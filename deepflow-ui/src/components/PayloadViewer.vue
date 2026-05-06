<script setup>
import { computed, inject, provide, ref, watch } from 'vue';
import JsonTree from './JsonTree.vue';
import { findPathToObjectId } from '../util/envelope.js';
import {
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID,
  MUTATED_OBJECT_IDS, ADDED_OBJECT_IDS,
  HIGHLIGHT
} from '../keys.js';

const props = defineProps({
  data:   { required: true },
  callId: { type: String, required: true },
  kind:   { type: String, required: true }
});

// Scope the per-call mutation/added id sets down to this viewer.
// Only AX viewers expose them — AR/TI/RE never carry these marks.
// JsonTree injects each set and marks any envelope row whose object_id
// is in it.
const mutatedObjectsByCallId = inject(MUTATED_OBJECTS_BY_CALL_ID, ref(new Map()));
const mutatedObjectIds = computed(() => {
  if (props.kind !== 'AX') return null;
  return mutatedObjectsByCallId.value.get(props.callId) || null;
});
provide(MUTATED_OBJECT_IDS, mutatedObjectIds);

const addedObjectsByCallId = inject(ADDED_OBJECTS_BY_CALL_ID, ref(new Map()));
const addedObjectIds = computed(() => {
  if (props.kind !== 'AX') return null;
  return addedObjectsByCallId.value.get(props.callId) || null;
});
provide(ADDED_OBJECT_IDS, addedObjectIds);

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
const highlight = inject(HIGHLIGHT, ref(null));

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
  const path = findPathToObjectId(props.data, targetId);
  if (!path) return;
  highlight.value = {
    callId: props.callId,
    kind: props.kind,
    pathKey: JSON.stringify(path)
  };
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
