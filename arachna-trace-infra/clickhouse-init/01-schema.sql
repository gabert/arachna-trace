-- ArachnaTrace ClickHouse schema.
--
-- Two tables, both partitioned by day, TTL 30 days:
--
--   calls    : one row per method invocation. Light. For session/request/time-slice queries.
--   payloads : one row per (call, kind in {TI, AR, AX, RE}). Heavy JSON + extracted hash and
--              object-id index. For object-identity search and mutation detection.
--
-- Both insert paths are populated by RecordProcessorServer.
--
-- Identity columns:
--   agent_run_id : per-JVM-run UUID (from RH wire record at agent start).
--                  Disambiguates traces from concurrent / sequential JVM runs that
--                  may share session_id (e.g. config-resolver users).
--   call_id      : per-method-invocation UUID. Pairs MS↔ME on the wire and
--                  serves as a single-field stable handle for tool calls.
--   parent_call_id : the call that lexically (sync) or by-submitter (async)
--                    contains this call. Nullable at the root of a request.

CREATE TABLE IF NOT EXISTS arachna_trace.calls
(
    agent_run_id   UUID,
    call_id        UUID,
    parent_call_id Nullable(UUID),
    session_id     String,
    request_id     UInt64,
    thread_name    LowCardinality(String),
    ts_in          DateTime64(3),
    ts_out         DateTime64(3),
    duration_ms    Int64                     MATERIALIZED dateDiff('millisecond', ts_in, ts_out),
    signature      LowCardinality(String),
    caller_line    Int32,
    return_type    Enum8('VOID' = 0, 'VALUE' = 1, 'EXCEPTION' = 2),
    is_exception   Bool                      MATERIALIZED return_type = 'EXCEPTION',
    this_id        Nullable(Int64),
    -- Agent-observation order, monotonic per agent run, gap-free across the
    -- run. Canonical primitive for narrative ordering — sub-millisecond ties
    -- on ts_in are disambiguated by seq. Zero when the producer omits the
    -- SQ tag (emit_tags excludes "SQ"); in that mode, ordering falls back
    -- to ts_in (with the original ms-resolution ambiguity).
    seq            UInt64 DEFAULT 0,
    -- Retention escape: rows with retain=true survive the TTL DELETE.
    -- Currently no agent/sink path sets this; flip via external SQL when
    -- a debugging or audit need warrants long retention.
    retain         Bool DEFAULT false,
    inserted_at    DateTime DEFAULT now(),

    INDEX idx_call_id     call_id     TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_parent_call parent_call_id TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts_in)
ORDER BY (session_id, request_id, seq, ts_in)
TTL toDateTime(ts_in) + INTERVAL 30 DAY DELETE WHERE NOT retain;

CREATE TABLE IF NOT EXISTS arachna_trace.payloads
(
    agent_run_id  UUID,
    call_id       UUID,
    session_id    String,
    request_id    UInt64,
    ts_in         DateTime64(3),
    signature     LowCardinality(String),
    kind          Enum8('TI' = 0, 'AR' = 1, 'AX' = 2, 'RE' = 3),
    payload_json  String,
    payload_size  UInt32,
    root_hash     FixedString(32),
    object_ids    Array(Int64),
    -- Length-aligned with object_ids: own_hashes[i] is the own-state hash
    -- of object_ids[i] at its first appearance in this payload. Lets the
    -- query "find calls where some object had own_hash X" run as a single
    -- bloom-filter probe instead of JSON scanning. Empty strings for
    -- payloads enriched before own_hash existed (graceful coexistence).
    own_hashes    Array(String),
    -- Distinct canonicalized scalar values present in the payload tree
    -- (strings, numbers, booleans). Populated at enrich time by
    -- ScalarTokenCollector. Powers value-search and provenance lookups
    -- as a bloom-filter `has(...)` probe instead of `payload_json LIKE`
    -- full-table scans. Empty for payloads enriched before this column
    -- existed; the index just produces no false-positives for those.
    payload_tokens Array(String),
    -- Mirrors calls.seq for the call this payload belongs to. Lets payload
    -- queries order causally without a join back to calls.
    seq           UInt64 DEFAULT 0,
    retain        Bool DEFAULT false,
    inserted_at   DateTime DEFAULT now(),

    INDEX idx_object_ids     object_ids     TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_own_hashes     own_hashes     TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_payload_tokens payload_tokens TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_call_id        call_id        TYPE bloom_filter(0.01) GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts_in)
ORDER BY (session_id, request_id, seq, kind, ts_in)
TTL toDateTime(ts_in) + INTERVAL 30 DAY DELETE WHERE NOT retain;

-- ============================================================
--  agent_runs : one row per JVM-with-agent run.
--    Populated when an RH wire record arrives. ReplacingMergeTree so
--    a re-issued insert (e.g. processor restart re-reading from Kafka)
--    de-duplicates without us having to track seen-runs persistently.
-- ============================================================
CREATE TABLE IF NOT EXISTS arachna_trace.agent_runs
(
    agent_run_id    UUID,
    hostname        String,
    jvm_pid         UInt32,
    agent_version   String,
    code_version    String,                 -- empty when unset
    env             LowCardinality(String), -- empty when unset
    started_at      DateTime64(3),
    ended_at        Nullable(DateTime64(3)),
    completed_clean Bool DEFAULT false,
    inserted_at     DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(inserted_at)
ORDER BY (agent_run_id);

-- ============================================================
--  sessions : one row per (agent_run_id, session_id) pair seen.
--    Lightweight in v1: just identity + timestamps. Counts and tags
--    can be added when concrete queries demand them.
-- ============================================================
CREATE TABLE IF NOT EXISTS arachna_trace.sessions
(
    session_id    String,
    agent_run_id  UUID,
    first_seen    DateTime64(3),
    last_seen     DateTime64(3),
    -- Open user-facing bag for arbitrary session metadata (e.g.
    -- tenant, feature flag, audit reason). Carried opaquely; the
    -- only convention is that 'retain' here mirrors the boolean column.
    tags          Map(String, String) DEFAULT map(),
    retain        Bool DEFAULT false,
    inserted_at   DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(inserted_at)
ORDER BY (session_id, agent_run_id);

-- ============================================================
--  requests : aggregated rollup, one logical row per
--    (agent_run_id, session_id, request_id). Maintained by a
--    materialized view that reads every insert into `calls` and
--    folds it into the rollup via SimpleAggregateFunction columns.
--
--    Why a rollup, not the raw calls?
--    The UI's bread-and-butter view is "list recent requests, sorted
--    by duration / show me the failures." Doing that off `calls`
--    would require a GROUP BY across billions of rows per page-load.
--
--    Why CH-side aggregation, not in-process?
--    The processor cannot reliably tell when a request is "done"
--    in the presence of fire-and-forget async work. Late calls
--    that arrive after the synchronous root closes still belong
--    to the request. The MV folds them in automatically — the
--    in-memory aggregator can't, which was bug B-03.
--
--    Reads should go through `requests_view` below to avoid the
--    SimpleAggregateFunction column types leaking into queries.
-- ============================================================
CREATE TABLE IF NOT EXISTS arachna_trace.requests
(
    agent_run_id     UUID,
    session_id       String,
    request_id       UInt64,
    started_at       SimpleAggregateFunction(min, DateTime64(3)),
    ended_at         SimpleAggregateFunction(max, DateTime64(3)),
    call_count       SimpleAggregateFunction(sum, UInt64),
    exception_count  SimpleAggregateFunction(sum, UInt64),
    -- Per-request entrypoint signature and originating thread.
    -- entry_signature uses max-with-empty-fallback so the root
    -- (parent_call_id IS NULL) wins when present and the column
    -- stays empty otherwise. thread_name picks max over ALL calls
    -- in the request — when the root isn't instrumented (the agent
    -- doesn't see Spring's own dispatch frame, for instance) any
    -- in-request thread name still surfaces, instead of an empty
    -- string. Multi-threaded requests pick alphabetically largest
    -- deterministically.
    entry_signature  SimpleAggregateFunction(max, String),
    thread_name      SimpleAggregateFunction(max, String),
    retain           Bool DEFAULT false
)
ENGINE = AggregatingMergeTree
ORDER BY (agent_run_id, session_id, request_id)
TTL toDateTime(started_at) + INTERVAL 30 DAY DELETE WHERE NOT retain;

-- The materialized view that maintains `requests`. Fires on every
-- insert into `calls`; aggregates that batch's rows by request and
-- writes the partial state into `requests` (which merges across
-- batches via the SimpleAggregateFunction columns).
CREATE MATERIALIZED VIEW IF NOT EXISTS arachna_trace.requests_mv TO arachna_trace.requests AS
SELECT
    agent_run_id,
    session_id,
    request_id,
    min(ts_in)                                        AS started_at,
    max(ts_out)                                       AS ended_at,
    toUInt64(count())                                 AS call_count,
    toUInt64(countIf(return_type = 'EXCEPTION'))      AS exception_count,
    max(if(parent_call_id IS NULL, signature,    '')) AS entry_signature,
    max(thread_name)                                  AS thread_name
FROM arachna_trace.calls
GROUP BY agent_run_id, session_id, request_id;

-- Clean-read view: collapses any unmerged parts at query time and
-- exposes the derived columns (`has_exception`, `duration_ms`) so
-- UI / ad-hoc queries don't need to know about SimpleAggregateFunction.
--
-- The aggregation lives in an inner subquery so the outer SELECT can
-- derive `has_exception` and `duration_ms` from plain values. Without
-- the subquery, ClickHouse resolves the second `exception_count`
-- reference to the alias (itself a `sum(...)`) and rejects the view as
-- nested aggregation.
CREATE VIEW IF NOT EXISTS arachna_trace.requests_view AS
SELECT
    agent_run_id,
    session_id,
    request_id,
    started_at,
    ended_at,
    call_count,
    exception_count,
    entry_signature,
    thread_name,
    exception_count > 0                                AS has_exception,
    dateDiff('millisecond', started_at, ended_at)      AS duration_ms,
    retain
FROM
(
    SELECT
        agent_run_id,
        session_id,
        request_id,
        min(started_at)      AS started_at,
        max(ended_at)        AS ended_at,
        sum(call_count)      AS call_count,
        sum(exception_count) AS exception_count,
        max(entry_signature) AS entry_signature,
        max(thread_name)     AS thread_name,
        -- retain is a regular column, not aggregated — `any` is fine
        any(retain)          AS retain
    FROM arachna_trace.requests
    GROUP BY agent_run_id, session_id, request_id
);
