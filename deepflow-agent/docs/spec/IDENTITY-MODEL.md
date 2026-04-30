# Identity Model — Cross-Language Design

**Normative + design discussion.**

This is the hardest part of porting DeepFlow to a new language. The
binding contract is small — see SPEC.md §7 — but satisfying it
naturally in a language that lacks Java's primitives requires real
design choices. This document spells out the choices and the
trade-offs so a porter can pick consciously.

## 1. The contract (recap, normative)

1. Every captured **composite value** (objects/structs, maps,
   collections, arrays — anything with instance identity) MUST be
   wrapped in an envelope carrying `OBJECT_ID` (a positive int64)
   and `CLASS_NAME` (UTF-8). Primitives (numbers, booleans, strings,
   null) MAY be emitted as bare CBOR — they have no instance
   identity to track. See
   [CBOR-ENVELOPE.md §5](CBOR-ENVELOPE.md) for the bare-primitive
   rules.
2. Within one agent-run, the **same logical instance** MUST get the
   **same `OBJECT_ID`** every time it appears, for as long as the
   agent considers it alive.
3. **Different live instances** MUST get **different `OBJECT_ID`s**.
4. IDs MUST NOT be reused within an agent-run, even after an
   instance becomes unreachable.
5. Across agent-runs, IDs are unrelated. Cross-run identity is by
   content hash (see [HASHING.md](HASHING.md)), not by ID.

That is the entire contract a producer MUST satisfy. The rest of
this document is about *how* — and what compromises each strategy
forces.

## 2. The reference (Java) implementation

The reference satisfies the contract via:

- A monotonically increasing `AtomicLong` counter (no reuse).
- A `ConcurrentHashMap<IdentityWeakRef, Long>` mapping each live
  instance to its assigned ID.
- A custom `IdentityWeakRef` wrapper whose `equals()` uses raw
  reference equality (`==`) and whose `hashCode()` is the cached
  `System.identityHashCode`.
- A `ReferenceQueue` drained on every lookup to evict entries whose
  referent has been garbage-collected.

This is exactly the right shape because the JVM gives all five
ingredients: weak references, identity hash, identity equality, GC
notification, and a thread-safe map.

```
counter:    1, 2, 3, ...   (monotonic, never reused)
map:        IdentityWeakRef(obj) → id
GC sweep:   when obj becomes unreachable, the WeakRef enters
            ReferenceQueue; lookup drains the queue and removes
            the entry. The id is gone but never reissued.
```

Lookup is amortized O(1). Memory cost is proportional to live
tracked objects only.

## 3. The cross-language landscape

| Language | Weak refs? | Identity equality? | GC notification? | Verdict |
|---|---|---|---|---|
| Java   | yes (`WeakReference` + `ReferenceQueue`) | `==` is by reference | yes (queue) | Reference; trivially conformant. |
| Kotlin | yes (Java interop) | `===` | yes | Same as Java. |
| Python | yes (`weakref`) | `is`, `id()` | yes (`weakref` callback / `gc` module) | Conformant; see §4.1. |
| C#     | yes (`WeakReference<T>`) | `Object.ReferenceEquals` | yes (finalizers / `WeakReference`) | Conformant; see §4.2. |
| JavaScript / TypeScript | yes (`WeakRef`, `WeakMap`, `FinalizationRegistry` since ES2021) | `===` for objects | yes (FinalizationRegistry) | Conformant; modern runtimes only. |
| Swift  | yes (`weak var`, `WeakObjectIdentifier`) | `===` | via deinit | Conformant. |
| Go     | **no** weak references; runtime has no portable identity | `==` for pointers | only via finalizers (`runtime.SetFinalizer`) | **Constrained — see §5.** |
| Rust   | no GC; `Weak<T>` for `Rc`/`Arc` only | pointer equality | drop semantics, not GC | **Constrained — see §6.** |
| C / C++ | no GC | pointer equality | manual | Manual lifetime management; see §7. |

Java, Kotlin, Python, C#, JavaScript (modern), and Swift can
implement the contract via the same shape as the reference.
**Go and Rust need a different strategy** because they lack the
underlying mechanism.

## 4. Direct ports (weakref-capable languages)

### 4.1 Python

```python
import weakref, itertools, threading

_counter = itertools.count(1)
_lock    = threading.Lock()
_id_for  = weakref.WeakValueDictionary()  # id -> obj  (NB: see below)
_obj_for = {}                              # id(obj) -> assigned long

def id_of(obj):
    raw = id(obj)
    with _lock:
        existing = _obj_for.get(raw)
        if existing is not None:
            return existing
        new_id = next(_counter)
        _obj_for[raw] = new_id
        weakref.finalize(obj, _obj_for.pop, raw, None)
        return new_id
```

Notes:

- `id()` returns the CPython object's address, which is stable for the
  object's lifetime and unique among live objects. (Different from
  Java's `identityHashCode`, which can collide.)
- Use `weakref.finalize` to evict the `_obj_for` entry on GC. The
  counter never reuses values — same guarantee as Java.
- `weakref` does not support all built-in types (e.g. `int`, `str`,
  `tuple`). For those, the producer MAY skip envelope wrapping and
  emit the bare CBOR primitive (CBOR-ENVELOPE.md §5).

### 4.2 C#

```csharp
private static long _counter;
private static readonly ConditionalWeakTable<object, IdBox> Map = new();

public static long IdOf(object o)
{
    return Map.GetValue(o, _ => new IdBox(Interlocked.Increment(ref _counter))).Value;
}
private sealed class IdBox { public readonly long Value; public IdBox(long v) { Value = v; } }
```

`ConditionalWeakTable` is the GC-aware identity-keyed map. Entries
disappear automatically when the key becomes unreachable.

### 4.3 JavaScript (ES2021+)

```js
const counter = (() => { let n = 0n; return () => ++n; })();
const map = new WeakMap();   // obj -> bigint id

export function idOf(obj) {
  let id = map.get(obj);
  if (id === undefined) { id = counter(); map.set(obj, id); }
  return id;
}
```

`WeakMap` keys are GC-aware. Use `BigInt` to keep the contract that
IDs fit in int64 even past 2³².

## 5. Go — the hard case

Go has no weak references, no runtime identity hash, and no GC
notification beyond finalizers. The textbook approach (a map keyed
by pointer) **leaks** unless you can detect GC.

Three plausible strategies, in order from "closest to the contract"
to "give up some property":

### 5.1 Strategy A — Finalizer-driven map

```go
var (
    counter atomic.Int64
    mu      sync.Mutex
    ids     = map[unsafe.Pointer]int64{}
)

func IdOf(v interface{}) int64 {
    ptr := unpackInterfacePointer(v)  // see runtime details below
    mu.Lock()
    if id, ok := ids[ptr]; ok { mu.Unlock(); return id }
    id := counter.Add(1)
    ids[ptr] = id
    mu.Unlock()
    runtime.SetFinalizer(v, func(_ interface{}) {
        mu.Lock(); delete(ids, ptr); mu.Unlock()
    })
    return id
}
```

Conforms to the contract. **Caveats:**

- `runtime.SetFinalizer` has runtime cost and can extend object
  lifetime by one GC cycle. For a tracing tool this is usually
  acceptable, but it is observable.
- Pointer extraction from `interface{}` requires care; not all
  values are pointers (Go interfaces can hold non-pointer types).
  The producer SHOULD only assign IDs to pointer-bearing values
  (structs accessed via pointer, slices, maps, channels).
- For value types (int, struct copies), the producer SHOULD skip
  envelope wrapping and emit bare CBOR primitives, like Python's
  treatment of `int`.

This is the recommended default for a Go agent.

### 5.2 Strategy B — Weakened identity (per-call only)

Drop guarantee §4 (no reuse). Maintain identity only within the
current capture (one record), not across the whole agent-run.
Trivial: a per-record `IdentityHashMap<*T, int64>` cleared after the
record is emitted.

Loses cross-call mutation tracking by ID. Mutation can still be
detected by content hash. Cross-call object tracking degrades to
"objects that look identical at the time."

A producer using strategy B MUST document this in its `agent_version`
string (e.g. `agent_version="deepflow-agent/0.1-go-weak-identity"`)
so consumers know not to trust cross-call ID equality from this
agent's output.

### 5.3 Strategy C — Content-only identity

Drop the ID-by-instance contract entirely. Every envelope's
`OBJECT_ID` is set to the **content hash** truncated to int64. Two
envelopes with the same content get the same ID; mutating an instance
changes its ID.

This is a coherent, simpler model. It loses the ability to track an
instance across mutations (you can't say "this is still the same
order, just with a different total"). For some use cases that
distinction doesn't matter.

A producer using strategy C MUST flag this in its `agent_version`
and SHOULD use `OBJECT_ID = lower 63 bits of content hash` so the
result remains a positive int64.

### 5.4 Recommendation for Go

Default to Strategy A (finalizer-driven). Document the lifetime
extension. If the finalizer cost is unacceptable for the workload,
fall back to Strategy B for ergonomics or Strategy C for simplicity,
and flag the choice in `agent_version`.

## 6. Rust

Rust has no GC. Lifetime is explicit. `Rc<T>` / `Arc<T>` provide
`Weak<T>` for *those specific reference types*, but most data passes
through references (`&T`) whose lifetime is bound by the borrow
checker, not by GC.

A Rust agent's natural model is:

- Identity at the `Arc<T>` / `Rc<T>` level — trace data wrapped in
  reference-counted handles. The ID survives as long as any strong
  reference exists; eviction is by `Weak<T>` upgrade-fail.
- For non-`Arc`/`Rc` data captured by reference, fall back to
  Strategy B (per-call identity).

A Rust agent will probably trace a narrower scope of data than a
Java agent, by language nature. That is acceptable; the spec does
not require any particular instrumentation surface.

## 7. C / C++

Pointer equality is straightforward; lifetime is fully manual. A
C/C++ agent MUST be told (by configuration, by integration with the
host application's allocator, or by per-type registration) when an
object becomes unreachable, so the ID-eviction sweep can run.

This is enough rope to hang an implementor; in practice a C/C++
agent will likely be limited to data structures the host application
explicitly opts in to.

## 8. Test corpus for identity (recommended, not normative)

A conformance test for §1 should:

1. Capture the same instance twice in one record. Assert both
   envelopes have the same `OBJECT_ID`.
2. Capture two distinct same-content instances in one record.
   Assert different `OBJECT_ID`s.
3. Capture an instance, drop the strong ref, force GC (where
   applicable), capture a fresh instance whose memory address may
   coincide. Assert the fresh instance gets a new ID, not the dead
   one's.

The reference Java agent has analogous tests in
`EnvelopeSerializerTest.ObjectIdRegistryTests`.

## 9. Open question — cross-run identity (Informative)

Today's spec says cross-run identity is by content hash, not by ID.
That works for *content* but not for *trajectory* — you cannot say
"this is the same `Order` instance the previous run was working on,
just with a mutation," only "this is content-equivalent to that one."

A future major version MAY introduce an optional **stable instance
ID** layer (e.g. derived from a producer-supplied primary-key field)
to recover cross-run identity for selected types. This is research,
not part of v1.

## 10. Summary

If the language has weak references and identity equality, port the
Java implementation directly. If it doesn't (Go, Rust, C/C++), pick
one of the three documented strategies and tell consumers which one
via `agent_version`. The spec accepts any of them as conformant; the
trade-off is which downstream queries (mutation-by-ID, cross-call
tracking) work for that agent's data.
