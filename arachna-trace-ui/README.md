# arachna-trace-ui

Web UI for browsing Arachna Trace traces. Vite + Vue 3 (no TypeScript) + PrimeVue.

Talks to `record-query-server` (default `http://localhost:8082`) via the Vite
dev proxy on `/api`.

## Run

```bash
npm install
npm run dev
```

Open http://localhost:5173.

The query server must be running for any data to load:

```bash
cd ../arachna-trace-agent
mvn -pl server/record-query-server -am package
java -jar server/record-query-server/target/record-query-server.jar \
     config=server/record-query-server/arachna-query.cfg
```
