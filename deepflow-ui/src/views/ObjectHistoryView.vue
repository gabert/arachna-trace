<script setup>
import { onMounted, ref, watch } from 'vue';
import DataTable from 'primevue/datatable';
import Column from 'primevue/column';
import { api } from '../api/client.js';

const props = defineProps({ objectId: { type: String, required: true } });

const rows = ref([]);
const error = ref(null);

async function load() {
  try {
    rows.value = await api.objectHistory(props.objectId);
  } catch (e) {
    error.value = e.message;
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
