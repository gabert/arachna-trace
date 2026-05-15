# Roadmap

The backlog of open work. References stable IDs from
`KNOWN_BUGS.md` without restating; describes feature ideas with
motivation, approach, and edge cases — enough to pick up cold.

For *fixed* bugs and accepted degradations, see `KNOWN_BUGS.md`.
For the bug-finding workflow design (own_hash, mutations,
provenance, value search), see `BUG_FINDING_DESIGN.md`.

---

## Open bugs and leaks

By ID — see `KNOWN_BUGS.md` for the full description.

- **B-04** — version dispatch in wire parser. Dismissed: agent +
  processor ship together; revisit only if external consumers
  appear.
- **B-05** — JVM crash mid-method leaves orphan UUID on
  `CALL_STACK`. Self-heals at top-of-request; doable via a
  periodic stack-depth alarm but no clean ByteBuddy try/finally
  fix.
- **B-06** — failed entry on request's originating root → spurious
  root in `requests`. Hard; documented degradation.
- **L-04** — accumulation in long-lived thread `CALL_STACK` after
  B-05. Tied to B-05.
- **D-02** — `sessions.first_seen` wrong after processor restart
  (RMT picks latest insert). Needs `AggregatingMergeTree` switch.
  Cheap once the schema change is accepted.
- **D-03** — `ReplacingMergeTree(inserted_at)` uses 1-second
  resolution; matters only multi-processor. Switch to
  `DateTime64(3)`.
- **D-04** — `ALTER TABLE UPDATE retain` async race with TTL.
- **D-05** — `payload_size` is JSON byte count, misleading name.
- **D-10** — **HIGH SEVERITY**. Truncation marker drops the
  envelope wrapper, breaking identity tracking, mutation
  detection, and object-tree walk at the truncated node.
  Directly collides with Arachna Trace's core feature claims. Mitigation
  today is `max_value_size=0` (agent default), but anyone setting
  a non-zero cap silently opts out of identity / mutation
  semantics for clipped values. Fix in three flavours, ordered by
  effort × parity-with-current — see KNOWN_BUGS for the analysis.
- **U-02** — Watch-row click highlights the JsonTree node but the
  left pane doesn't scroll it into view. Visual-only.

## Bug-finding UX backlog

Items beyond what's already shipped (see `BUG_FINDING_DESIGN.md`).
All described as "B-N" because they came out of the same design
round; numbering is for cross-reference convenience, not
priority.

- **B2 — Exception ring on FrameCard for `return_type=EXCEPTION`.**
  Cheap. The data is already on `payloads` (and aggregated in
  `requests_view.has_exception`). Just rendering: red border on
  the FrameCard, tooltipped with the exception type. Sessions
  list and request selector show counts.
- **B3 — Sessions list per-request decoration.** Add per-request
  decorations from `requests_view`: exception badge if
  `has_exception`, mutation badge with count from a new
  server-side aggregate, duration in ms, `retain` lock icon if
  true. Plus a session-level rollup at the top: *"23 requests,
  3 with exceptions, 14 with mutations, 2 retained."* Lets users
  pick a session worth opening before clicking through.
- **B4 — ObjectHistoryView upgrade.** Today the view lists
  appearances. Reshape as a vertical timeline keyed off `own_hash`
  transitions, reusing the diff renderer:

  ```
  [ session A, request 5 ] AuthorEntity #9
    ┌  AR  saveAuthor            own=abc   deep=xyz
    ├  AX  saveAuthor   ▲ own    own=def   deep=xyz'  name: "Tolkien" → "J.R.R. Tolkien"
    ├  AR  buildDisplayName       own=def   deep=xyz''
    └  AX  buildDisplayName ▲ own own=ghi   deep=xyz''' name: "J.R.R. Tolkien" → "Tolkien, J.R.R."
  ```

  The transitions are the spine. Clicking any row jumps to that
  call. Closes the loop "this object got mutated → where exactly →
  what changed."
- **B5 — Hash chip on envelope rows in JsonTree.** A 6-char
  own-hash chip on every envelope-rendering row (`AuthorEntity #9
  · 3 fields` line) so a reader can spot drift across nested
  expanded views without opening the WatchPanel. Cheap rendering,
  big recognition payoff once you've trained your eye to spot
  drift.
- **B6 — Always-show pin button.** `JsonTree.vue` makes `⊕ watch`
  invisible until row hover (`opacity: 0` default). For
  first-time users, this hides the only mechanism the UI offers
  for inspection. Make it always visible on rows that can be
  pinned. Loses ~6px; gains "users actually find it."
- **Tag-vocabulary help affordance in the instance inspector.**
  First-time users hit a hump on the two-letter wire tags
  (`TI` / `AR` / `AX` / `RE` / `TS` / `TE` / `SQ` / `RT` / `CI`
  / `PI` / `SI` / `RI` / `TN` / `CL`). The codes are the right
  primary label — they're shorter than the English they
  replace, unify with the wire-format spec, and stay legible
  once learned. But they need a *first-five-minutes*
  affordance. Add a small `?` icon at the **component
  level** of `CallInspectionCard.vue` (one icon, not per
  section header) that pops a tooltip / inline panel mapping
  each code to its full name + one-line meaning. Single source
  of truth — no per-chip duplication.

## Server / agent follow-ups

- **Agent shutdown emit** — write an "ended" record on graceful
  shutdown so `agent_runs.ended_at` / `completed_clean` get
  populated. With agent-run identity now on the transport layer,
  this needs reformulation — probably an extra `X-Arachna-Trace-*`
  POST on shutdown or a sidecar update for the file destination.
- **`last_seen` updates on sessions** — emit per call or batched.
- **Retain wiring** — agent config flag + sink stamping for
  "promote this debugging session for long retention." Unblocks
  the user-facing payoff of the retention escape (`retain` column
  already exists on every table).
- **Verb layer / agent query DSL** — the next greenfield design
  problem; the schema is now stable enough to design on.
- **Re-entrancy / recursion guard in the advice.** ThreadLocal
  boolean to skip re-entrant `recordEntry/recordExit` if CBOR
  serialization itself ever triggers an instrumented method on
  the same thread. `toString` / `equals` / `hashCode` are already
  excluded by matcher; this is the belt-and-suspenders fix that
  closes the residual recursion class. Cheap.
- **Source-code attachment.** Combine `CL` (caller line) with the
  application's source jars to render the exact executing line
  inline next to each call in the UI. Aligns with the
  "microscope, not detective" UX of trace browsing.
- **Cross-thread mutation correlation / race detection.** Compare
  the same `object_id` across threads inside one session; flag
  interleaved writes that aren't guarded by a request boundary.
  Distinct from FR-2 (content trace by `(class, own_hash)`),
  which is identity-content driven, not concurrency driven.
- **Align the size-limit defaults across the pipeline.** Today's
  defaults are silently misaligned:

  | Layer | Default | Issue |
  |---|---|---|
  | Agent `max_value_size` | `0` (unlimited) | A single captured value can exceed every downstream cap |
  | Agent `http_flush_threshold` | 64 KiB | OK |
  | Collector `max_content_length` | **10 MiB** | Far above Kafka |
  | Kafka producer `max.request.size` | not set → 1 MiB (Kafka default) | **mismatch** |
  | Kafka broker `max.message.bytes` | 1 MiB (Kafka default) | OK |

  POSTs between 1–10 MiB are accepted by the collector and then
  fail to forward to Kafka with a producer error in stderr —
  silent batch loss from the user's perspective. The contract
  (every downstream layer's cap must be ≥ the previous layer's
  output) is documented at
  [../reference/deployment-modes.md#size-limits-across-the-pipeline--alignment-contract](../reference/deployment-modes.md#size-limits-across-the-pipeline--alignment-contract).

  Proposed fix (all four together):
  - `AgentConfig.maxValueSize` default `0` → `32768` (32 KiB,
    matching the existing recommended-starting-point in
    `arachna-agent.cfg`'s comment). Captures of >32 KiB get the
    truncation marker instead of producing oversized payloads.
  - `ServerConfig.DEFAULT_MAX_CONTENT_LENGTH` `10 MiB` → `1 MiB`
    (matches Kafka's default per-message cap).
  - `KafkaRecordForwarder` constructor: explicitly set
    `MAX_REQUEST_SIZE_CONFIG = config.getMaxContentLength()` so
    the producer's per-request cap inherently tracks the
    collector's body cap.
  - `ServerConfigTest.defaultValues` assertion `10 MiB` → `1 MiB`.

  Result: a fresh deployment with zero tuning has a coherent
  pipeline — agent caps at 32 KiB per value, ~32× headroom up
  to the 1 MiB collector / Kafka / broker ceiling. Operators
  who need larger payloads raise all four together (the
  alignment-contract section in deployment-modes.md is the
  recipe).

- **Java 11 source compatibility for runtime modules.**
  Audience: enterprise JVM shops still on Java 11 LTS — a real
  population that can't easily upgrade and would benefit from
  this tool. The runtime modules (loaded into the user's JVM —
  `arachna-trace-shared/`, `arachna-trace-agents/jvm/core/`,
  `arachna-trace-jvm-extensions/`) currently require Java 17
  because they use records, sealed interfaces, and pattern
  `instanceof`.

  Scope: re-target only the modules that load into the user's
  JVM. `arachna-trace-infra/` stays on Java 17 — it runs in our
  containers, not the user's.

  Mechanical conversion:
  - Records (~15–20 of them: `AgentRun`, `MethodStartRecord`,
    `MethodEndRecord`, `ParsedCall`, etc.) → final classes with
    constructor + getters + `equals`/`hashCode`/`toString`.
  - Sealed interfaces (`TraceRecord` and similar) → regular
    interfaces. Loses the compiler's exhaustiveness check;
    runtime behaviour identical.
  - Pattern `instanceof` → cast + check.
  - Switch expressions with pattern matching → traditional switch.
  - Maven `<release>11</release>` on runtime modules only;
    tooling (build, infra) can stay on 17.
  - CI matrix (Java 11 + Java 17) to prevent silent regression
    after the conversion.

  Cost: 2–4 days of sustained mechanical surgery plus the CI
  matrix. Not a refactor — no design changes, just lines of
  boilerplate replacing syntactic sugar.

  **Java 8 is explicitly out of scope** — the audience is
  smaller, the language gap is wider (no `var`, no records, no
  pattern `instanceof`, no `Map.of`, etc.), and the security
  posture of modern Java has effectively retired it for new
  tooling.

- **Runtime attach to a running JVM.** Audience: production
  debuggers who can't restart the JVM — *"customer reports a bug
  at 14:00, you want runtime evidence, you can't bounce the
  process."* Today the agent only supports startup attach
  (`-javaagent:`); the JAR manifest declares `Premain-Class`
  but no `Agent-Class`, so `VirtualMachine.attach(pid).loadAgent(jar)`
  fails. ByteBuddy itself fully supports runtime attach (the
  capability is well-trodden — Datadog, New Relic, Elastic APM
  all do this); the gap is purely that we haven't wired it up.

  Scope:
  - Manifest: add
    `Agent-Class: com.github.gabert.arachna.trace.agent.ArachnaTraceAgent`
    next to the existing `Premain-Class`.
  - Code: add `agentmain(String args, Instrumentation inst)` to
    `ArachnaTraceAgent` mirroring the `premain` setup (config
    load, `RecorderManager.create(...)`, shutdown hook, ByteBuddy
    `AgentBuilder` install).
  - ByteBuddy: install with `RedefinitionStrategy.RETRANSFORMATION`
    so already-loaded classes get retransformed on attach. The
    existing `@Advice`-based instrumentation fits within the
    JVM's retransformation constraints (no new methods, no new
    fields), so this is enabling-mode, not a redesign.
  - Integration test: spin up a JVM, let it warm up, attach the
    agent, exercise previously-loaded code, verify the resulting
    traces. None of the existing integration tests cover this
    path; runtime-attach has its own failure modes (classloader
    visibility, retransform conflicts).

  Operational notes that should land in
  [`reference/agent-config.md`](../reference/agent-config.md) when this ships:
  - The agent runs **inside the target JVM** after attach — same
    process, same network stack, same filesystem. The attacher
    is just a one-shot "load this JAR" delivery mechanism.
    Network reach to the collector is the target's responsibility,
    not the attacher's.
  - Agent stderr lands in the target JVM's stderr, not the
    attacher's terminal — easy to miss HTTP destination errors
    if you're not watching the target's logs.
  - "Remote attach" in practice means SSH to target → run
    `jcmd <pid> JVMTI.agent_load /path/to/agent.jar config=...`
    locally. Attach is process-local; the JVM does not expose a
    network attach endpoint.
  - Pairs nicely with `destination=file` for one-off production
    debugging — the agent writes `.dft` files locally, you
    `scp` them off afterwards, and you bypass the
    network-reachability-to-collector question entirely.

  Cost: ~1 day for manifest + `agentmain` + AgentBuilder
  retransform-mode wiring; ~1 day for a robust integration test
  that catches the corner cases (classloader visibility, classes
  loaded by application classloader after attach, classes that
  fail retransformation).

- **Codec support for `Optional` unwrapping.** `Optional<T>` is a
  synchronous presence wrapper, fully resolved by the time the
  agent serializes it. The codec today walks it as a generic
  POJO, surfacing its internal `value` field and `isPresent()`
  getter — so the contained DTO ends up buried one level under
  `value` and the rendered output reads cryptically (envelope id
  + a boolean). Replace with: unwrap to the inner value's
  envelope and stamp a `wrapped_in: "Optional"` marker on
  `__meta__` so the wrapping signal is preserved without burying
  the payload. Empty Optional renders as an explicit
  `{ "__optional__": "empty" }` sentinel. Generalizes to
  `OptionalInt` / `OptionalLong` / `OptionalDouble` with the
  same shape. Scope is strictly synchronous wrappers — `Future`
  is a separate entry because the constraints differ.

- **Codec support for `Future` / `CompletableFuture` state
  capture.** Async wrappers whose value may not exist when the
  agent records them. The agent must never call `.get()` (would
  block) or peek at internals to fish out a maybe-null value
  (conflates pending / done-with-null / failed). Render as a
  tagged structure carrying *state* plus optional payload:
  `{ "__future__": { "state": "PENDING|DONE|FAILED|CANCELLED",
  "value": <T>?, "exception": <Throwable>? } }`. PENDING shows
  state only; DONE includes the value envelope; FAILED includes
  the exception envelope. A `PENDING` at AR transitioning to
  `DONE` at AX is interesting signal but is *not* a wrapper
  mutation — the Future object didn't change, its internal
  state did. The UI may eventually want a distinct decoration
  for that transition class; the codec just records what it
  sees at each end.

## Spec / wire-format evolution

### Adopt JCS (RFC 8785) for hash canonicalization — **Low priority**

**Status**: open. Triggers a wire-format **major bump** (1.x → 2.0).

> **Note on priority.** Originally filed as HIGH PRIORITY on the
> assumption that future non-JVM agents would also produce hashes.
> Under the actual architecture there is **one hasher** (Java, in
> the processor) — every agent in every language emits CBOR and the
> processor does all hashing. Cross-implementation hash agreement
> is therefore not a current concern. Even when polyglot ships,
> semantic correlation is gated by the ontology problem (sibling
> entry below), not by canonicalization. JCS is kept on the roadmap
> as the right long-term canonicalization choice, but is not
> load-bearing for any planned feature.

#### Motivation

Arachna Trace's hash inputs are produced by Jackson `ObjectMapper`
with `ORDER_MAP_ENTRIES_BY_KEYS=true`. That's a *de facto*
canonical form, not a portable spec. Java-specific quirks —
notably `Double.toString()` formatting (`1.0` vs `1`, `1.0E-4`
vs `0.0001`) — mean any future non-Java hashing implementation
would have to **mirror Jackson's exact byte output** to produce
matching MD5s. Achievable but fragile (a Jackson upgrade or a
porter's arithmetic difference one place can shift hashes).

[RFC 8785 — JSON Canonicalization Scheme](https://datatracker.ietf.org/doc/html/rfc8785)
is the IETF standard for byte-identical JSON serialization
across implementations. Adopting it replaces an ad-hoc
convention with a published, testable, language-neutral
contract.

#### Approach

The agent doesn't change — it emits CBOR, doesn't hash.
Replace `Hasher`'s Jackson serialization step with a JCS
implementation (one library in each target language —
`cyberphone/json-canonicalization` in Java, `jcs` on PyPI,
similar elsewhere). MD5 algorithm is unchanged; only the
bytes fed into it differ. ~50 lines in the processor.

The wire-format spec (`HASHING.md` §5) gains a normative
"canonicalization = JCS" clause; the current Jackson-quirk
enumeration becomes informative / historical for v1 stores.

#### Stored-data impact

Every byte change to the hash input flips every MD5. Concretely:

- `payloads.root_hash` — different value for the *same payload*.
- `payloads.own_hashes[]` — different values.
- `__meta__.hash` and `__meta__.own_hash` inside `payload_json`
  — different values.

Three migration paths to pick from at switch time:

1. **Wait it out.** Default TTL is 30 days. Stop writing v1
   data, start writing v2 data, in 30 days the store is
   uniformly v2. Zero migration code. Loses
   retain-flagged historical traces' hash queryability.
2. **Re-hash in place.** Walk every `payloads` row,
   re-canonicalize `payload_json` with JCS, recompute hashes,
   UPDATE row. Possible because Jackson canonicalization is
   deterministic so the raw payload still hashes-as-stored.
   Cost: O(stored payloads) one-time CPU.
3. **Version-bucket.** Add a `wire_format_version` column to
   `payloads`; queries that compare hashes filter by version.
   Cheap, but every consumer (UI, LLM tools, ad-hoc SQL) has
   to know about the column.

#### What stays working

- The UI doesn't care — it renders hex strings.
- Mutation detection inside one request / session / agent run
  — fine, both sides are v2 within one run.
- The CBOR envelope shape is unchanged (JCS only affects the
  JSON serialization used as the MD5 pre-image).

#### Trigger

Two conditions must both hold before this becomes worth the
migration cost:

1. A non-Java hashing implementation exists (a non-JVM file-mode
   agent, or a non-Java processor) — *and* its hashes must
   byte-match Java's.
2. Cross-language semantic correlation is in scope (see the
   sibling polyglot-ontology entry — JCS is necessary but not
   sufficient for that, and the ontology work is the harder
   half).

Today neither condition holds. Single Java hasher in the
processor, no polyglot deployments, no plans for either. The
entry stays on the roadmap so the choice is made consciously
when those conditions appear, not as standing work.

#### References

- [spec/HASHING.md §5](../../../spec/HASHING.md) — current
  canonicalization with the Jackson-quirk enumeration that
  this work replaces.
- [spec/PORTING-GUIDE.md](../../../spec/PORTING-GUIDE.md) —
  the porter's-perspective doc that today has to warn about
  byte-level Jackson mimicry.

### Polyglot semantic correlation needs an ontology, not a JSON convention

**Status**: open · **Low priority** — deferred until polyglot is a
real use case.

JCS gives byte-canonical JSON, which is a prerequisite for
cross-language hash agreement but not sufficient for correlating
"the same logical object" across language boundaries. A `BookSO`
produced by a Python service and a `BookSO` produced by a Java
service won't match even with JCS in place because:

- `class` strings diverge (`com.example.BookSO` vs
  `example.book.BookSO` vs `example/book.BookSO`).
- `object_id` is per-runtime (JVM long, Python `id()`, Go pointer)
  and own_hash embeds it via `__ref__`, so own_hash diverges too.
- Field names diverge — and not just casing. Different systems use
  genuinely different names for the same concept (`title` / `name`
  / `bookTitle` / `displayName`). A JSON-level normalization
  (snake_case ↔ camelCase) can't fix this; the divergence is
  semantic, not syntactic.

The right shape is a **semantic ontology layer** mapping
language-native types and fields onto shared logical concepts —
not a wire-format convention. Out of scope for v1; surfaces only
when polyglot deployments need joined queries, and best designed
then with the actual use case in hand.

---

# User-facing feature ideas

Brainstormed feature ideas captured for future implementation
rounds. Each entry: motivation, approach sketch, edge cases.
Status stays "open" until the work lands; struck-through entries
are shipped.

## FR-1 · Surface the agent's matcher patterns per session

**Status**: open

### Motivation

Today, when a developer opens a trace and a method they expected
to see isn't there, they have to read `arachna-agent.cfg` off disk to
find out the call didn't match `matchers_include` (or hit
`matchers_exclude`). Worst case they suspect a bug in the agent.
The pattern that decided to drop their call is an essential piece
of context for *reading* the trace, but it currently lives only
on the producer side.

### Approach sketch

1. **Capture the resolved config at agent-run start.** Capture
   the *resolved* matchers (file values overridden by inline
   `&...=...` args) so what gets recorded is what the agent
   actually used, not the file content.
2. **Wire-format / transport**: extend the AgentRun record
   (already carried via Kafka headers per
   `spec/TRANSPORT.md`). Two new headers:
   - `X-Arachna-Trace-Matchers-Include`
   - `X-Arachna-Trace-Matchers-Exclude`
3. **Schema**: add columns to `agent_runs`:
   - `matchers_include  String`
   - `matchers_exclude  String`
   Both stored verbatim (comma-separated regex literals).
4. **Server**: extend `/api/sessions` and the agent-runs response
   so the UI can read them.
5. **UI**: small chip in the session header — `🔍 3 include / 0
   exclude patterns`. Click expands to show the full regex list.
   On the empty state ("no calls in this request"), use the
   context to say *"Only methods matching `<pattern>` are
   instrumented"* — turns surprise into a teachable moment.

### Edge cases

- **Multiple agent runs in one session_id** (config-resolver
  users or restarts): show the union, or list per-run if they
  differ. Diff highlight between runs would catch "you changed
  the filter between deploys".
- **Long pattern lists**: truncate with "show all".
- **Inline overrides via `&matchers_include=...`** should be
  reflected, not the file content — captured naturally if the
  agent records the resolved config object, not re-parses the
  file.
- **Empty `matchers_include`** (extremely unusual): the agent
  instruments nothing. Should surface clearly so the developer
  doesn't think the trace is broken.

---

## FR-2 · Content-identity trace across requests (and across sessions)

**Status**: open

### Motivation

Today's instance trace follows **runtime-reference identity**:
same JVM instance, stable for as long as the runtime holds it
strongly. For framework-managed entities (JPA's persistence
context, request-scoped beans, ORM session caches in any
language) that lifetime typically ends with the request — the
next request materializes the same logical entity as a fresh
in-memory instance with a new `object_id`. Any forensic question
that crosses request boundaries — "this corrupted ISBN was first
written THREE requests ago", "where else did this exact User
snapshot appear today?" — is unanswerable with the current
primitive.

### Approach: `(class, own_hash)` content identity

The agent already emits `own_hash` per envelope (the object's own
scalar state) and indexes it on `payloads.own_hashes` via a bloom
filter. Pairing it with the envelope's `class` (also already
emitted) gives a content-addressable identity that's:

- **Framework-neutral**: no `@Id` / `@PrimaryKey` / Hibernate
  annotations. Works for plain Java POJOs, Kotlin data classes,
  Scala case classes, future non-JVM agents — anything
  serialized through the same envelope shape.
- **Language-neutral**: the agent / processor / server treat
  `class` as an opaque string; the wire format already does.
- **Zero new agent capture**: `class` and `own_hash` are both in
  the envelope today.

What it answers: *"every appearance of an instance of this class
with this exact own state"*. What it doesn't (deliberately):
*"track the same logical entity through mutations"* — when any
own-state scalar changes, the hash moves and the chain ends.
That deeper question requires framework-specific semantic input
the agent shouldn't carry; rejecting it here keeps the product
neutral. The user can always fall back to value-search for a
specific scalar to bridge across mutations.

### Approach sketch

**Server**:

- New endpoint, parallel to the existing `object-trace`:
  `GET /api/sessions/{id}/content-trace?class=...&own_hash=...`
  → ordered call list. The query is the existing bloom-filter
  probe on `own_hashes` plus a string equality on the envelope
  `class` (extracted from `payload_json` at enrich time, or held
  in a new parallel array column if frequent enough to warrant
  indexing).
- A session-less variant for cross-session use:
  `GET /content-trace?class=...&own_hash=...`. Same SQL, no
  session filter. Bound by retention TTL.

**Schema**:

- If matching by `(class, own_hash)` becomes a hot path, add a
  `payloads.class_own_hashes Array(String)` column (each entry
  `"<class>|<own_hash>"`, length-aligned with `object_ids`) plus
  a bloom-filter index. Falls naturally out of enrich time. No
  reflection or framework awareness required.
- Optional and incremental: phase 1 can match server-side by
  walking `payload_json` for matching envelopes, gated to the
  callers known to contain the hash via the existing
  `idx_own_hashes` index. Optimize later if hot.

**UI**:

- Second affordance on the envelope inspection: *"trace this
  **content** across the session"*.
- Bubble glyph / colour on call-tree rows distinct from the
  reference-identity bubble — reads as a different lens, not a
  different value of the same lens. Suggested palette: existing
  blue `→` for runtime-reference identity, a green `≈` for
  content identity, so a developer scanning the tree can read
  "same Java object" vs "matching state" at a glance.

### Edge cases / risks

- **Cross-class own_hash collisions**: vanishingly unlikely with
  MD5 even before adding `class`, but pairing them costs nothing
  and removes the question entirely. Always match on the pair.
- **Cross-tenant identical state**: two unrelated databases each
  with a `User { name = "alice" }` collide. Whether that's a
  feature or a bug depends on the use case. UI should show the
  `agent_run_id` / `code_version` / `env` per appearance so the
  operator can tell environments apart.
- **High-cardinality short-lived state**: when the session is
  full of objects whose own-scalars all differ (transient DTOs,
  every call has a new timestamp field), the index has many
  entries but the trace is rarely useful. Acceptable — same
  shape as `object_ids` today.

### Side-effect wins along the way

- An *"object never changed across N appearances"* badge falls
  out of own_hash equality across an existing instance trace.
- Origin chain becomes deeper — content match across requests,
  not just literal scalar match. The "where did this Author first
  appear in this state?" question gets a direct answer.
- Two identity lenses share the same UI primitive (the bubble +
  cycle nav we already built); the developer doesn't learn a new
  pattern.

---

## FR-3 · Per-session recording window — start / stop tracing under user control

**Status**: open

### Motivation

A developer debugging a long flow doesn't want noise from the
start (warmup, login, navigation) or the end (cleanup, logout) —
only the specific minutes around the bug. Today, the agent
records every matched call from the moment it's attached. The
user wants to send a signal "start recording now" / "stop
recording now" that's scoped to *their* session — other users on
the same JVM keep their normal behaviour.

Adjacent benefit: a "default off, opt-in per session" mode for
selective recording without the cost of capturing everything.

### Strategy: gate at the agent, not the pipeline

Two places to filter:

1. **At the agent**, before any capture work happens. A
   `RecordingState.shouldRecord(sessionId)` check at the top of
   `ArachnaTraceAdvice.recordEntry/recordExit`. `sessionId` is
   already in hand (SPI resolved it for the SI tag); the check
   is a `ConcurrentHashMap.containsKey` — O(1), no allocation.
   For not-recording sessions the cost approaches
   `serialize_values=false` since the expensive CBOR work is
   skipped entirely.
2. **At the destination / pipeline**, dropping records for
   not-recording sessions before the HTTP send (or before the
   ClickHouse insert). Saves network only — the agent still
   pays the full serialization tax. Wrong place for production.

Pick (1).

### Control mechanism — three options, can stack

1. **In-band API call (preferred base)** — a tiny `arachna-trace-api`
   jar with two static methods:
   ```java
   import com.github.gabert.arachna.trace.api.Arachna Trace;
   Arachna Trace.startRecording();   // resolve session, add to set
   Arachna Trace.stopRecording();    // resolve session, remove from set
   ```
   The agent's advice recognizes these by exact signature and
   toggles state without recording the call itself. Zero new
   network surface, no port, no firewall hole. Drop into code
   (or inject via a debugger / aspect at runtime) and you're
   done.
2. **HTTP control endpoint on the agent** — embed a tiny server,
   `POST /agent-control/recording?session=X&on=true`. Right
   shape for "external operator (SRE) flips it without touching
   app code". Adds an agent-side server + security questions —
   cost surface that the in-band API avoids.
3. **File-based control plane** — agent watches a directory;
   presence of `session-XYZ.recording` toggles the flag. Useful
   for ops-driven flows where code change is impossible.

All three plug into the same `RecordingState` set. Phase 1: ship
(1); add (2)/(3) on demand.

### Default-mode config

`recording_default` in `arachna-agent.cfg`, three values:

- `on` (current behaviour) — record everything;
  `stopRecording()` narrows: this session goes silent. *"Mute
  the noise around the bug."*
- `off` — record nothing; `startRecording()` activates this
  session. Other sessions remain silent.
- `on_after_first_signal` — record after the first
  `startRecording()` call anywhere in the JVM, scoped to the
  calling session. Belt-and-suspenders for *"I forgot to enable
  it in time."*

### Per-session lifecycle / leak prevention

A session that called `startRecording()` but never
`stopRecording()` would otherwise accumulate forever. Two
cleanup paths, can stack:

- **TTL eviction** — drop the recording flag if the session
  hasn't been seen in N minutes. Mirrors the existing
  WeakReference bookkeeping in `ObjectIdRegistry`.
- **Cap** — bounded LRU map; oldest active recording sessions
  get evicted when capacity is reached.

### Honest constraints to surface in docs

- **`session_resolver=config`** makes session_id JVM-global.
  With that resolver, `startRecording()` flips the flag for
  *every* request on the JVM — there's only one "session". Not
  a bug, but a foot-gun the docs need to warn about; users
  debugging per-request flows want `session_resolver=spring-session`
  or similar.
- **Matchers vs recording state are independent gates**: a user
  who toggles recording on but has no relevant
  `matchers_include` still sees nothing. The empty-state UX
  from FR-1 should mention recording state alongside the
  matcher patterns: *"Recording is on for this session, but no
  methods matching `<patterns>` ran in the recorded window."*

### Adjacent extensions (defer, but worth flagging)

- **Trigger by method match** —
  `recording_start_match=...regex...` /
  `recording_stop_match=...`. Agent flips recording when a
  matching method is entered/exited on a session. Same
  `RecordingState` set, more entry points. Matches the existing
  `matchers_include` regex shape — uniform mental model.
- **Granular recording** — instead of binary on/off, "structural
  only" (MS / TS / TE) vs "full envelopes" (current). Reuses
  the existing `emit_tags` machinery; the recording state can
  pin a different `emit_tags` set per session.
- **UI-driven recording** — the Arachna Trace UI exposes a "start
  recording on session X" button that calls (1)'s in-band API
  via a small admin endpoint in the user's app. Closes the
  loop — developer is already in the UI debugging, doesn't
  context-switch to a terminal.

---

## FR-4 · Surface mutation counts at the AR / AX section headers

**Status**: open. Acknowledges that AX-header counts are already
shipped (per `BUG_FINDING_DESIGN.md`); this FR promotes them to a
proper chip and mirrors on AR.

### Motivation

RE has a `⚠ exception` chip in its section header (via
`<ExceptionChip>`), so the developer reads "this call threw"
without expanding anything. AR/AX have count text but no
equivalent chip. To discover that a call mutated some object,
the user has to: expand AX → expand the envelope → see the
JsonTree mutated marker. Three clicks for what should be
glanceable. The signal is already present in the loaded data
(`mutatedObjectsByCallId` and `addedObjectsByCallId` from the
mutations endpoint); the UI just isn't surfacing it at the right
level.

### Approach sketch

**A new `<MutationChip>`**, parallel to `<ExceptionChip>` — same
rounded pill shape, amber palette (`#fbbf24` family — same
colour as the mutated bubble in JsonTree and the trace banner,
so the section-level chip reads as "section-level version of a
signal you already recognize at the row level").

Render in `CallInspectionCard.vue`'s section headers when the
section is `AR` or `AX` AND there's at least one mutated or
added envelope for the call:

```
<ExceptionChip v-if=...> ... existing
<MutationChip v-if="(p.kind === 'AR' || p.kind === 'AX')
                && (mutatedCount(call_id) > 0 || addedCount(call_id) > 0)"
              :mutated="mutatedCount(call_id)"
              :added="addedCount(call_id)" />
```

**Chip body**:

- `Δ N` when `mutated > 0` (the delta glyph is already legible
  in the codebase's typography and visually adjacent to the
  envelope-diff metaphor).
- `+N` when `added > 0`.
- Both joined with a dot — `Δ 3 · +2` — when both are non-zero.

**Click behaviour**: click → expand the AR/AX section if
collapsed, then scroll JsonTree to the first mutated/added node.
Re-uses the existing `goto` + `HIGHLIGHT` injection plumbing —
no new primitive.

### Why both AR and AX, not just one

The mutation is a property of the AR↔AX pair. Showing the chip
on both halves matches how the developer reads the card
top-to-bottom: heads-up on AR ("there's a change between this
and AX"), then on AX ("here's the new state"). Slight visual
redundancy is the right trade — a mutation chip only on AX would
be equally honest but makes the AR section feel deceptively
quiet.

TI / RE never get the chip:

- TI mutation is an edge case (the receiver mutated mid-call).
  If we ever want it, it's a separate signal; out of scope here.
- RE is the return value, not part of the AR↔AX diff.

### Edge cases

- AX rows for unmutated calls are already filtered out of
  display in some flows. Keep that — no mutations means no chip
  means no visual noise. The chip only ever appears when
  `mutatedCount + addedCount > 0`.
- Only-added (objects appeared in AX that weren't in AR) is
  meaningful (creation events) — chip renders with just `+N`.
- Very high counts: don't truncate the count itself; the digits
  ARE the signal. A call where 30 books had ISBNs rewritten
  reads very differently from one where 1 author changed name.

### Side-effect wins

- The chip's pattern (`<MutationChip>`, parallel to
  `<ExceptionChip>`, slot-into-section-header) generalizes to
  any future "structured signal at section level" — e.g. a
  "values truncated by max_value_size" badge could land on the
  same hook.

---

## FR-5 · Field-level mutation diff inline in JsonTree

**Status**: open · **Composes with FR-4**

### Motivation

Even with FR-4's section-level chip telling the developer "this
AR/AX has 3 mutations", they still have to scan AR vs AX
side-by-side to find which fields actually moved. For an
envelope with 20 fields where one ISBN shifted by a character,
that's exactly the busywork the tool should obviate. The data
already lives in the mutations endpoint response (`field_paths`
per group, plus the AR/AX envelope snapshots) — we just don't
surface it at field level.

The Mutations panel already shows the diff inline per group; FR-5
puts the same signal where the developer is actually looking when
inspecting a single call — inside the JsonTree of AR/AX.

### Approach sketch

**Render the diff inline in JsonTree** at the leaf where the
mutation happened. AX section gets the loud signal (this is
"what the call did"); AR optionally gets a fainter mirror or
skips it entirely.

For a mutated leaf in AX:
```
isbn: "9780618002213"   ← was "978-0-618-00221-3"
```

For an added leaf in AX:
```
+ registration_groups: "..."
```

For a removed leaf shown on AR:
```
~~old_field~~: "..."
```

Truncation rules: long values collapse to first/last N chars
with hover-for-full, same pattern we use elsewhere for long
strings. If either value exceeds threshold (~40 chars), stack
vertically instead of inlining; preserves readability without
breaking the JsonTree's per-line rhythm in the common case.

### Component shape

**New `<FieldDiff>` element** — JsonTree drops it at any leaf
whose canonicalized path matches a mutated path on the rendered
envelope. Wraps the leaf's value rendering plus the `← was "..."`
annotation.

**Inject shape**, one new key, one extension:

- New `MUTATED_FIELD_PATHS_BY_OBJECT_ID:
   Map<objectId, Set<pathKey>>` — exact-match field-level set.
- New `MUTATED_OLD_VALUES_BY_OBJECT_ID:
   Map<objectId, Map<pathKey, unknown>>` — the AR-side value, so
  `<FieldDiff>` has the "was" content to render without a
  re-fetch. Sourced from the mutations endpoint response
  (already carries AR/AX snapshots per group).

Both populated by the existing `useObjectChanges` composable;
no new server endpoint.

### Click behaviour

The `← was "..."` annotation is itself clickable. Behaviour:

- Opens a small popover with the full before/after as JSON.
  Useful when the change is structural (a child object
  reference flipped, an array item count shifted) rather than a
  scalar swap.
- Optional escalation: a "trace this old value" entry in the
  popover that triggers the value-search → origin chain on the
  pre-mutation value. Field-level origin, not just
  envelope-level.

### Edge cases

- **Nested mutations** — when a child envelope changed but the
  parent's own scalars didn't, the parent's leaf paths don't
  match. Surface a subtler indicator on the parent (e.g. an
  amber dot in the gutter) that says "drill in for changes".
  Honest: the parent itself didn't change, but something below
  it did.
- **Added fields** — present in AX, absent from AR. Render with
  a leading `+` and amber tint, no `← was` annotation (there
  was no "was").
- **Removed fields** — present in AR, absent from AX. Render on
  AR side struck-through. AX side has nothing at that path to
  render.
- **Cycle refs** — unchanged. Cycle marker stays a cycle marker;
  the mutation it points to is rendered on the resolved
  envelope.
- **Very long old values** — first/last N chars with
  hover-for-full. Same rule we already use for long strings.

### Composability

- **With FR-4 (section chip)**: the chip's "click to scroll to
  first mutated field" target now lands on a field that has its
  own diff indicator. Full reading flow: see chip → click → land
  on changed field → see the diff inline. Two clicks total, zero
  manual comparison.
- **With FR-2 (content trace)**: clicking `← was "<old>"` can
  hop into a content-trace on the OLD value — "where else has
  this old state been seen?" — turning the field's diff into a
  starting point for backwards origin analysis.

### Side-effect wins

- One signal, three depths: chip on the section header (count),
  amber row in JsonTree (which envelope), inline `← was ...` at
  the leaf (which field, before/after). The user can read at
  whichever resolution they need without changing tool.

---

## FR-6 · Infra-only compose mode for external agents

**Status**: open

### Motivation

Today's quickstart `release/compose.yml` is "everything in one
shot": Kafka + ClickHouse + collector + processor + query + UI
+ **demo Spring Boot app** + **traffic generator**. Perfect for
the 60-second "see what this thing does" first impression —
visitor opens `http://localhost:8080` and the UI is already
populated with the demo's intentional silent-mutation bug.

But for a developer whose next question is *"OK, now I want to
try this on **my** app"* the full stack is more than they need:

- The demo Spring Boot container runs and generates traffic
  they don't care about (and that pollutes the UI with demo
  sessions).
- They want to attach the agent to **their** JVM and see
  **their** traces only.
- The current path — edit `compose.yml`, comment out
  demo-spring-boot + demo-traffic, expose the collector port,
  figure out the agent config — is more steps than "try it on
  my app" should take.

The gap is the doc/demo funnel's natural second step: *wow →
hook → instrument*. The first compose covers wow + hook;
FR-6 fills the third.

### Approach sketch

A second compose file at `release/compose-infra.yml` (or
`compose-byoa.yml` — naming TBD). Same services as
`compose.yml` **minus** `demo-spring-boot` and `demo-traffic`,
**plus** the collector port (`8099`) exposed to the host so an
agent running outside the docker network can POST to it.

Developer workflow becomes:

```bash
mkdir arachna-trace && cd arachna-trace
curl -fsSLO https://raw.githubusercontent.com/gabert/arachna-trace/main/release/compose-infra.yml
docker compose -f compose-infra.yml up
```

Open `http://localhost:8080` — UI is up, empty.

Configure their app's agent (the typical `arachna-agent.cfg`):

```properties
destination=http
http_server_url=http://localhost:8099/records
matchers_include=com\.theirapp\..*
session_resolver=spring-session     # or whatever fits
```

Attach to their JVM:

```bash
java -javaagent:path/to/arachna-trace-agent.jar=config=arachna-agent.cfg -jar their-app.jar
```

Refresh the UI — their session(s) appear with their traces.

### Edge cases

- **Where does the agent JAR come from?** The first developer
  trying this won't have the source. Two options:
  - Document `git clone + mvn clean install` (extra step but
    self-contained — no third party).
  - Publish the agent JAR to GitHub Releases (preferable; the
    60-second framing wants zero JDK setup just to start
    capturing).
- **Cross-network agent reach.** `localhost:8099` works if the
  app runs on the same host as the compose stack. If the dev's
  app runs in another docker network, they need
  `host.docker.internal` (Docker Desktop) or the host's bridge
  IP (Linux). Worth a short troubleshooting section.
- **`code_version` / `env` attribution.** When the dev's
  traces and demo traces coexist in the same ClickHouse
  (because they ran `compose.yml` first, then `compose-infra.yml`
  later, against the same volume), the UI needs to disambiguate.
  Already supported via `agent_runs.code_version` / `env`; doc
  should call out setting these.
- **No auth on the exposed collector.** Localhost-only is the
  safe default (which is what `0.0.0.0:8099` from docker amounts
  to on a personal machine). Anyone considering a non-local
  collector exposure has stepped into production-deployment
  territory and should re-read `deployment-modes.md`.
- **Compose file maintenance.** The two compose files share
  ~80% of lines. Could be implemented as one base + one
  override, or as two slightly-divergent files. Pick whichever
  is clearer at the time; both are fine.

### Side-effect wins

- **Doubles as the right production shape.** No one runs the
  demo Spring Boot in production — production *is* "infra only,
  bring your own apps". This compose file is what an operator
  copies as a starting point, with auth + TLS + network policy
  layered on top.
- **Lowers the bar for the "instrument-your-app" doc** that
  currently exists only conceptually (per
  `project_first_setup_funnel.md` memory). FR-6's recipe IS
  the doc.
- **Makes the demo file's role explicit.** Today `compose.yml`
  is the only option; users assume "this is how arachna-trace runs."
  With two files side-by-side it's clear that the demo
  container is teaching scaffolding, not part of the product.

### Naming

- `compose.yml` keeps the "everything, including the demo"
  role (the GHCR quickstart link in the root README continues
  to point here).
- `compose-infra.yml` (or `-byoa.yml`) is the new
  bring-your-own-app file.
- The two appear together in `release/README.md` with a clear
  "pick one" header.
