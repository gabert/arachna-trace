<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import { api } from '../api/client';
import type { ObjectHistoryRow } from '../types';

const props = defineProps<{ objectId: string }>();

const rows = ref<ObjectHistoryRow[]>([]);
const error = ref<string | null>(null);

async function load(): Promise<void> {
  try {
    rows.value = await api.objectHistory(props.objectId);
  } catch (e) {
    error.value = (e as Error).message;
  }
}
onMounted(load);
watch(() => props.objectId, load);
</script>

<template>
  <section class="view">
    <header class="view-header">
      <h1>Object {{ objectId }}</h1>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <DataTable :value="rows" stripedRows>
      <Column field="ts_in" header="Time" />
      <Column field="kind" header="Kind" />
      <Column field="signature" header="Method" />
      <Column field="root_hash" header="Hash" />
      <Column field="session_id" header="Session" />
    </DataTable>
  </section>
</template>
