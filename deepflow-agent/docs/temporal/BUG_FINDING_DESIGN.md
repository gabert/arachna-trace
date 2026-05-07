# Bug-Finding Design — `own_hash` + UI turn from explorer to debugger

Notes from the 2026-05-06 first-setup session. After the demo container
came up clean and the agent → collector → Kafka → processor → ClickHouse
→ UI pipeline was running end-to-end, we tried to find the demo's
intentional silent-mutation bug (`normalizeIsbns` / `buildDisplayName`
mutating shared JPA entities). The data was correct; the UI surfaced it
poorly. Two coupled threads opened:

1. **Object-level mutation signal**: the agent's deep Merkle hash
   propagates through children, so a flat watch row can drift even when
   the watched object's own state didn't move. Need a per-object
   "own-state" hash that doesn't include child contents.
2. **UI as bug finder, not just trace explorer**: today the UI shows
   the data; it does not surface the anomalies in it. Pin/watch is
   sound machinery but expert-mode. First-time users see no signal.

This doc captures the agreed direction for both. Decisions only — no
implementation yet.

---

## Part A — Server-side `own_hash` *(DONE 2026-05-06)*

Part A is implemented end-to-end. `__meta__.own_hash` flows through
the rendered payload JSON; `payloads.own_hashes Array(String)` is
populated and bloom-filter indexed; the UI's `WatchPanel` reads
`__meta__.own_hash` directly (the prior FNV-1a + collapse helpers
were deleted). The deep-hash column was also dropped from the panel
in the same pass — own-state is the sole row signal.

The design notes below stay as the rationale; verify against current
code if you're refactoring this layer.

### Problem

The agent's `__meta__.hash` is a Merkle hash: a parent's hash includes
its children's hashes. That's the right shape for **tree drilling**
("an Order changed somewhere; walk down comparing hashes until I find
the leaf that moved"), but it's the wrong shape for **flat row
inspection** ("did *this* AuthorEntity's own state move at this call?").

In the demo, `Author #9`'s deep hash drifts both when the author's
`name` is mutated and when its books' ISBNs are mutated, because
`Author.books → BookEntity.isbn` participates in the Merkle hash. A row
reader has to mentally answer "was that drift in this object or in a
child?" — back to field-by-field reading.

### Current workaround

`WatchPanel.vue:96-119` (`ownStateKey` + `fnv1a`) computes an own-state
fingerprint client-side over a JSON-stringified collapse of the
envelope where nested envelopes become `{__ref__: id}`. Works for
live UI inspection. **Three things this does not give us:**

1. **SQL queryability.** `SELECT WHERE own_hash != prev_own_hash` over
   a request can't be written today; the signal lives only in the
   browser after JSON.parse. Trace-store queries by an LLM can't filter
   on it.
2. **Cross-tool consistency.** Agent uses MD5 over CBOR for deep hash;
   UI uses FNV-1a over JSON for own hash. Two algorithms, two
   pre-image formats, never cross-comparable.
3. **Stable contract.** Any UI-side normalization tweak (key sort,
   stringify quirks) silently changes own-hash values for the same
   content.

### Decision

Add `own_hash` as a sibling field to `__meta__.hash`, computed by the
processor's `RecordHashEnricher` in the same walk that computes deep
hash. Surface it as a top-level `payloads.own_hash` ClickHouse column.

### Algorithm

For each envelope `E` with class `C`, scalar fields `S = {k → v}`,
and child references `R = [child.__meta__.id for child ∈ E]` in wire
order:

```
own_hash(E) = MD5(canonical(C, S, R))
```

`canonical(...)` is the same canonicalization deep hash uses, except
each child is replaced by its **id only**, not its hash. Cycle markers
(`{cycle_ref: true, ref_id: N}`) collapse to the same ref shape as
full envelopes — so own-hash is invariant under cycle-entry direction.

Rationale: scalars + child identity but not child content. Captures
"this object's own fields changed" and "the set/order of children
changed", but not "a child's content changed". Same invariants the UI
already encodes in `ownStateKey`, just canonical and server-side.

### Schema changes

- **Wire format**: `__meta__` gains an `own_hash` field. Backward
  compatible — old payloads without it still render; UI falls back to
  client-side FNV-1a (or shows blank in the column).
- **ClickHouse `payloads`**: add `own_hash String` column with a
  bloom-filter skip-index (same shape as `root_hash`'s index).
- **Hasher API**: `Hasher.computeOwnHash(envelope)` alongside
  `extractRootHashFromHashed`. `RecordHashEnricher` writes both.
- **Query server**: pass `own_hash` through in the payload row JSON so
  the UI can read it from `__meta__.own_hash`.

No agent-side changes. The hot path stays unchanged.

### Effort

Half a day end-to-end, including the schema migration. Does not block
any other work.

### What this unlocks

- UI: WatchPanel reads `__meta__.own_hash` when present, falls back to
  FNV-1a otherwise. Mutations view (Part B) can use `own_hash` for the
  detection predicate without re-parsing JSON.
- ClickHouse / LLM: `WHERE own_hash` filters become trivial. "Find every
  call where Author #N's own state was modified" is a single-pass query
  against an indexed column.

---

## Part B — UI turn from trace explorer to bug finder

### Goal

A first-time user opening a trace should be able to spot the
interesting calls *without knowing the recipe*. Today the UI exposes
all the signal needed (object identity, deep + own hashes, AR/AX,
exceptions, retain) but does not surface anomalies — pin/watch is the
only path, and it requires you to know what to pin.

### Proposed UX changes (prioritized)

#### B1. Mutations view with inline diff — *the biggest unlock*

A new tab/section in the right pane (peer to WatchPanel) that lists,
for the loaded request, every `(call, object_id)` where the object's
`own_hash` at exit (`AX`) differs from its `own_hash` at entry (`AR`).
**Each row renders the actual field-level diff inline** — not "click
to drill", but "look, here's what moved":

```
buildDisplayName ⏵ AuthorEntity #9
  name:  "J.R.R. Tolkien"  →  "Tolkien, J.R.R."

normalizeIsbns ⏵ BookEntity #11
  isbn:  "978-0-618-00221-3"  →  "9780618002213"
```

Click any row → jumps to that call in the left pane (FrameCard
expanded, AR + AX visible) for full context. The diff is the
*discovery*; the click is for *context*.

**Architecture: hash detects, client diffs.** The server emits
`own_hash` per envelope (Part A — done). The UI uses hash transitions
as the cheap detection signal, then computes the actual field-level
diff on demand only for the rows it renders. No diff materialization
on the server, no extra schema, no batch-time cost for diffs the user
never inspects. Pure derivation from the two `payload_json` blobs
already in memory.

**Localization is essentially free thanks to nested own_hashes.** To
find the exact mutated field inside an envelope, the diff walker uses
own_hash at each envelope boundary as a "subtree changed?" oracle:

```
diff(envelope_AR, envelope_AX):
  if envelope_AR.__meta__.own_hash == envelope_AX.__meta__.own_hash:
    return []                      # no own-state change here, skip
  for each field f shared by both:
    if both envelopes' f are scalars and they differ:
      record (f, AR.f, AX.f)
    else if f is a child envelope at both sides:
      if AR.f.__meta__.id != AX.f.__meta__.id:
        record (f, "→ id swap from #X to #Y")
      else:
        recurse diff(AR.f, AX.f)   # only if subtree own_hash differs
    else if f is a list:
      element-wise pair, apply same rules
  record any added / removed fields
```

For an Author with 10 books where one ISBN changed: ~3 hash
comparisons total (Author own_hash same → never recursed; books list
walked; one book's own_hash differs → drill into its scalars). Not 10
deep field comparisons. The own_hash chain *is* the directory
structure of "where in this object did something move."

**Predicate that drives the row list:** `own_hash(AX) !=
own_hash(AR)` for the same `(call_id, object_id)`. Detect on the
client by scanning loaded payloads of the current request; later, a
SQL variant (`SELECT WHERE arrayMap(...) != arrayMap(...)` over
`payloads.own_hashes`) lets the server pre-aggregate counts for
session-list badges (B3).

**Filters / controls:** by class, by depth ("only objects matched at
the root of args, not nested"), by call signature substring. Surface
"N mutations in this request" as a header summary above the list.

**Prerequisite for the AR-vs-AX detection:** the agent must emit AX
records. The default `emit_tags` (`AgentConfig.DEFAULT_EMIT_TAGS`)
does **not** include `AX`. The first-setup demo agent config
(`release/configs/demo-agent.cfg`) needs `emit_tags=...,AX,...` added
or B1's primary predicate has nothing to compare against. (Without
AX, B1 falls back to "compare adjacent appearances of the same id
across calls" — still useful but weaker than within-call mutation
detection.)

This is the feature that flips the tool's value proposition from
"investigate a known bug" to "find a bug you didn't know you had."

#### B2. Exception ring

`FrameCard` for any call with `return_type = EXCEPTION` gets a red
border. Tooltipped with the exception type. Sessions list and request
selector show counts.

Cheap. The data is already on `payloads` (and aggregated in
`requests_view.has_exception`). Just rendering.

#### B3. Sessions list decoration

The right pane on `SessionsView` (newly added 2026-05-06) lists
requests but shows raw counts only. Add per-request decorations from
`requests_view`:

- exception badge if `has_exception`
- mutation badge with count from a new server-side aggregate
- duration in ms
- `retain` lock icon if true

Plus a session-level rollup at the top: "23 requests, 3 with
exceptions, 14 with mutations, 2 retained."

Lets users pick a session worth opening before clicking through.

#### B4. Object timeline upgrade

`ObjectHistoryView` already exists at `/objects/:objectId/history`.
Today it just lists the appearances. Reshape it as a vertical
timeline keyed off own_hash transitions:

```
[ session A, request 5 ] AuthorEntity #9
  ┌  AR  saveAuthor                  own=abc   deep=xyz
  ├  AX  saveAuthor          ▲ own  own=def   deep=xyz'   name: "Tolkien" → "J.R.R. Tolkien"
  ├  AR  buildDisplayName            own=def   deep=xyz''
  └  AX  buildDisplayName    ▲ own  own=ghi   deep=xyz''' name: "J.R.R. Tolkien" → "Tolkien, J.R.R."
```

The transitions are the spine. Clicking any row jumps to that call.
Diff hints inline. Closes the loop "this object got mutated → where
exactly → what changed."

#### B5. Hash chip on envelope rows in JsonTree

A 6-char own-hash chip on every envelope-rendering row (`AuthorEntity
#9 · 3 fields` line) so a reader can spot drift across nested
expanded views without opening the WatchPanel. Cheap rendering, big
recognition payoff once you've trained your eye to spot drift.

#### B6. Always-show pin button

`JsonTree.vue:223-236` makes `⊕ watch` invisible until row hover
(`opacity: 0` default). For first-time users, this hides the only
mechanism the UI offers for inspection. Make it always visible on
rows that can be pinned. Loses ~6px; gains "users actually find it."

#### B7. WatchPanel signal preference *(DONE 2026-05-06)*

Done as part of the same session that landed Part A: WatchPanel now
reads `__meta__.own_hash` for every instance row, the deep-hash
column was removed entirely (it added clutter without value in a
flat row context), and bands flip on own-state transitions. The
deep / Merkle perspective is still in the data
(`__meta__.hash`) for anyone drilling via the JsonTree on the left
pane, just not surfaced in the watch column.

### Out of scope (this design round)

- Cross-session / cross-run comparison (regression detection). Pillar
  3 of the product but its own design problem.
- "AI-driven anomaly detection" — let the human/LLM ask SQL questions
  against the trace store; the UI's job is to make the human side
  fast.

(Inline diff rendering, originally listed here as deferred, has been
folded into B1 — own_hash makes the localization cheap enough that
shipping B1 without it would leave the most actionable signal one
step away from the user.)

---

## Part C — Field provenance *(deferred — design only)*

A use case raised during the B1 design discussion: a reader wants to
know **where each field of an object came from** — which earlier call
populated `Author.name`, which API response carried the
`discountPercent`, which DTO mapper set `address.city`. Different from
B1 (what changed *on* the object across one call) and B4 (the
object's own_hash transition timeline). This is about the *inbound
dataflow* into each field.

### Why not just instrument getters/setters

The precise answer is "track every getter and setter and the trace
shows you exactly which method read which field when." We are not
going to do that. A typical Spring Boot request runs thousands of
getter/setter calls; instrumenting them would bury the business-logic
calls under property-access noise and inflate trace size by an order
of magnitude. The default matcher policy already excludes `get*` /
`set*` by convention, and that's the right default.

So the question becomes: can we give a *useful hint* of provenance
without that instrumentation, accepting that the answer is heuristic,
not authoritative?

### Inference from data already in the trace

Three sources of signal are already present in current traces, no new
agent surface needed:

1. **Argument-flow.** When a call's AX shows `author.name = "Tolkien"`,
   scan that call's AR scalars for `"Tolkien"`. A match means an
   argument is the candidate source for that field at that call.
2. **Chained return values.** That argument was probably itself the
   return value of an earlier call (`fetchUser`, `parseDto`, …). Walk
   backwards: find the most recent call whose RE contained the value.
3. **Object-id linkage.** When the field is a child envelope, its
   `__meta__.id` first appeared as a return value (or argument)
   somewhere — that's a confident "constructed here" signal because
   ids are unambiguous, unlike string values.

For unique-ish values (UUIDs, names, ISBNs, generated IDs) this draws
a high-confidence "field came from here" arrow. Where it breaks down:
constants, booleans, common strings — there the honest UX is to show
*candidate sources* with a confidence flag rather than a single arrow.

### Where the computation lives — split by scope

- **Single-request scope: client-side.** The JSON is already hydrated;
  the diff walker B1 needs is already on the client. No new endpoint,
  no extra cost. Within a single request the source frame is usually
  visible in the timeline anyway.
- **Cross-request / cross-session scope: server-side.** The client
  can't answer "where did Author #9's `name` first come from across
  this whole session" without refetching everything. ClickHouse can.
  This is also where provenance gets *interesting* — within one
  request the construction is usually local; across a session, the
  chain runs through HTTP boundaries, repository layers, mappers.

The cross-request version naturally takes the shape of an API
endpoint:

```
POST /api/provenance
  { object_id, field_path, scope: "request" | "session" }
  → { candidates: [{call_id, source_kind, value, confidence}, ...] }
```

### Why the API-server version matters beyond the UI

The endpoint shape above is exactly what an LLM agent calling into
the trace store would want. "Trace provenance of `discountPercent` on
Order #4" today requires the LLM to pull raw payloads and reason over
them; with a real endpoint it's one tool call. Lines up with the
"trace store as queryable substrate for LLM debugging" pillar of the
product positioning — UI is one consumer, agents are another.

### Indexing — the open question

- **Object-id linkage is already fast.** `payloads.object_ids` carries
  a bloom-filter skip-index; "envelope #N first appeared in call X"
  is one indexed lookup.
- **Scalar-value matching is full-scan today.** `payload_json LIKE
  '%Tolkien%'` walks every payload row in scope. Fine for a
  single-request preview, painful for session-wide queries on large
  traces.

If cross-request provenance becomes load-bearing, the server-side
version eventually wants a tokenized-values index — likely a `String`
array column populated at enrich-time alongside `object_ids`, or a
secondary token-indexed table. Separate schema design; not a
prerequisite for the client-side single-request version, which can
ship anytime after B1.

### Effort & sequencing

- Client-side single-request version: ~a day, mostly UI. Reuses B1's
  diff walker and the loaded JSON. Ships as an "origin" hint inline
  in Mutations rows or as a hover-card on JsonTree fields.
- Server-side cross-scope endpoint: a few days for the unindexed
  version (full-scan over request/session), substantially more if a
  tokenized-values column gets added.

Deferred. Not in the B1–B6 ladder. Revisit after B1 ships and the
diff walker has stabilized — at that point the client-side
single-request version is mostly free, and the cross-request question
becomes "do we need indexing?" rather than greenfield design.

---

## Sequencing

Status as of 2026-05-06: items 1 and 8 below shipped together with
the release pipeline / first-setup work. Pick up at item 2 (B1).

1. ~~**A — `own_hash` end-to-end.**~~ DONE — `__meta__.own_hash`,
   `payloads.own_hashes`, UI consumption, all live.
2. **B1 — Mutations view with inline diff.** Highest leverage. The
   feature that flips the tool from "investigate a known bug" to
   "find one you didn't know you had". Pre-req: enable `AX` in the
   demo agent's `emit_tags` so AR-vs-AX comparison has data.
3. **B2 — Exception ring.** Cheap. Pairs naturally with B1 in the
   same "anomalies surfaced" UX shift.
4. **B3 — Sessions list decoration.** Builds on the just-shipped
   sessions two-pane preview. Per-request mutation/exception/
   duration/retain badges + a session-level rollup.
5. **B5 — Hash chip on envelopes.** Small, ~30-line UI change.
   Optional polish.
6. **B6 — Always-show pin.** Smaller still, ~5 lines of CSS.
7. **B4 — Object timeline upgrade.** Larger UI piece, reuses B1's
   diff renderer. Last in this batch.
8. ~~**B7 — WatchPanel default signal swap.**~~ DONE — done in the
   same session that shipped A; deep-hash column removed entirely.

---

## Appendix — the demo bug, mapped to the proposed UX

The demo's `runDemoScenario` call exhibits two intentional silent
mutations. With B1 in place:

| User action | What they see |
|---|---|
| Open the session in `SessionsView` preview | Right pane shows: 1 request, **"4 mutations" badge** (1 author + 3 books), no exceptions |
| Click "Open session" | Lands in `SessionDetailView` |
| Open the **Mutations** tab in the right pane | Four rows, with **the diff inline on each row** — no further click needed: |
| | `buildDisplayName ⏵ AuthorEntity #9` <br> &nbsp;&nbsp;`name: "J.R.R. Tolkien" → "Tolkien, J.R.R."` |
| | `normalizeIsbns ⏵ BookEntity #11` <br> &nbsp;&nbsp;`isbn: "978-0-618-00221-3" → "9780618002213"` |
| | (same for books #12, #13) |
| Click any row | Jumps to that frame for full context — AR and AX expanded, the divergent field already highlighted |

Compare to today: same data, but the user has to know to pin
`AuthorEntity #9` in `WatchPanel`, watch own_hash drift, click the
transition row, and then mentally diff AR vs AX. The proposed UX
makes the bug **discoverable** (mutations appear in the panel
without being asked for) and **legible** (the diff is the row, not
something to compute by reading).

---

## What this is not

- Not a re-architecture. Agent stays unchanged. Wire format gains one
  optional field. ClickHouse gains one column. UI gains views, not
  pages-of-state.
- Not a "make the UI smart" play. Detection predicates are simple
  hash comparisons; smartness comes from surfacing the signal, not
  inferring intent.
- Not opinionated about *which* anomaly classes matter. Mutations and
  exceptions are the two with first-class agent signal today; if a
  third class earns first-class signal later (e.g. "method-time
  anomaly" via APM-ish data — which we've explicitly *not* taken on),
  it slots into the same shelf.
