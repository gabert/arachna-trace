# DeepFlow release / quickstart

Try DeepFlow in 60 seconds. Only Docker is required — Java is **not** needed
for the demo (the demo Spring Boot app already runs in a container with
the agent baked in).

## Try it

```bash
mkdir deepflow && cd deepflow
curl -O https://raw.githubusercontent.com/gabert/deepflow/main/release/compose.yml
docker compose up
```

Open <http://localhost:8080>. The UI lands on a session populated by the
demo container, which fires a small traffic burst on boot. You should see
traces immediately.

To shut everything down and reclaim the disk:

```bash
docker compose down -v
```

## What's running

| Service | Role |
|---|---|
| `kafka` | Single-node KRaft broker, buffers binary records |
| `clickhouse` | Stores parsed calls + payloads |
| `collector` | Receives binary records from agents over HTTP, publishes to Kafka |
| `processor` | Consumes Kafka, renders + hashes, writes to ClickHouse |
| `query` | Read-only HTTP API in front of ClickHouse |
| `ui` | Vue/PrimeVue UI served by nginx (also proxies `/api/*` to `query`) |
| `demo-spring-boot` | Library demo app with the DeepFlow agent attached |
| `demo-traffic` | One-shot container that hits the demo a few times so the UI is non-empty |

Only `ui` exposes a host port (`8080:80`). The rest talk over the
internal Docker network.

---

## Releasing (maintainer)

The compose pulls images from `ghcr.io/gabert/deepflow-*`. Cutting a new
release is a single command:

```bash
./release/release.sh v0.1.0
```

The script verifies the local environment (Docker, multi-arch buildx
builder, GHCR auth, repo layout), then builds all six images for
`linux/amd64` + `linux/arm64` and pushes them with both `:v0.1.0` and
`:latest` tags.

### One-time prerequisites

1. **Docker Desktop** (or any Docker daemon with `buildx`).
2. **GHCR auth.** Create a Personal Access Token (classic) with the
   `write:packages` scope at <https://github.com/settings/tokens>, then:

   ```bash
   echo $GHCR_TOKEN | docker login ghcr.io -u gabert --password-stdin
   ```

3. **First push of a new image name → flip the package to public.** GHCR
   creates new packages as private. Until they're public, anonymous
   `docker pull` (i.e. `docker compose up` for any user without
   credentials) fails. Either:

   - Web UI: GitHub → your profile → Packages → click the package →
     Package settings → Change visibility → Public.
   - Or `gh` CLI:

     ```bash
     for n in deepflow-collector deepflow-processor deepflow-query \
              deepflow-demo-spring-boot deepflow-clickhouse deepflow-ui; do
       gh api -X PATCH /user/packages/container/$n -f visibility=public
     done
     ```

### After the release

Update the version pinned in `release/compose.yml` (the `:vX.Y.Z`
tags) and commit. That's the version users will pull on their next
`docker compose pull && docker compose up`.
