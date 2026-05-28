# CBOR Identity Envelope

**Normative.** Defines how captured Java objects (or your language's
analog) are packed into CBOR for transport in `ARGUMENTS`,
`ARGUMENTS_EXIT`, `THIS_INSTANCE`, `RETURN`, and `EXCEPTION` records.

The envelope is what makes Arachna Trace more than a span tracker: every
captured value carries its own stable identity and runtime class so
the consumer can reason about *which instance* this is, not just
*what shape* it has.

## 1. Vocabulary

- **Envelope** — a CBOR map wrapping one captured value, carrying
  `OBJECT_ID`, `CLASS_NAME`, and `VALUE`.
- **Cycle reference** — a CBOR map standing in for an already-seen
  envelope, carrying `REF_ID` and `CYCLE_REF`. Breaks cycles so a
  graph encodes as a finite tree.
- **Field IDs** — the integer keys used inside envelopes and cycle
  references (CBOR major type 0). Integers are used instead of
  strings to keep the wire small.

## 2. Field IDs

These integer keys are the contract. They MUST be used as the map
keys exactly as listed; consumers MUST recognize them by integer
identity.

| ID | Name        | Type | Role |
|----|-------------|------|------|
| 1  | `OBJECT_ID` | uint64 | Stable unique ID of this object instance, within the producer's agent-run. |
| 2  | `CLASS_NAME`| text   | Runtime class name as a UTF-8 string. |
| 3  | `VALUE`     | (varies) | The serialized object content. May be a map, an array, or a scalar string. |
| 4  | `REF_ID`    | uint64 | In a cycle reference: the ID of the already-seen object. |
| 5  | `CYCLE_REF` | bool   | In a cycle reference: always `true`. |

IDs 6–127 are reserved for future use. Producers MUST NOT use them.
Consumers MUST treat unknown integer keys at the top of an envelope
as opaque and ignore them.

## 3. Envelope shape

Every captured object is encoded as a CBOR map with at least
`OBJECT_ID`, `CLASS_NAME`, and `VALUE`:

```
{
  1: <object_id>,        // OBJECT_ID
  2: "<class.name>",     // CLASS_NAME
  3: <value>             // VALUE — see §4
}
```

In CBOR diagnostic notation:

```
{1: 17, 2: "com.example.Order", 3: {"id": 42, "total": 99.50}}
```

In hex (illustrative):

```
A3                              # map(3)
   01                           # field 1 (OBJECT_ID)
   11                           # uint(17)
   02                           # field 2 (CLASS_NAME)
   71                           # text(17)
      636F6D2E6578616D706C652E4F72646572  # "com.example.Order"
   03                           # field 3 (VALUE)
   ...                          # value bytes
```

### 3.1 OBJECT_ID requirements

- MUST be a positive int64 (1 .. 2⁶³-1).
- Within one agent-run, the same logical instance MUST produce the
  same `OBJECT_ID` everywhere it appears.
- Different live instances MUST produce different `OBJECT_ID`s.
- IDs MUST NOT be reused within an agent-run, even after the
  underlying object becomes unreachable.

The full identity contract — including how to satisfy these
guarantees in languages without weak references — is in
[IDENTITY-MODEL.md](IDENTITY-MODEL.md).

### 3.2 CLASS_NAME requirements

- A UTF-8 string naming the *runtime* type of the object (not its
  declared type).
- For Java, this SHOULD be the fully qualified class name as
  returned by `Class.getName()`.
- For other languages, producers SHOULD use the language's most
  natural fully-qualified type name (e.g. Python's `module.Class`,
  Go's `pkg.Type`).
- Anonymous / synthetic classes SHOULD be reported with whatever
  name the language runtime gives them.

### 3.3 VALUE shape

`VALUE` (field 3) is one of:

- **A CBOR map.** The object's fields/properties as `name → child`,
  where each child value is itself either an envelope, a cycle
  reference, a primitive, an array, or `null`.
- **A CBOR array.** Used when the captured object is itself a
  collection (a Java `List`, an array, etc.) or when the captured
  thing is the argument list of a method (the `ARGUMENTS` record).
  Elements are envelopes/refs/primitives.
- **A CBOR text string.** Used as a sentinel for opaque values.
  The reference agent uses `"<proxy>"` for unresolvable JPA proxies;
  other producers MAY use other markers — they SHOULD start with
  `<` and end with `>` to make them visually distinct from real
  string data.
- **A CBOR primitive** (int, float, bool, null) — only when the
  *captured value itself* is a primitive (e.g. when the method
  argument is `int`). Even then, an envelope MAY be omitted entirely
  in favour of a bare primitive — see §5.

A producer MUST be consistent: a given object kind always encodes
its `VALUE` the same way.

## 4. Cycle references

When a producer encounters an object it has already started
serializing within the current top-level call (i.e. the object's
graph contains a cycle), it MUST emit a cycle reference instead of
recursing:

```
{
  4: <ref_id>,           // REF_ID — the OBJECT_ID of the already-seen instance
  5: true                // CYCLE_REF
}
```

`REF_ID` MUST equal the `OBJECT_ID` previously emitted for that
instance. The consumer can resolve a cycle reference to its target
by scanning earlier envelopes in the same record, or by ID lookup
across the trace.

The cycle-detection scope is **one top-level captured value** (one
`ARGUMENTS` record, one `RETURN` record, etc.) — not the whole call,
not the whole stream. This means the same instance may appear as a
full envelope in multiple records on the same call (e.g. as an
argument and again as a return value), and that is correct, not a
cycle.

## 5. Primitives without envelopes

A captured top-level scalar (e.g. a method that returns `int`) MAY be
encoded as a bare CBOR primitive instead of an envelope. In that
case the consumer cannot attribute identity to it (which is fine —
primitives have no instance identity).

A captured top-level array or list MAY be encoded as a bare CBOR
array of envelope/primitive elements; the array itself need not be
wrapped in an envelope. The reference agent does this for the
`ARGUMENTS` record's outer array.

A producer MUST document, per record type, which top-level shapes
it uses. A consumer MUST handle all of: bare primitive, bare array,
envelope-wrapped object.

## 5b. Truncation marker

> **Known bug.** The shape described in this section drops the
> envelope wrapper, which kills identity tracking, mutation
> detection, and object-tree walking at the truncated node — see
> [process/KNOWN_BUGS.md → D-10](../arachna-trace-agents/docs/process/KNOWN_BUGS.md). The
> current shape is normative for wire-format 1.4; a future
> minor or major bump will envelope-wrap the marker (or replace
> it with per-field truncation). Until then, set
> `max_value_size=0` (the agent default) if you need accurate
> identity / mutation semantics.

When a producer is configured with a per-value size cap (the
reference Java agent's `max_value_size` config; `0` = unlimited),
any CBOR-encoded value whose serialized length exceeds the cap
MUST be replaced with a fixed-shape **truncation marker**:

```
{
  "__truncated":   true,
  "original_size": <int>      // bytes of the payload that would have been emitted
}
```

The marker:

- is a **bare CBOR map**, NOT an envelope. There is no `OBJECT_ID`,
  no `CLASS_NAME`, no `VALUE` wrapping.
- uses **string keys** (`"__truncated"`, `"original_size"`), NOT
  envelope integer field IDs.
- replaces the value entirely — there is no recovery of the
  truncated content.
- can appear in any of `THIS_INSTANCE`, `ARGUMENTS`, `ARGUMENTS_EXIT`,
  `RETURN`, or `EXCEPTION`.

A consumer MUST recognize the shape and treat the value as opaque
when rendering or hashing. The reference Java agent emits the marker
in `ValueEncoder.truncationMarker(int)`.

Two design implications for downstream consumers:

1. **Hashing.** A truncation marker hashes like any other CBOR map.
   Two truncated values with different original sizes hash
   differently; two truncated values with the same original size
   hash identically. This is acceptable — truncation is a degraded
   capture, and asking the consumer to disambiguate would require
   re-introducing payload bytes.
2. **Mutation detection.** If the entry value was truncated and the
   exit value was not (or vice-versa), the hashes will differ and
   the consumer will report mutation — even if the underlying object
   was unchanged. This is a known false-positive of the truncation
   path; producers that need accurate mutation detection MUST
   configure `max_value_size = 0` or pick a cap large enough to
   avoid clipping.

The keys `"__truncated"` and `"original_size"` are reserved within
this spec; producers MUST NOT emit a normal CBOR envelope whose
user-field name collides with either. (The `__meta__` reservation
in §8 likewise applies.)

## 6. Argument lists (ARGUMENTS, ARGUMENTS_EXIT)

The reference Java agent passes the argument array to its CBOR
encoder, and the envelope serializer wraps the array itself (since
arrays are wrappable per `EnvelopeModifier.modifyArraySerializer`).
The resulting payload is:

```
{
  1: <object_id-of-the-array>,
  2: "java.lang.Object[]",             // CLASS_NAME — humanized form (ClassNameCache renders `[L...;` as `...[]`)
  3: [<envelope-of-arg0>, <envelope-of-arg1>, ...]   // VALUE = the args
}
```

i.e. an envelope **wrapping** a CBOR array, where each element is
itself an envelope (or bare primitive). The reference processor's
renderer (`RecordRenderer.decodeArgumentsPayload`) unwraps the outer
envelope and renders only the inner `VALUE` array — but the **wire
shape is the wrapped form**.

A non-Java agent SHOULD do the same (envelope-wrap the argument
list) for cross-implementation parity.

The element count of the inner array MUST match the method's
declared arity. A producer that captures only a subset of arguments
(e.g. for performance) MUST still emit the full arity by using a
placeholder envelope with `CLASS_NAME = "<omitted>"` and
`VALUE = "<omitted>"` for the skipped slots, so positional
correspondence is preserved.

Consumers MUST also accept a **bare array** at the top level of
ARGUMENTS / ARGUMENTS_EXIT (no outer envelope) — the reference
processor handles both shapes transparently. This permits
lightweight implementations that don't have an obvious "array
identity" to track.

## 7. Exceptions (EXCEPTION)

The reference encoding of a thrown exception:

```
{
  1: <object_id>,
  2: "<exception.class.Name>",
  3: {
    "message": "<text or null>",
    "stacktrace": [<frame>, <frame>, ...]
  }
}
```

Each `<frame>` SHOULD be either a string (e.g.
`"com.example.Foo.bar(Foo.java:42)"`) or a CBOR map
`{"class": ..., "method": ..., "file": ..., "line": ...}`. Producers
SHOULD prefer the structured form — it is far easier for downstream
tools to consume.

## 8. Field naming inside VALUE maps

When `VALUE` is a map, the key is the field's name as a UTF-8 string.
Producers SHOULD use the source-level name. Producers MAY apply a
language-conventional transform (e.g. Java getter `getFoo()` → field
`"foo"`); when they do, they SHOULD do so consistently within an
agent-run.

Reserved keys (consumers treat these specially in the rendered text
view — see [TAGS.md](TAGS.md) — but inside CBOR they have no special
meaning):

- `__meta__` — added by the consumer's hashing pass to a humanized
  representation. MUST NOT appear in producer output.

A producer that emits a field literally named `__meta__` MUST escape
it (e.g. as `__meta___user`); otherwise it will collide with the
hashing convention downstream.

## 9. Versioning of the envelope

The envelope shape is stable as of wire-format `major=1`. Adding new
field IDs in the 6–127 range is a **minor** change. Renaming or
repurposing existing IDs (1–5) is a **major** change.

## 10. Worked examples

CBOR is shown here in **diagnostic notation** (RFC 8949 §8) —
the human-readable form most CBOR libraries print. Bytes are
illustrative; the contract is the structure, not the hex.

### 10.1 A simple object

Java:

```java
class Order {
    long id = 42;
    BigDecimal total = new BigDecimal("99.50");
}
```

Captured as one ARGUMENTS element or one RETURN value:

```
{1: 17,                        / OBJECT_ID — assigned by the agent /
 2: "com.example.Order",       / CLASS_NAME — runtime type /
 3: {"id":    42,              / VALUE — map of user fields /
     "total": "99.50"}}
```

The same instance reappearing on a later call (still in this
agent-run) produces the **same** `OBJECT_ID: 17`. If its `total`
field changes between calls, only the VALUE map changes —
OBJECT_ID stays.

### 10.2 An argument list (one ARGUMENTS record)

Method:

```java
void processOrder(Order order, int retries);
```

Called with the order above and `retries=3`. The agent
envelope-wraps the **argument array itself**, then each composite
argument:

```
{1: 31,                                    / OBJECT_ID of the args array /
 2: "java.lang.Object[]",                  / CLASS_NAME — humanized form, not JVM-internal `[L...;` /
 3: [                                      / VALUE = the args, in order /
   {1: 17,                                 / arg 0: envelope-wrapped Order /
    2: "com.example.Order",
    3: {"id": 42, "total": "99.50"}},
   3                                       / arg 1: bare primitive (no envelope) /
 ]}
```

Note arg 1 is a bare CBOR uint — primitives carry no instance
identity (§5). The agent could equivalently emit the args as a
bare array with no outer envelope; consumers must handle both
shapes (§6 last paragraph).

### 10.3 A cycle reference in context

Java (bidirectional Author ⇌ Book — JPA-typical):

```java
class Author { String name; List<Book> books; }
class Book   { String isbn; Author author; }
```

Captured when an `Author` with one `Book` is serialized. The
agent encodes the Author, descends into `books[0]`, encodes the
Book, then encounters `author` again — a cycle. It emits a
cycle reference back to the Author's OBJECT_ID:

```
{1: 9,                                 / OBJECT_ID of Author #9 /
 2: "com.example.Author",
 3: {"name": "Tolkien",
     "books": [
        {1: 11,                        / OBJECT_ID of Book #11 /
         2: "com.example.Book",
         3: {"isbn":   "9780618...",
             "author": {4: 9,          / REF_ID — back to Author #9 /
                        5: true}}}     / CYCLE_REF — marker /
     ]}}
```

The cycle reference is **not** an envelope: no OBJECT_ID, no
CLASS_NAME, no VALUE. Just `{4: <ref>, 5: true}`. The consumer
resolves `4: 9` by scanning previously emitted envelopes in the
same record (or by global ID lookup).

If the Author appeared again as the root of a *different* record
(say, another method's argument), the agent would emit a fresh
full envelope — cycle detection is scoped to one top-level
captured value (§4).

### 10.4 A truncation marker

Same method, but the agent is configured `max_value_size=4096`
and the Order has 50 KB of customer notes. The Order is replaced
in the wire:

```
{1: 31,                                / args array, unchanged /
 2: "java.lang.Object[]",
 3: [
   {"__truncated":   true,             / bare CBOR map, NOT an envelope /
    "original_size": 51234},           / string keys, NOT integer field IDs /
   3
 ]}
```

Three things to notice:

- **No envelope wrapper.** OBJECT_ID, CLASS_NAME, and VALUE are
  gone. Identity is lost on truncated values — the consumer
  can't follow them across calls.
- **String keys.** `"__truncated"` and `"original_size"`, not
  the integer field IDs of an envelope.
- **The args array itself stays envelope-wrapped.** Only the
  oversized element is replaced.

The marker hashes like any other CBOR map (§5b); two truncated
values with the same `original_size` hash identically. This is a
known false-positive of mutation detection — use
`max_value_size=0` if you need byte-accurate diffs.

### 10.5 An exception with stacktrace

Method throws `NullPointerException("order is null")`. The agent
emits an EXCEPTION record carrying:

```
{1: 88,                                / OBJECT_ID of the exception instance /
 2: "java.lang.NullPointerException",  / CLASS_NAME — note: on the envelope, not in VALUE /
 3: {"message":    "order is null",
     "stacktrace": [
        "com.example.OrderService.process(OrderService.java:42)",
        "com.example.OrderController.submit(OrderController.java:18)",
        "...elided..."
     ]}}
```

The exception's class name lives on the **envelope** (`CLASS_NAME`
= field 2), **not** inside the VALUE map. A consumer rendering
"java.lang.NullPointerException: order is null" reads them from
two different places.

### 10.6 Cross-reference

For the binary frame that wraps each of these CBOR payloads
(METHOD_START / ARGUMENTS / RETURN / EXCEPTION / etc.), see
[WIRE-FORMAT.md §7](WIRE-FORMAT.md) for a pinned byte-level
worked example.

## 11. Why CBOR

(Informative.) CBOR (RFC 8949) is chosen for:

- **Self-describing.** A consumer can decode without a schema. New
  envelopes added by a producer don't break older consumers.
- **Compact.** Integer field IDs avoid repeating string keys on every
  envelope. Real-world payloads are 30–50% smaller than equivalent
  JSON.
- **Wide language support.** Mature CBOR libraries exist for Java,
  Python, Go, Rust, JavaScript, .NET, C++, Swift. Porting an agent
  doesn't require writing a parser.
- **Stable spec.** RFC 8949 is final, with no anticipated breaking
  revisions.

A future version MAY introduce alternative encodings (e.g. Cap'n
Proto for performance-critical paths) under different `major`
numbers. Until then, CBOR is the only normative payload format.
