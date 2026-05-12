# Content Hashing — Merkle Construction

**Normative for processors. Informative for agents.**

The agent emits CBOR envelopes (see [CBOR-ENVELOPE.md](CBOR-ENVELOPE.md))
without hashes. The processor adds **content hashes** during rendering,
producing the form that lands in storage and that downstream tools
(UI, AI agents, analytics) consume.

This document defines the hash construction so any processor produces
**the same hash** for the same envelope tree. That portability is the
property the rest of the system rests on: change-detection, mutation
analysis, and "find every call that touched this exact object state"
all assume hashes are reproducible across processors.

## 1. Why hashes

- **Mutation detection.** Compare the entry hash of `ARGUMENTS` to
  the exit hash of `ARGUMENTS_EXIT`; equal → no mutation, different →
  the call mutated something.
- **Cross-call object tracking.** Same `(object_id, hash)` pair across
  two different calls means the same instance with the same content.
  Same `object_id`, different `hash` → the instance was mutated
  between the two calls.
- **Storage compaction.** Identical sub-trees hash identically;
  storage layers may dedupe. (DeepFlow's reference processor does not
  do this today, but the property is preserved.)
- **AI tool affordance.** "Find every call where the customer state
  with hash `abc123…` appeared" is a single ClickHouse predicate.

## 2. Inputs and outputs

**Input** — the *humanized* representation of one envelope tree:
nested maps and arrays where each envelope has `object_id` and
`class` keys at the top, and its user fields as siblings (the form
produced by decoding the CBOR envelope and replacing integer field
IDs with names — see §3 below).

**Output** — the same tree transformed so each envelope carries a
`__meta__` block containing `id`, `class`, and a content **`hash`**:

```
{
  "__meta__": {"id": <object_id>, "class": "<class.name>", "hash": "<hex>"},
  ...userFields
}
```

The processor MUST also expose a single **root hash** for the entire
tree (used to populate the `payloads.root_hash` ClickHouse column).
Root hash semantics depend on the tree's shape — see §6.

## 3. Humanization (Informative)

Before hashing, the processor decodes the CBOR envelope and renames
integer field IDs to readable strings:

| CBOR field id | Humanized key |
|---|---|
| 1 (`OBJECT_ID`) | `"object_id"` |
| 2 (`CLASS_NAME`)| `"class"` |
| 3 (`VALUE`)     | inlined as siblings (see below) |
| 4 (`REF_ID`)    | `"ref_id"` |
| 5 (`CYCLE_REF`) | `"cycle_ref"` |

The `VALUE` field is **flattened**, not preserved as a nested key:
when `VALUE` is a CBOR map, its key/value pairs become siblings of
`object_id` and `class`. When `VALUE` is a CBOR array, it becomes a
sibling key `"items"`. When `VALUE` is a scalar, it becomes a sibling
key `"value"`.

So the envelope CBOR
`{1: 17, 2: "Order", 3: {"id": 42, "total": 99.50}}`
humanizes to
`{"object_id": 17, "class": "Order", "id": 42, "total": 99.50}`.

This humanization is part of the hash input — it MUST be performed
exactly as specified, or hashes will not be portable.

## 4. Hash function

- Algorithm: **MD5** (RFC 1321).
- Input encoding: **UTF-8 bytes of a JSON serialization** of the
  hash-input form (§5).
- Output encoding: **lowercase hex** (32 hex characters, no separator,
  no `0x` prefix).

> MD5 is chosen for speed and 128-bit width. It is NOT
> cryptographically used here — collisions could only mislead a
> mutation-detection query, not subvert any security boundary. A
> future major version MAY switch to BLAKE3 if collision tolerance
> tightens.

## 5. JSON canonicalization for hashing

**Honest framing.** The reference implementation uses Jackson
`ObjectMapper` with `ORDER_MAP_ENTRIES_BY_KEYS=true` and otherwise
default settings. That is the entire canonicalization. It is
**not** a formally portable canonical-JSON spec (e.g. RFC 8785 JCS).
Producing the same hash from a non-Java implementation requires
matching Jackson's defaults byte-for-byte — which is achievable but
fragile. A future major version SHOULD adopt JCS for guaranteed
cross-language portability; this v1 doesn't.

The bytes Jackson actually emits, pinned for porters:

1. **Map keys sorted** by `String.compareTo` order. For ASCII keys
   this matches UTF-8 byte order; for keys containing characters
   above U+FFFF it compares by UTF-16 code units (which differs
   from UTF-8 byte order for surrogates). Non-ASCII keys in
   captured payloads are rare in practice but exist — beware.
2. **Array order preserved** — list order is data; sorting would
   hide real mutations of ordered collections.
3. **No whitespace** between tokens.
4. **Strings**: standard JSON escape — `\\`, `\"`, `\b`, `\f`,
   `\n`, `\r`, `\t`, `\u00XX` for other control chars (U+0000 to
   U+001F). Forward-slash NOT escaped. Non-ASCII characters
   emitted as raw UTF-8 bytes (NOT `\uXXXX`-escaped).
5. **Integers** as decimal, no leading zero, no `+` sign.
6. **Doubles** formatted via Java `Double.toString()` — i.e.
   shortest decimal round-trippable to the same IEEE-754 double,
   with a few Java-specific quirks: always at least one digit
   after the decimal point (`1.0`, not `1`), scientific notation
   outside `1.0E-3 .. 1.0E7` (`1.0E-4` rather than `0.0001`).
   This is **not** the same as ECMAScript `Number.prototype.toString`,
   so a JavaScript or Python implementation must explicitly mirror
   Java's behavior to produce identical hashes.
7. **Booleans** as `true` / `false`. **Null** as `null`.
8. **NaN / ±Infinity**: Jackson errors on these by default.
   Producers SHOULD NOT capture non-finite floats; if they do,
   they MUST replace them with a sentinel string (e.g. `"NaN"`)
   before encoding. (This is technically a producer concern, not
   a hashing one, but it bites here first.)

A non-Java implementor SHOULD validate against the reference by:
1. Capturing one canned object in both implementations.
2. Comparing the canonical JSON byte-for-byte before running
   MD5. Differences here, not in MD5, are where portability
   breaks.

## 6. Walk algorithm

The processor walks the humanized tree depth-first. For each node:

### 6.1 Cycle reference

If the node is a map containing key `"ref_id"`: pass through
unchanged. Cycle references carry no content of their own; their
identity is the `ref_id`. They contribute their *string form* to a
parent's hash.

### 6.2 Envelope

A map containing both `"object_id"` and `"class"` keys.

1. Recurse into every other key. Each child returns two things:
   - a `transformed` value (with `__meta__` injected on its
     envelope sub-trees)
   - a `hashInput` value (with each child envelope replaced by **its
     own hash string**)
2. Build `hashInput` map: copy the envelope's user keys (everything
   except `"object_id"` and `"class"`), substituting each child's
   `hashInput` for its raw value.
3. Compute `hashHex = md5(canonicalJson(hashInput))`.
4. Build `transformed` map:
   - First key `"__meta__"` →
     `{"id": <object_id>, "class": "<class>", "hash": <hashHex>}`.
   - Then the envelope's user keys with the recursed `transformed`
     values.
5. Return `{transformed, hashInput: <hashHex>}` — i.e. the **string
   hash itself** is what propagates into the parent's hash input.

This is the Merkle property: a parent's hash depends on its children's
hashes (not on their full content), so any mutation anywhere in the
subtree changes the root hash by exactly one MD5 chain step.

### 6.3 Plain map (not envelope, not cycle ref)

Recurse into each value. The map is rebuilt twice — once for
`transformed`, once for `hashInput` — using the recursed values.
No `__meta__` injection. No own hash computed.

### 6.4 List

Recurse into each element. Build the `transformed` and `hashInput`
arrays in element order.

### 6.5 Scalar

`transformed = hashInput = the scalar itself`.

## 7. Root hash semantics

The root hash for a record's payload depends on the root node:

- **Envelope at the root.** The root hash IS the envelope's own
  `__meta__.hash`.
- **Array at the root** (e.g. an `ARGUMENTS` payload, which is a
  bare CBOR array of envelopes). The root hash is computed over
  the canonical JSON of the array's `hashInput` form (each element
  envelope replaced by its hash). This guarantees: change any
  argument's content → root hash changes.
- **Plain map at the root.** Same: canonical JSON of the map's
  `hashInput` form.
- **Scalar at the root.** Canonical JSON of the scalar.

A processor MUST produce the same root hash for two payloads whose
`hashInput` forms are bytewise-equal canonical JSON.

## 8. What this enables (Informative)

- Comparing two `ARGUMENTS_EXIT` records' root hashes against the
  matching `ARGUMENTS` record's root hash → **mutation present?**
  (Different hashes = yes.)
- ClickHouse predicate `WHERE root_hash = '...'` → all calls with the
  same payload content, regardless of `object_id`.
- ClickHouse predicate `WHERE has(object_ids, 12345)` → all calls
  that touched a specific instance, regardless of payload content.
- ClickHouse predicate combining both → "did instance X ever appear
  with state Y?"

These predicates work cross-agent-run only when payloads are
content-equivalent. Same instance with different `object_id` across
runs (because IDs are agent-run-scoped) will have the same content
hash — so cross-run identity is by hash, not by ID. This is a
deliberate consequence of §3.1 of CBOR-ENVELOPE.md.

## 9. Worked example

Two envelopes — an Author with one Book inside — taken end-to-end
from CBOR through canonical JSON to the final transformed form
with `__meta__` blocks. Every byte the canonicalizer emits matters;
this example shows what changes at each step.

### Step 1 — input CBOR envelope

The agent emitted (diagnostic notation):

```
{1: 9,
 2: "com.example.Author",
 3: {"name":  "Tolkien",
     "books": [{1: 11,
                2: "com.example.Book",
                3: {"isbn": "9780618"}}]}}
```

### Step 2 — humanize (§3)

Decode CBOR and rename integer field IDs. The `VALUE` map's keys
flatten as siblings of `object_id` and `class`:

```
{
  "object_id": 9,
  "class":     "com.example.Author",
  "name":      "Tolkien",
  "books":     [{"object_id": 11,
                 "class":     "com.example.Book",
                 "isbn":      "9780618"}]
}
```

### Step 3 — walk + compute child hashes first

The walker recurses depth-first. The inner Book envelope hashes first.

Book's `hashInput` (user keys only — drop `object_id` and `class`):

```
{"isbn": "9780618"}
```

Canonical JSON (keys sorted, no whitespace):

```
{"isbn":"9780618"}
```

```
md5("{\"isbn\":\"9780618\"}") = 6dd1e09c9d9e5b4f2e7e4d3aa9f4be8c   (illustrative)
```

So Book's `hashInput` value that propagates upward is the **hash
string** `"6dd1e09c…"`, not its content.

### Step 4 — Author's hashInput uses Book's hash, not Book's content

Author's `hashInput` (user keys only, with each child envelope
replaced by its hash string):

```
{
  "name":  "Tolkien",
  "books": ["6dd1e09c9d9e5b4f2e7e4d3aa9f4be8c"]
}
```

Canonical JSON:

```
{"books":["6dd1e09c9d9e5b4f2e7e4d3aa9f4be8c"],"name":"Tolkien"}
```

Note `books` sorts before `name` alphabetically. Author's hash:

```
md5(...) = a3f72c1b3e2d8895f4c11d09e6b27a4d   (illustrative)
```

### Step 5 — the transformed output

What lands in storage / what the UI consumes:

```
{
  "__meta__": {"id": 9, "class": "com.example.Author",
               "hash": "a3f72c1b3e2d8895f4c11d09e6b27a4d"},
  "name":  "Tolkien",
  "books": [
    {"__meta__": {"id": 11, "class": "com.example.Book",
                  "hash": "6dd1e09c9d9e5b4f2e7e4d3aa9f4be8c"},
     "isbn": "9780618"}
  ]
}
```

### Step 6 — root hash

For an envelope-rooted payload, the root hash equals the root
envelope's `__meta__.hash` — Author's hash above. For an
array-rooted payload (an ARGUMENTS record's outer array), the
root hash is computed over the canonical JSON of the array's
`hashInput` form (each element envelope replaced by its hash
string). See §7.

### The Merkle property in action

Mutate Book's `isbn` to `"9780620"`:

1. Book's `hashInput` → `{"isbn":"9780620"}` → different MD5,
   say `7e54bb…`.
2. Author's `hashInput` → `{"books":["7e54bb…"],"name":"Tolkien"}`
   → different MD5.
3. Author's `__meta__.hash` changes even though *Author's own
   fields* didn't move. That's the Merkle propagation — the
   reason the UI also exposes `own_hash` (a sibling hash that
   ignores child content; see
   [../reference/concepts.md](../reference/concepts.md) and
   [../reference/bug-finding.md](../reference/bug-finding.md)).

### A note on illustrative vs real bytes

The MD5 values above are placeholders to show the *flow*. A
real cross-implementation conformance test must compute the
actual MD5 of the actual canonical JSON bytes — that is the
whole point of the pinning in §5. Two implementations that
produce different bytes from the same input differ before MD5,
not after; debug by diffing the canonical-JSON outputs.

## 10. Implementation notes (Informative)

- The reference implementation lives at
  `core/codec/src/main/java/com/github/gabert/deepflow/codec/Hasher.java`.
  The hashing pass is wired up by
  `core/serializer/.../destination/RecordHashEnricher.java`, which
  applies `Hasher.hash()` to the JSON values of the `TI`, `AR`,
  `AX`, and `RE` rendered tag lines.
- The reference processor injects hashes lazily on a worker thread,
  not on the agent's hot path. New processors SHOULD do the same —
  hashing is O(payload size) and adds latency to the ingest path
  but not to the traced application.
- Collision risk: MD5's 128-bit space is ample for the per-request
  scope where hashes are compared. Collisions across the whole
  trace store are theoretically possible but operationally
  irrelevant (≈10⁹ payloads before P(collision) reaches 10⁻¹⁹).
