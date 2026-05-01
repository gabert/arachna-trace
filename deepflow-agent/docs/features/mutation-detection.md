# Mutation Detection

DeepFlow can detect when a method modifies its arguments by capturing
argument values both at method entry and at method exit.

## How to enable

Add `AX` to the `emit_tags` configuration:

```properties
emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE,AX
```

`AX` is not in the default tag set because capturing arguments twice per
method call doubles the serialization cost for arguments.

## How it works

1. On method entry, the agent serializes `allArguments` and emits an `AR`
   record.
2. On method exit, the agent serializes `allArguments` again and emits an
   `AX` record.
3. Comparing `AR` and `AX` for the same method call reveals what changed.

In the trace output:

```
TS;1000
MS;com.example::OrderService.process(java.util::List) -> void [public]
AR;[{"object_id":9,"class":"java.util.ArrayList","value":["item-A"]}]
TE;2000
AX;[{"object_id":9,"class":"java.util.ArrayList","value":["item-A","item-B"]}]
```

The list (object_id 9) had one item at entry and two items at exit. The
`process` method added `"item-B"`.

## Object identity

Every serialized object carries an `object_id` -- a stable unique identifier
assigned by `ObjectIdRegistry`. The same Java object instance always gets the
same `object_id` within a session.

This is what makes mutation detection work across method boundaries:

```
Method: validate(order)
  AR: order.object_id=42, order.status="PENDING"

Method: submit(order)
  AR: order.object_id=42, order.status="VALIDATED"
```

Same `object_id` (42), different `status` value. Something between `validate`
and `submit` mutated the order. Look at the `AX` of methods called between
them to find the culprit.

## What gets compared

In the HTTP pipeline, the server-side `RecordHashEnricher` walks every
captured envelope and injects a `__meta__` block carrying a Merkle
content hash (see [HASHING.md](../spec/HASHING.md) for the construction).
Each `payloads` row in ClickHouse therefore carries a `root_hash`
column.

For mutation detection, compare the `root_hash` of the `AR` (arguments
at entry) row to the `root_hash` of the matching `AX` (arguments at
exit) row of the same call. Equal hashes → the call did not mutate the
inputs. Different hashes → mutation; drill into the underlying JSON to
find which field changed.

In file mode, the same enricher runs in `FileDestination` before the
`.dft` lines are written to disk, so the JSON values for `TI`/`AR`/
`AX`/`RE` already carry their `__meta__.hash` values. Compare them
directly.

## Envelope structure

Arguments and return values are wrapped with identity metadata:

```json
{
  "object_id": 42,
  "class": "com.example.Order",
  "value": {
    "id": 1,
    "status": "PENDING",
    "items": {
      "object_id": 43,
      "class": "java.util.ArrayList",
      "value": ["item-A"]
    }
  }
}
```

Nested objects have their own `object_id`. Primitives, Strings, and
immutable types are serialized directly without an envelope.

Cycle references are handled with back-references:

```json
{
  "ref_id": 42,
  "cycle_ref": true
}
```

See [CBOR Codec](../internals/codec.md) for the full envelope specification.

## Limitations

- **Immutable arguments.** If a method receives a `String` and returns a
  different `String`, that's a new object -- not a mutation. AX will show
  the same original String. Mutation detection is for mutable objects
  (collections, POJOs, maps).

- **Serialization cost.** Enabling AX doubles argument serialization. For
  hot methods with large argument lists, this adds overhead. Consider
  enabling AX only for targeted debugging sessions.

- **Deep equality.** The content hash comparison is shallow -- it hashes
  the JSON representation. Nested object mutations are detected because
  the outer object's JSON changes. But if an inner object is replaced with
  a different instance that has identical content, it won't be flagged as
  a mutation (because the content didn't change).
