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
  storage layers may dedupe. (Arachna Trace's reference processor does not
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
`__meta__` block containing `id`, `class`, and **two** content
hashes:

```
{
  "__meta__": {
    "id":       <object_id>,
    "class":    "<class.name>",
    "hash":     "<hex>",      // Merkle deep hash — see §6
    "own_hash": "<hex>"       // per-envelope own-state hash — see §7
  },
  ...userFields
}
```

- **`hash`** is a **Merkle deep hash**: the envelope's own scalar
  content *plus* the hashes of its children. Changes anywhere in
  the subtree propagate upward. Right for tree-drilling
  ("something changed somewhere; walk down to find it"). Full
  construction in §6.
- **`own_hash`** is the envelope's **own-state hash**: scalars
  plus child *ids* (not child content). Changes only when this
  object's own fields change; invariant under cycle-entry
  direction. Right for flat row inspection ("did this object's
  own data move"). Full construction in §7.

The processor MUST also expose a single **root hash** for the entire
tree (used to populate the `payloads.root_hash` ClickHouse column).
Root hash semantics depend on the tree's shape — see §8.

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

## 7. `own_hash` — per-envelope own-state hash

A second hash, computed alongside `hash` and emitted on the same
`__meta__` block. Same MD5 algorithm, same canonical-JSON
encoding (§5), different input tree.

Where `hash` propagates child content upward (Merkle), `own_hash`
collapses every child envelope to its **id only**, so child
content does NOT enter the parent's own-state hash.

### 7.1 Why two hashes

| Question the reader is asking | Right hash to compare |
|---|---|
| "Did anything anywhere in this subtree change?" | `hash` (Merkle) |
| "Did **this** object's own fields change?" | `own_hash` |
| "Same object seen from two sides of a cycle — same value?" | `own_hash` (invariant) |
| "Same envelope, different agent runs — content match?" | `hash` (cross-run content-equivalent) |

Without `own_hash`, a row reader inspecting an `Author` whose
`books[i].isbn` was rewritten by another method sees the `Author`
row's `hash` shift — even though the author itself didn't change.
`own_hash` cleanly separates the two questions. See
[../reference/bug-finding.md](../reference/bug-finding.md) for
the user-facing workflow that uses it.

### 7.2 Algorithm

For an envelope `E` at the root of its own-hash walk (i.e. the
envelope whose `own_hash` is being computed):

1. **Strip `object_id` and `class`** from the input. Envelope
   identity is *not* part of the own-hash input. Two same-class
   instances with identical scalar fields therefore **collide**
   on `own_hash`. That collision is intentional — `own_hash`
   answers *"did this object's own data move"*, and the
   adjacent `object_id` is the stable identity at envelope
   boundaries.
2. **Walk every remaining (user) key** with the rules below.
3. **Compute** `own_hash_hex = md5(canonicalJson(processed_tree))`
   using the same canonical JSON as §5.

Walk rules:

- **Scalar** → keep as-is.
- **Plain map** (not envelope, not cycle ref) → recurse into
  every value with the same rules; rebuild the map.
- **List** → recurse into every element; rebuild the list in
  order.
- **Child envelope** (a map with both `object_id` and `class`,
  encountered below the root) → collapse to
  `{"__ref__": <child.object_id>}`. Child content does NOT
  propagate. This is the key difference from the Merkle walk
  in §6.
- **Cycle reference** (a map containing `ref_id`) → collapse to
  `{"__ref__": <ref_id>}`. **Same shape** as a collapsed child
  envelope. This is why `own_hash` is invariant under
  cycle-entry direction: a JPA bidirectional `Author ⇌ Book`
  serialized from either side produces the same `own_hash` for
  both, because the back-reference always reduces to the same
  `__ref__` shape.

The collapse shape `{"__ref__": <id>}` is **private to own-hash
input**. It does NOT appear in the rendered output that lands
in `payload_json`; it exists only inside the bytes fed to MD5.

### 7.3 What changes `own_hash`, what doesn't

Falls out of §7.2's rules, but worth enumerating because the
behaviour is the whole reason `own_hash` is useful.

`own_hash` is sensitive to the envelope's own scalars **plus**
the **identity** and **structural placement** of any child
envelopes it references — never the children's content.

**Changes `own_hash`:**

- A scalar field on the envelope itself changes
  (`Author.name: "Tolkien" → "JRR"`).
- A child instance is **added** (a new Book appended to
  `Author.books`).
- A child instance is **removed**.
- A child instance is **replaced by a different instance** with
  a different `object_id` (Book #11 → Book #99 in the same
  slot — replacement, not mutation).
- The **order** of children changes (list reorder; list order
  is part of canonicalization per §5 rule 2).
- A child reference moves between fields (`primary` slot vs.
  `secondary` slot of the same parent).

**Does NOT change `own_hash`:**

- A child instance's **content** is mutated, same `object_id`
  (Book #11's `isbn` rewritten in place). That mutation shifts
  the child's own `own_hash` and propagates into the parent's
  Merkle `hash` — but the parent's `own_hash` is unchanged.
  This is the central difference from `hash`.
- The graph is traversed from a different side of a cycle.
  The cycle marker collapses to the same `__ref__` shape as a
  non-root envelope, so both traversal directions produce the
  same `own_hash`. (This is the mitigation for the cyclic-graph
  false positives catalogued in
  [process/KNOWN_BUGS.md → D-09](../process/KNOWN_BUGS.md).)
- The envelope's `object_id` or `class` is renumbered between
  agent runs (those keys are stripped from the own-hash input
  at root, per §7.2 step 1).

Reader's mental model: `own_hash` answers *"is this object,
right now, the same object I saw last time"* — where *the same
object* means **same own scalars** + **same set/positions of
children by id**. Mutations one level down are the next
envelope's problem.

### 7.4 What this enables

- **Per-row mutation detection** in the UI's WatchPanel — bands
  flip on `own_hash` transitions, not on `hash` drift caused by
  child mutations.
- **Cycle-direction invariance** — sidesteps the false positives
  catalogued in [process/KNOWN_BUGS.md → D-09](../process/KNOWN_BUGS.md).
- **Indexed cross-call lookup** — `payloads.own_hashes` is a
  `LowCardinality(String)` array column with a bloom-filter
  index, so `WHERE has(own_hashes, '<hex>')` finds every call
  where some envelope had this exact own-state — across the
  session, the request, or (subject to retention) the trace
  store.

### 7.5 Reference implementation

The reference Java implementation is `Hasher.ownHashInput(node,
atRoot)` in
`arachna-trace-shared/codec/src/main/java/com/github/gabert/arachna/trace/codec/Hasher.java`.
A non-Java processor produces byte-identical `own_hash` values
by implementing the same algorithm plus the same canonical JSON
(§5).

## 8. Root hash semantics

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

## 9. What this enables (Informative)

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

## 10. Worked example

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
string). See §8.

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

## 11. Implementation notes (Informative)

- The reference implementation lives at
  `arachna-trace-shared/codec/src/main/java/com/github/gabert/arachna/trace/codec/Hasher.java`.
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
