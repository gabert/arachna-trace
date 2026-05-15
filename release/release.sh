#!/usr/bin/env bash
#
# Manual release: build all ArachnaTrace release images for amd64+arm64
# and push them to GHCR. No CI involved — you run this on your machine.
#
# Usage:
#   ./release/release.sh v0.1.0
#   ./release/release.sh v0.1.0 --yes        # skip the confirmation prompt
#   ./release/release.sh v0.1.0 --no-push    # build host arch only, --load to local docker, do not touch GHCR
#   RELEASE_YES=yes ./release/release.sh v0.1.0
#
# What it does, in order:
#   1. Verify local environment (docker daemon, buildx, repo layout,
#      and — only when pushing — multi-arch builder + GHCR auth).
#   2. Build all six images.
#   3. Push to ghcr.io/gabert/arachna-trace-* (or --load locally with --no-push).
#
# First-time-per-image-name follow-up (one-shot per package, in GitHub UI):
#   GitHub → your profile → Packages → click the package → Package settings
#   → Change visibility → Public. Without this, anonymous `docker pull`
#   from `docker compose up` fails for users.

set -euo pipefail

# ---------------------------------------------------------------- helpers
red()    { printf '\033[31m%s\033[0m\n' "$*" >&2; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
say()    { printf '>>> %s\n' "$*"; }
die()    { red "FATAL: $*"; exit 1; }

# ---------------------------------------------------------------- args
VERSION="${1:-}"
[[ -n "$VERSION" ]] || die "missing version arg. Usage: $0 v0.1.0 [--no-push] [--yes]"
[[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] \
    || die "version must look like v1.2.3 or v1.2.3-rc1 (got: $VERSION)"

NO_PUSH="no"
SKIP_PROMPT="${RELEASE_YES:-no}"
shift  # consume version
for arg in "$@"; do
    case "$arg" in
        --no-push)        NO_PUSH="yes" ;;
        --yes|-y)         SKIP_PROMPT="yes" ;;
        *)                die "unknown flag: $arg" ;;
    esac
done

# ---------------------------------------------------------------- locate repo root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ---------------------------------------------------------------- config
REGISTRY="ghcr.io"
NAMESPACE="gabert"
MULTIARCH_PLATFORMS="linux/amd64,linux/arm64"
BUILDER_NAME="arachna-trace-builder"

# image-name : dockerfile : target (empty = no target)
IMAGES=(
    "arachna-trace-collector:release/Dockerfile:collector"
    "arachna-trace-processor:release/Dockerfile:processor"
    "arachna-trace-query:release/Dockerfile:query"
    "arachna-trace-demo-spring-boot:release/Dockerfile:demo-spring-boot"
    "arachna-trace-clickhouse:release/Dockerfile.clickhouse:"
    "arachna-trace-ui:release/Dockerfile.ui:"
)

# ---------------------------------------------------------------- phase 1: verify env
say "Phase 1: verifying environment ($([[ "$NO_PUSH" == "yes" ]] && echo "dry-run, no push" || echo "release with push"))"

command -v docker >/dev/null \
    || die "docker not on PATH. Install Docker Desktop (or equivalent) and retry."

# Daemon probe. Capture stderr so we can give a Windows-pipe-aware message
# instead of swallowing the underlying error.
DOCKER_INFO_ERR=$(docker info 2>&1 >/dev/null) || {
    case "$DOCKER_INFO_ERR" in
        *"dockerDesktopLinuxEngine"*|*"named pipe"*|*"pipe/docker"*)
            die "docker daemon not reachable. Start Docker Desktop and wait for it to finish initializing." ;;
        *"permission denied"*)
            die "docker daemon refused (permission denied). Add your user to the 'docker' group or rerun with sudo." ;;
        *)
            die "docker daemon not reachable: ${DOCKER_INFO_ERR}" ;;
    esac
}

docker buildx version >/dev/null 2>&1 \
    || die "docker buildx not available. Install buildx (Docker Desktop ships with it)."

if [[ "$NO_PUSH" == "yes" ]]; then
    # Dry run: stay on the default builder (loads to local docker), build
    # host architecture only, skip GHCR auth check entirely.
    BUILDER_FLAG=()
    PLATFORM_FLAG=()
    OUTPUT_FLAG="--load"
    yellow "    dry-run mode — using default builder, host arch, --load (no push)"
else
    # Real release: multi-arch needs a docker-container builder; the default
    # 'docker' driver does not support cross-platform.
    if ! docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
        yellow "    creating buildx builder '$BUILDER_NAME' (one-time setup)"
        docker buildx create --name "$BUILDER_NAME" --driver docker-container --bootstrap
    fi

    # Confirm the builder advertises both target platforms.
    PLATFORM_LIST=$(docker buildx inspect "$BUILDER_NAME" | awk -F': *' '/^Platforms:/ {print $2}')
    for plat in linux/amd64 linux/arm64; do
        [[ "$PLATFORM_LIST" == *"$plat"* ]] \
            || die "builder '$BUILDER_NAME' does not advertise $plat. Got: $PLATFORM_LIST"
    done

    # GHCR login. Hard-fail if missing — the alternative is a 10-minute build
    # that explodes at the push step with an opaque 401.
    DOCKER_CONFIG="${DOCKER_CONFIG:-$HOME/.docker}"
    CONFIG_FILE="$DOCKER_CONFIG/config.json"

    login_hint() {
        cat <<EOF >&2

To log in to GHCR:

  echo \$GHCR_TOKEN | docker login ghcr.io -u gabert --password-stdin

The PAT (classic) needs the 'write:packages' scope. Create one at:
  https://github.com/settings/tokens

EOF
    }

    if [[ ! -f "$CONFIG_FILE" ]]; then
        red "    no docker config at $CONFIG_FILE — you've never logged in to any registry."
        login_hint
        exit 1
    fi

    # Portable JSON probe: prefer jq if present, else fall back to a strict
    # awk parse. False negatives possible only if config.json was hand-edited
    # into an unusual shape; in that case re-run `docker login`.
    if command -v jq >/dev/null 2>&1; then
        GHCR_AUTH=$(jq -r '.auths."ghcr.io".auth // empty' "$CONFIG_FILE" 2>/dev/null)
        CREDS_STORE=$(jq -r '.credsStore // empty' "$CONFIG_FILE" 2>/dev/null)
        CREDS_HELPER=$(jq -r '.credHelpers."ghcr.io" // empty' "$CONFIG_FILE" 2>/dev/null)
    else
        GHCR_AUTH=$(awk '
            /"ghcr\.io"/ { in_block = 1; next }
            in_block && /"auth"[[:space:]]*:[[:space:]]*"[^"]+"/ {
                sub(/.*"auth"[[:space:]]*:[[:space:]]*"/, "")
                sub(/".*/, "")
                print
                exit
            }
            in_block && /\}/ { in_block = 0 }
        ' "$CONFIG_FILE")
        CREDS_STORE=$(awk -F'"' '/"credsStore"[[:space:]]*:/ { print $4; exit }' "$CONFIG_FILE")
        CREDS_HELPER=""
    fi

    # Credential helpers (Docker Desktop on Win/Mac, pass on Linux, ...)
    # store the secret OUTSIDE config.json — the inline "auth" field stays
    # empty by design. Probe the helper directly to confirm a usable
    # credential is present, the same way `docker push` resolves it at
    # runtime.
    if [[ -z "$GHCR_AUTH" ]]; then
        helper="${CREDS_HELPER:-$CREDS_STORE}"
        if [[ -n "$helper" ]] && command -v "docker-credential-$helper" >/dev/null 2>&1; then
            if echo "ghcr.io" | "docker-credential-$helper" get 2>/dev/null | grep -q '"Secret"'; then
                yellow "    auth via credential helper '$helper' (creds resolved for ghcr.io)"
                GHCR_AUTH="<helper:$helper>"
            fi
        fi
    fi

    if [[ -z "$GHCR_AUTH" ]]; then
        red "    no ghcr.io credentials in $CONFIG_FILE (and no working credential helper)"
        login_hint
        exit 1
    fi

    BUILDER_FLAG=(--builder "$BUILDER_NAME")
    PLATFORM_FLAG=(--platform "$MULTIARCH_PLATFORMS")
    OUTPUT_FLAG="--push"
fi

# Sanity-check we're at the repo root with the expected layout.
[[ -f "$REPO_ROOT/release/Dockerfile"            ]] || die "missing release/Dockerfile"
[[ -f "$REPO_ROOT/release/Dockerfile.ui"         ]] || die "missing release/Dockerfile.ui"
[[ -f "$REPO_ROOT/release/Dockerfile.clickhouse" ]] || die "missing release/Dockerfile.clickhouse"
[[ -d "$REPO_ROOT/arachna-trace-shared"               ]] || die "missing arachna-trace-shared/ source"
[[ -d "$REPO_ROOT/arachna-trace-agents/jvm"           ]] || die "missing arachna-trace-agents/jvm/ source"
[[ -d "$REPO_ROOT/arachna-trace-jvm-extensions"       ]] || die "missing arachna-trace-jvm-extensions/ source"
[[ -d "$REPO_ROOT/arachna-trace-infra"                ]] || die "missing arachna-trace-infra/ source"
[[ -d "$REPO_ROOT/arachna-trace-demos/jvm"            ]] || die "missing arachna-trace-demos/jvm/ source"
[[ -d "$REPO_ROOT/arachna-trace-ui"                   ]] || die "missing arachna-trace-ui/ source"

green "    environment OK"

# ---------------------------------------------------------------- confirm
echo ""
if [[ "$NO_PUSH" == "yes" ]]; then
    echo "About to BUILD (host arch, no push):"
else
    echo "About to BUILD and PUSH ($MULTIARCH_PLATFORMS):"
fi
for entry in "${IMAGES[@]}"; do
    name="${entry%%:*}"
    echo "    $REGISTRY/$NAMESPACE/$name:$VERSION (and :latest)"
done
echo ""

if [[ "$SKIP_PROMPT" != "yes" && -t 0 ]]; then
    read -r -p "Proceed? [y/N] " ans
    case "$ans" in
        y|Y|yes|YES) ;;
        *) yellow "aborted by user"; exit 0 ;;
    esac
fi

# ---------------------------------------------------------------- phase 2+3: build & (push|load)
if [[ "$NO_PUSH" == "yes" ]]; then
    say "Phase 2: building $VERSION locally (--load)"
else
    say "Phase 2/3: building and pushing $VERSION (and :latest) to $REGISTRY/$NAMESPACE/*"
fi

for entry in "${IMAGES[@]}"; do
    IFS=':' read -r name dockerfile target <<<"$entry"
    image="$REGISTRY/$NAMESPACE/$name"
    target_args=()
    [[ -n "$target" ]] && target_args=(--target "$target")

    say "  building $name (target=${target:-<default>})"
    docker buildx build \
        "${BUILDER_FLAG[@]}" \
        "${PLATFORM_FLAG[@]}" \
        --file "$dockerfile" \
        "${target_args[@]}" \
        --tag "$image:$VERSION" \
        --tag "$image:latest" \
        $OUTPUT_FLAG \
        .
done

if [[ "$NO_PUSH" == "yes" ]]; then
    green "All images built locally:"
    for entry in "${IMAGES[@]}"; do
        name="${entry%%:*}"
        echo "    $REGISTRY/$NAMESPACE/$name:$VERSION"
    done
    cat <<EOF

To smoke-test the stack against the locally-built images:

  cd release
  docker compose up

The compose file references the same image names — Docker uses your local
copies before consulting GHCR.

EOF
    exit 0
fi

green "All images pushed:"
for entry in "${IMAGES[@]}"; do
    name="${entry%%:*}"
    echo "    $REGISTRY/$NAMESPACE/$name:$VERSION"
done

cat <<EOF

If this is the first time you pushed any of these image names, GHCR
created the package as PRIVATE by default. Anonymous \`docker pull\`
from \`docker compose up\` will fail until you flip them to public:

  GitHub → your profile → Packages → click each package → Package settings
  → Change visibility → Public

Or via gh CLI:

  for n in arachna-trace-collector arachna-trace-processor arachna-trace-query \\
           arachna-trace-demo-spring-boot arachna-trace-clickhouse arachna-trace-ui; do
    gh api -X PATCH /user/packages/container/\$n -f visibility=public
  done

EOF
