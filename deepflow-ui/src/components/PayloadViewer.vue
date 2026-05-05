<script setup>
import { ref, nextTick } from 'vue';
import JsonTree from './JsonTree.vue';

const props = defineProps({
  data: { required: true },
  rootRef: { type: HTMLElement, default: null }
});

const emit = defineEmits(['pin']);

// Sole owner of expansion state for this payload's tree. Path keys are
// JSON-serialised arrays; the root path is "[]". Two top levels open
// by default so the user can see the envelope's first layer of fields.
const expandedKeys = ref(new Set(['[]']));

// Pre-open the immediate children of an array root, so an `RE` payload
// of shape `[envelope]` doesn't render as a single one-liner.
function preopenRoot() {
  if (Array.isArray(props.data)) {
    expandedKeys.value.add('[]');
    for (let i = 0; i < props.data.length; i++) {
      expandedKeys.value.add(JSON.stringify([i]));
    }
  }
}
preopenRoot();

function isExpanded(key) {
  return expandedKeys.value.has(key);
}

function onToggle(key) {
  const next = new Set(expandedKeys.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  expandedKeys.value = next;
}

const rootEl = ref(null);

/**
 * Imperatively reveal a node at the given path: expand every prefix,
 * scroll the node into view, and add a transient `flashed` class so the
 * eye finds it. Pure DOM after the Vue commit — no global reactive
 * cascade, no shared highlight prop.
 */
async function revealPath(pathArr) {
  const next = new Set(expandedKeys.value);
  for (let i = 0; i <= pathArr.length; i++) {
    next.add(JSON.stringify(pathArr.slice(0, i)));
  }
  expandedKeys.value = next;

  await nextTick();

  if (!rootEl.value) return;
  const targetKey = JSON.stringify(pathArr);
  const escaped = targetKey.replace(/"/g, '\\"');
  const el = rootEl.value.querySelector(`[data-path="${escaped}"]`);
  if (!el) return;

  el.scrollIntoView({ block: 'center' });

  // Toggle the flash class. We replay it on the same element so a quick
  // succession of reveals on the same node still animates.
  el.classList.remove('flashed');
  // Force reflow before re-adding so the transition restarts.
  void el.offsetWidth;
  el.classList.add('flashed');
}

function onFollowCycle(targetId) {
  // Cycle ref points to an ancestor envelope by object_id. Walk our
  // own subtree until we find it; the cycle target is by definition
  // already in the open subtree above this point.
  const path = findPathToObjectId(props.data, targetId, []);
  if (path) revealPath(path);
}

function findPathToObjectId(node, targetId, currentPath) {
  if (node == null || typeof node !== 'object') return null;
  if (node.__meta__ && node.__meta__.id === targetId) return currentPath;
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      const r = findPathToObjectId(node[i], targetId, [...currentPath, i]);
      if (r) return r;
    }
    return null;
  }
  for (const k of Object.keys(node)) {
    if (k === '__meta__') continue;
    const r = findPathToObjectId(node[k], targetId, [...currentPath, k]);
    if (r) return r;
  }
  return null;
}

defineExpose({ revealPath });
</script>

<template>
  <div ref="rootEl" class="payload-viewer">
    <JsonTree :data="data"
              :path="[]"
              :isExpanded="isExpanded"
              :envContext="null"
              @toggle="onToggle"
              @pin="(p) => emit('pin', p)"
              @follow-cycle="onFollowCycle" />
  </div>
</template>

<style scoped>
.payload-viewer { /* container only — JsonTree owns layout */ }
</style>
