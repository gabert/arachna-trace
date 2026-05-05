<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import Button from 'primevue/button';
import Message from 'primevue/message';
import { api } from '../api/client.js';

const router = useRouter();
const sessions = ref([]);
const loading = ref(true);
const error = ref(null);

async function load() {
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
onMounted(load);

function open(row) {
  router.push({ name: 'session-detail', params: { sessionId: row.session_id } });
}
</script>

<template>
  <section class="view">
    <header class="view-header">
      <h1>Sessions</h1>
      <Button icon="pi pi-refresh" text @click="load" :loading="loading" aria-label="Refresh" />
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <DataTable :value="sessions" :loading="loading" stripedRows
               selectionMode="single" @row-select="(e) => open(e.data)"
               dataKey="session_id">
      <Column field="session_id" header="Session" />
      <Column field="agent_run_id" header="Agent run" />
      <Column field="first_seen" header="First seen" />
      <Column field="last_seen" header="Last seen" />
      <Column field="retain" header="Retain" />
    </DataTable>
  </section>
</template>
