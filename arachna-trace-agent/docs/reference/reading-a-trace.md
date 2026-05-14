# Reading a trace

Three worked examples. The first is a simple data-flow bug visible by
inspecting one method's arguments and return value. The second uses
mutation detection (`AX` enabled) to catch a cross-thread interference
that no log-and-redeploy cycle would have made obvious. The third walks
a Merkle content hash through a deep object tree to localise a single
field change.

## A calculation bug

A trace fragment looks like this:

```
VR;1.3
TS;1730412345678
SI;alice-session-01
MS;com.example::Pricing.applyDiscount(int, int) -> int [public static]
TN;http-nio-8080-exec-3
RI;5
CL;42
AR;[100, 10]
TE;1730412345680
RT;VALUE
RE;1000
```

How to read it. The first line is the wire-format version. Then comes
the entry block: at timestamp `TS` (epoch ms), inside session
`alice-session-01`, on thread `http-nio-8080-exec-3` as part of
request `RI=5`, the method `Pricing.applyDiscount` was called from
caller line 42 with arguments `[100, 10]` — price 100, discount
percent 10. The exit block records that the call returned 2 ms later
with the value `1000`.

The bug is visible directly. A 10% discount on a price of 100 should
return 90, not 1000 — `applyDiscount` is multiplying where it should
be subtracting. Finding this without the trace would typically mean
adding a print statement to that method and running the scenario
again.

## Mutation across threads

Mutation detection is useful when something else is modifying shared
state during a call. With `AX` (arguments at exit) enabled in
`emit_tags`, Arachna Trace captures arguments both at method entry (`AR`)
and at method exit (`AX`); if an object's content changed during the
call, the two differ.

Suppose `Math.multiplyByTwo(Counter)` is expected to return
`2 × counter.value`. A test passes in a counter holding 42 and
expects 84. It gets 108 instead. Querying the trace store for
`object_id:17` lines the two threads up side by side. The left
column — `multiplyByTwo`'s trace — is deliberately split into its
entry block (top) and exit block (bottom), with the other thread's
trace sitting between them, so the time relationship between the
two threads is visible at a glance: every event in the right column
happens between the left column's entry and exit.

<table>
<tr>
<th><code>http-nio-8080-exec-3</code> (RI=5)</th>
<th><code>background-worker-1</code> (RI=7)</th>
</tr>
<tr>
<td>
<small><pre>
TS;70
SI;alice-session-01
MS;com.example::Math.multiplyByTwo(Counter) -> int [public static]
TN;http-nio-8080-exec-3
RI;5
CL;42
AR;[{"object_id":17,"class":"com.example.Counter","value":42}]
</pre></small>
</td>
<td></td>
</tr>
<tr>
<td></td>
<td>
<small><pre>
TS;73
SI;alice-session-01
MS;com.example::Inventory.recount(Counter) -> void [public static]
TN;background-worker-1
RI;7
CL;120
AR;[{"object_id":17,"class":"com.example.Counter","value":42}]
--- nested call ---
TS;75
SI;alice-session-01
MS;com.example::Counter.setValue(int) -> void [public]
TN;background-worker-1
RI;7
CL;88
TI;17
AR;[54]
TE;76
RT;VOID
--- back to Inventory.recount ---
TE;78
RT;VOID
AX;[{"object_id":17,"class":"com.example.Counter","value":54}]
</pre></small>
</td>
</tr>
<tr>
<td>
<small><pre>
TE;80
RT;VALUE
RE;108
AX;[{"object_id":17,"class":"com.example.Counter","value":54}]
</pre></small>
</td>
<td></td>
</tr>
</table>

On the left, `Math.multiplyByTwo` enters at `TS=70` with the
`Counter` argument (`object_id=17`) holding 42, and exits at
`TE=80` with the same Counter holding 54 — the AR/AX divergence
proves the Counter was mutated mid-call. On the right,
`Inventory.recount` on `background-worker-1` did the mutating: it
called `Counter.setValue(54)` internally, and its whole `[73–78]`
window falls inside `multiplyByTwo`'s `[70–80]` window on the
other thread. That's the cross-thread overlap that produced the
wrong result. The bug is missing synchronisation, not arithmetic,
and the trace points at both the specific caller
(`Inventory.recount`) and the responsible thread without rerunning
anything.

This is why Arachna Trace wraps captured objects in identity envelopes
(`{"object_id": …, "class": …, "value": …}`) rather than emitting
raw primitives. A bare `42` and a bare `54` are just two different
integers; an envelope with the same `object_id` carrying different
values is unambiguous evidence that the same instance changed.

## Pinpointing the change in a deep object

Both the file destination and the processor run the same enrichment
step before traces are persisted: every captured object envelope
gets a `__meta__` block carrying a Merkle content hash. A parent's
hash is computed over its data with each child object replaced by
the child's own hash, so any mutation anywhere in a subtree
propagates up to the root.

For the simple `Counter` example above, this only adds noise. For a
nested object — say an Order with a Customer and a list of Items —
it lets you locate exactly which subtree changed without a deep
equality walk.

Suppose one item's price changes from 10 to 15 during a method
call. The enriched JSON values for that method's `AR` (entry) and
`AX` (exit) look like this:

**At entry (`AR`):**

```json
{
  "__meta__": { "id": 1, "class": "Order", "hash": "f4a2c91e..." },
  "id": 100,
  "customer": {
    "__meta__": { "id": 2, "class": "Customer", "hash": "9b3e1d77..." },
    "id": 5,
    "name": "Alice"
  },
  "items": [
    {
      "__meta__": { "id": 3, "class": "Item", "hash": "2e8a4f5b..." },
      "sku": "A",
      "price": 10
    },
    {
      "__meta__": { "id": 4, "class": "Item", "hash": "c1d6b923..." },
      "sku": "B",
      "price": 20
    }
  ]
}
```

**At exit (`AX`):**

```json
{
  "__meta__": { "id": 1, "class": "Order", "hash": "07b9e3a4..." },
  "id": 100,
  "customer": {
    "__meta__": { "id": 2, "class": "Customer", "hash": "9b3e1d77..." },
    "id": 5,
    "name": "Alice"
  },
  "items": [
    {
      "__meta__": { "id": 3, "class": "Item", "hash": "8d10c92f..." },
      "sku": "A",
      "price": 15
    },
    {
      "__meta__": { "id": 4, "class": "Item", "hash": "c1d6b923..." },
      "sku": "B",
      "price": 20
    }
  ]
}
```

Walking the tree by hash diff:

- **Order**: hash changed (`f4a2c91e... → 07b9e3a4...`) — recurse.
- **Customer**: hash unchanged (`9b3e1d77...`) — skip; nothing in
  this subtree mutated.
- **Items[0]**: hash changed (`2e8a4f5b... → 8d10c92f...`) —
  recurse. Comparing the two leaves shows `price` went from 10 to
  15.
- **Items[1]**: hash unchanged (`c1d6b923...`) — skip.

Three hash comparisons localised the change to one field of one
item, regardless of how deep the rest of the tree is. The same
construction is what backs ClickHouse predicates like
`WHERE root_hash = '...'` — "find every call whose payload had this
exact content" — for cross-call queries against the trace store.
