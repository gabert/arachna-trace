# Record Query Server Configuration

The read-only HTTP API the `deepflow-ui` Vue app talks to.
Translates UI requests into ClickHouse SQL; no write paths,
no agent-side state.

Config file: `server/record-query-server/deepquery.cfg`.
Passed at startup as `java -jar record-query-server.jar config=deepquery.cfg`.
CLI overrides are inline `key=value` pairs that take precedence
over file values.

For component context (endpoint families, CH parameter binding,
CORS handling), see
[../internals/query-server.md](../internals/query-server.md).

## Options

### server_port

HTTP listen port for the JSON API.

Default: `8082`. The Vite dev server in `deepflow-ui/` proxies
`/api` to this port; the demo `docker-compose.yml` publishes it
as well.

```properties
server_port=8082
```

### clickhouse_url

Base URL of the ClickHouse HTTP interface. Should point at the
same instance the processor writes to.

Default: `http://localhost:8123`.

```properties
clickhouse_url=http://clickhouse:8123
```

### clickhouse_database

Database name. Should match the processor's `clickhouse_database`.

Default: `deepflow`.

```properties
clickhouse_database=deepflow
```

### clickhouse_user

ClickHouse user the query server authenticates as. Needs SELECT
permission on `calls`, `payloads`, `agent_runs`, `sessions`,
`requests`, `requests_view`. Read-only — does **not** need
INSERT / ALTER.

Default: `deepflow`.

```properties
clickhouse_user=deepflow
```

In a hardened deployment, give the query server a dedicated
read-only user (separate from the processor's write user) so a
compromised query path can't mutate data.

### clickhouse_password

Password for `clickhouse_user`. Same secret-rotation guidance
as the processor.

Default: `deepflow`.

```properties
clickhouse_password=<set-via-secrets-in-production>
```

### cors_origin

Value of the `Access-Control-Allow-Origin` header returned on
every response. Required because the UI is typically served from
a different origin than the API (dev: `:5173` vs `:8082`;
production: the UI's deployed origin vs the API's).

Default: `http://localhost:5173` (Vite dev server).

In production:

- Set to the UI's deployed origin (e.g. `https://deepflow.example.com`).
- Or `*` if the API sits behind a gateway that handles CORS or
  is only reachable from trusted networks.

```properties
cors_origin=https://deepflow.example.com
```

The handler also returns `Access-Control-Allow-Methods: GET, OPTIONS`
and `Access-Control-Allow-Headers: Content-Type` unconditionally;
those are not configurable.

## What's not configurable

- **The endpoint surface** itself is fixed in code (`QueryHandler`
  routes a closed set of paths). Adding endpoints is a code
  change, not a config change. See
  [../internals/query-server.md](../internals/query-server.md)
  for the list and the structure for adding new ones.
- **Request body limits** — the query server takes only GET
  requests; the `HttpObjectAggregator` cap is 1 MiB, hard-coded.
  Query strings approaching this size mean a query design
  problem, not a config problem.
