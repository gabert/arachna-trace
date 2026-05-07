<script setup lang="ts">
// Bottom status strip — project name + version on the left, live
// API health indicator on the right, and (when viewing a session)
// the session's payload-bytes footprint in the middle. Polls
// /api/health every 30s so a dropped query backend shows up without
// a manual refresh; refetches session size on session navigation.

import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '../api/client';
import type { SessionSize } from '../types';
import pkg from '../../package.json';

type HealthStatus = 'unknown' | 'ok' | 'error';

const version = pkg.version;
const health = ref<HealthStatus>('unknown');
const lastCheck = ref<Date | null>(null);
let timer: number | null = null;

async function checkHealth(): Promise<void> {
  try {
    await api.health();
    health.value = 'ok';
  } catch {
    health.value = 'error';
  } finally {
    lastCheck.value = new Date();
  }
}

onMounted(() => {
  checkHealth();
  timer = window.setInterval(checkHealth, 30_000);
});

onUnmounted(() => {
  if (timer != null) window.clearInterval(timer);
});

const healthLabel = (h: HealthStatus): string =>
  h === 'ok' ? 'api ok' : h === 'error' ? 'api unreachable' : 'api…';

const healthTitle = (h: HealthStatus): string => {
  const stamp = lastCheck.value ? ` (last check ${lastCheck.value.toLocaleTimeString()})` : '';
  if (h === 'ok') return `Query API responded successfully${stamp}`;
  if (h === 'error') return `Query API not reachable${stamp}`;
  return 'Checking API…';
};

// Per-session size — only meaningful while viewing a session detail.
const route = useRoute();
const currentSessionId = computed<string | null>(() => {
  if (route.name !== 'session-detail') return null;
  const id = route.params.sessionId;
  return typeof id === 'string' ? id : null;
});

const sessionSize = ref<SessionSize | null>(null);

watch(currentSessionId, async (id) => {
  if (!id) { sessionSize.value = null; return; }
  try {
    sessionSize.value = await api.sessionSize(id);
  } catch {
    sessionSize.value = null;
  }
}, { immediate: true });

function formatBytes(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`;
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

const sessionSizeTitle = computed(() => {
  const s = sessionSize.value;
  if (!s) return '';
  return `Uncompressed JSON payload bytes for this session: ${s.payload_bytes.toLocaleString()} B `
       + `across ${s.payload_rows.toLocaleString()} payloads and ${s.call_rows.toLocaleString()} calls. `
       + `Actual on-disk footprint after ClickHouse compression is roughly 10× smaller.`;
});
</script>

<template>
  <footer class="status-bar" role="contentinfo">
    <div class="status-left">
      <span class="status-brand">DeepFlow</span>
      <span class="status-version">v{{ version }}</span>
    </div>
    <div class="status-mid">
      <span v-if="sessionSize" class="status-session" :title="sessionSizeTitle">
        session
        <strong>{{ formatBytes(sessionSize.payload_bytes) }}</strong>
        <span class="status-session-meta">
          · {{ sessionSize.payload_rows }} payloads · {{ sessionSize.call_rows }} calls
        </span>
      </span>
    </div>
    <div class="status-right">
      <span class="status-health" :class="health" :title="healthTitle(health)">
        <span class="status-dot"></span>
        <span class="status-label">{{ healthLabel(health) }}</span>
      </span>
    </div>
  </footer>
</template>

<style scoped>
.status-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  padding: 0.25rem 0.75rem;
  background: var(--bg-surface);
  border-top: 1px solid var(--border);
  color: var(--text-muted);
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace;
  font-size: 0.7rem;
  letter-spacing: 0.02em;
  user-select: none;
  gap: 1rem;
}

.status-left, .status-right {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-shrink: 0;
}

.status-mid {
  flex: 1;
  display: flex;
  justify-content: center;
  overflow: hidden;
}
.status-session {
  display: inline-flex;
  align-items: baseline;
  gap: 0.4rem;
  cursor: help;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.status-session strong {
  color: var(--text-secondary);
  font-weight: 600;
}
.status-session-meta {
  color: var(--text-muted);
  opacity: 0.8;
}

.status-brand {
  color: var(--text-secondary);
  font-weight: 600;
}
.status-version {
  color: var(--text-muted);
}

.status-health {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  cursor: default;
}

.status-dot {
  width: 0.55em;
  height: 0.55em;
  border-radius: 50%;
  background: var(--text-muted);
  transition: background-color 0.15s, box-shadow 0.15s;
}
.status-health.ok .status-dot {
  background: #6ee7b7;
  box-shadow: 0 0 0 2px rgba(110, 231, 183, 0.18);
}
.status-health.error .status-dot {
  background: var(--accent-red);
  box-shadow: 0 0 0 2px rgba(248, 113, 113, 0.22);
}

.status-health.ok .status-label    { color: #6ee7b7; }
.status-health.error .status-label { color: var(--accent-red); }
</style>
