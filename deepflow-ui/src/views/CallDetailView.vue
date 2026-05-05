<script setup>
import { onMounted, ref, watch } from 'vue';
import { api } from '../api/client.js';

const props = defineProps({
  sessionId: { type: String, required: true },
  callId: { type: String, required: true }
});

const payloads = ref([]);
const error = ref(null);

async function load() {
  try {
    payloads.value = await api.callPayloads(props.callId);
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(load);
watch(() => props.callId, load);
</script>

<template>
  <section class="view">
    <header class="view-header">
      <h1>Call {{ callId }}</h1>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-for="p in payloads" :key="p.kind" class="payload">
      <h3>{{ p.kind }} <small>({{ p.payload_size }} B, hash {{ p.root_hash }})</small></h3>
      <pre>{{ p.payload_json }}</pre>
    </div>
  </section>
</template>
