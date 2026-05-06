<script setup>
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import { api } from '../api/client.js';

const router = useRouter();

// --- list state ---------------------------------------------------------

const sessions = ref([]);
const loading = ref(true);
const error = ref(null);

// --- preview (right pane) state -----------------------------------------

const selected = ref(null);
const previewRequests = ref([]);
const loadingPreview = ref(false);
const previewError = ref(null);

async function loadList() {
  loading.value = true;
  error.value = null;
  try {
    sessions.value = await api.listSessions();
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
onMounted(loadList);

async function loadPreview(sessionId) {
  loadingPreview.value = true;
  previewError.value = null;
  previewRequests.value = [];
  try {
    previewRequests.value = await api.listRequests(sessionId);
  } catch (e) {
    previewError.value = e.message;
  } finally {
    loadingPreview.value = false;
  }
}

watch(selected, (row) => {
  if (row) loadPreview(row.session_id);
  else { previewRequests.value = []; previewError.value = null; }
});

function open(row) {
  if (!row) return;
  router.push({ name: 'session-detail', params: { sessionId: row.session_id } });
}
</script>

<template>
  <section class="sessions-view">
    <header class="sv-head">
      <h1 class="page-title">Sessions</h1>
      <Button icon="pi pi-refresh" text @click="loadList" :loading="loading" aria-label="Refresh" />
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <Splitter class="workspace" stateKey="deepflow-sessions-splitter" stateStorage="local">
      <SplitterPanel :size="45" :minSize="25" class="left-pane">
        <DataTable :value="sessions"
                   :loading="loading"
                   stripedRows
                   selectionMode="single"
                   v-model:selection="selected"
                   dataKey="session_id"
                   @row-dblclick="(e) => open(e.data)"
                   class="sessions-table">
          <Column field="session_id" header="Session" />
          <Column field="agent_run_id" header="Agent run" />
          <Column field="last_seen" header="Last seen" />
          <Column field="retain" header="Retain" />
        </DataTable>
      </SplitterPanel>

      <SplitterPanel :size="55" :minSize="25" class="right-pane">
        <div v-if="!selected" class="empty">
          <p class="muted">Select a session to preview its requests.</p>
          <p class="muted small">Double-click a row or use the button below to open the full session view.</p>
        </div>

        <div v-else class="preview">
          <header class="preview-head">
            <div class="meta">
              <div><span class="label">session</span> <code>{{ selected.session_id }}</code></div>
              <div><span class="label">agent run</span> <code>{{ selected.agent_run_id }}</code></div>
              <div class="row-times">
                <span><span class="label">first</span> <code>{{ selected.first_seen }}</code></span>
                <span><span class="label">last</span> <code>{{ selected.last_seen }}</code></span>
                <span v-if="selected.retain" class="badge">retain</span>
              </div>
            </div>
            <Button label="Open session" icon="pi pi-arrow-right" @click="open(selected)" />
          </header>

          <Message v-if="previewError" severity="error" :closable="false">{{ previewError }}</Message>

          <div v-if="loadingPreview" class="centered">
            <ProgressSpinner style="width:1.75rem;height:1.75rem" />
          </div>

          <DataTable v-else
                     :value="previewRequests"
                     stripedRows
                     selectionMode="single"
                     dataKey="request_id"
                     @row-select="(e) => open(selected)"
                     class="requests-table"
                     :rows="50"
                     scrollable
                     scrollHeight="flex">
            <Column field="request_id" header="#" style="width:5rem">
              <template #body="{ data }"><strong>#{{ data.request_id }}</strong></template>
            </Column>
            <Column field="thread_name" header="Thread" />
            <Column field="call_count" header="Calls" style="width:6rem" />
            <Column field="span_ms" header="ms" style="width:6rem" />
          </DataTable>

          <p v-if="!loadingPreview && !previewRequests.length" class="muted centered">
            no requests in this session
          </p>
        </div>
      </SplitterPanel>
    </Splitter>
  </section>
</template>

<style scoped>
.sessions-view {
  display: flex; flex-direction: column;
  height: calc(100vh - 60px);
  background: var(--bg-base);
}

.sv-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.page-title { margin: 0; font-size: 1rem; color: var(--text-primary); }

.workspace {
  flex: 1; overflow: hidden;
  border-radius: 0; border: 0;
  background: var(--bg-base);
}
:deep(.left-pane)  { overflow-y: auto !important; padding: 0; background: var(--bg-base); }
:deep(.right-pane) { overflow-y: auto !important; padding: 0; background: var(--bg-base); }
:deep(.p-splitter-gutter) { background: var(--border) !important; width: 6px !important; }
:deep(.p-splitter-gutter:hover),
:deep(.p-splitter-gutter.p-splitter-gutter-resizing) { background: var(--accent-blue) !important; }
:deep(.p-splitter-gutter-handle) { background: var(--text-muted) !important; }

/* Preview pane */
.preview { display: flex; flex-direction: column; height: 100%; }
.preview-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
}
.meta { display: flex; flex-direction: column; gap: 0.35rem; font-size: 0.85rem; }
.label { color: var(--text-muted); margin-right: 0.4rem; text-transform: uppercase; font-size: 0.7rem; letter-spacing: 0.04em; }
.meta code { color: var(--text-primary); font-family: ui-monospace, monospace; }
.row-times { display: flex; gap: 1rem; flex-wrap: wrap; }
.badge {
  background: var(--bg-elevated); color: var(--text-secondary);
  border: 1px solid var(--border-strong); padding: 0.05rem 0.5rem;
  border-radius: 4px; font-size: 0.7rem; text-transform: uppercase;
}

.empty { padding: 2rem 1.25rem; }
.small { font-size: 0.8rem; }
.muted { color: var(--text-muted); }
.centered { display: flex; justify-content: center; padding: 1.5rem; }

:deep(.sessions-table .p-datatable-wrapper),
:deep(.requests-table .p-datatable-wrapper) {
  background: var(--bg-base);
}
</style>
