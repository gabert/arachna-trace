<script setup>
import { computed, inject, ref, watch } from 'vue';
import JsonTree from './JsonTree.vue';

const props = defineProps({
  data:   { required: true },
  callId: { type: String, required: true },
  kind:   { type: String, required: true }
});

const emit = defineEmits(['pin']);

// Sole owner of expansion state for this payload's tree. Path keys
// are JSON-stringified arrays; the root path is "[]".
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

// Highlight is a single ref shared across all viewers. Each viewer
// only forwards a non-null pathKey to its JsonTree subtree when the
// global highlight's (callId, kind) match its own — that way only
// the right subtree ever sees a non-null highlight prop and renders
// the flash class.
const highlight = inject('highlight', ref(null));

const localHighlightPathKey = computed(() => {
  const h = highlight.value;
  if (!h) return null;
  if (h.callId !== props.callId) return null;
  if (h.kind !== props.kind) return null;
  return h.pathKey;
});

// When a navigation lands in this viewer, expand every prefix on the
// path so the target node mounts. JsonTree's own isMatch watcher will
// then scroll it into view.
watch(localHighlightPathKey, (key) => {
  if (!key) return;
  let arr;
  try { arr = JSON.parse(key); } catch (_) { return; }
  if (!Array.isArray(arr)) return;
  const next = new Set(expandedKeys.value);
  for (let i = 0; i <= arr.length; i++) {
    next.add(JSON.stringify(arr.slice(0, i)));
  }
  expandedKeys.value = next;
}, { immediate: true });

function onFollowCycle(targetId) {
  // Cycle ref points to an ancestor envelope by object_id. Walk our
  // own subtree to find its path, then highlight it as if a watch
  // row had been clicked.
  const path = findPathToObjectId(props.data, targetId, []);
  if (!path) return;
  highlight.value = {
    callId: props.callId,
    kind: props.kind,
    pathKey: JSON.stringify(path)
  };
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
</script>

<template>
  <JsonTree :data="data"
            :path="[]"
            :isExpanded="isExpanded"
            :envContext="null"
            :highlightedPathKey="localHighlightPathKey"
            @toggle="onToggle"
            @pin="(p) => emit('pin', p)"
            @follow-cycle="onFollowCycle" />
</template>
