import { computed } from 'vue';
import { walkEnvelopes } from '../util/envelope.js';

// Per-call AR↔AX analysis. For each (callId) where both AR and AX
// payloads are loaded, derive:
//
//   mutated  — Set<objectId> whose own_hash moved between AR and AX
//   added    — Set<objectId> present in AX but absent from AR
//
// These drive (a) the in-tree per-envelope decorations rendered by
// JsonTree (scoped down to AX-only by PayloadViewer) and (b) the AX
// header chip badges in FrameCard.
//
// Cheap: one walk per loaded payload, recomputed only when the
// payloadsByCallId map identity changes (i.e. when a new request
// loads).
export function useArAxAnalysis(payloadsByCallId) {
  const arAxAnalysisByCallId = computed(() => {
    const out = new Map();
    for (const [callId, payloads] of payloadsByCallId.value) {
      const ar = payloads.find(p => p.kind === 'AR');
      const ax = payloads.find(p => p.kind === 'AX');
      if (!ar?.parsed || !ax?.parsed) continue;
      const arHashes = collectOwnHashes(ar.parsed);
      const axHashes = collectOwnHashes(ax.parsed);
      const mutated = new Set();
      const added = new Set();
      for (const [id, ohAX] of axHashes) {
        const ohAR = arHashes.get(id);
        if (ohAR === undefined) added.add(id);
        else if (ohAR !== ohAX) mutated.add(id);
      }
      if (mutated.size > 0 || added.size > 0) {
        out.set(callId, { mutated, added });
      }
    }
    return out;
  });

  const mutatedObjectsByCallId = computed(() => {
    const m = new Map();
    for (const [k, v] of arAxAnalysisByCallId.value) m.set(k, v.mutated);
    return m;
  });

  const addedObjectsByCallId = computed(() => {
    const m = new Map();
    for (const [k, v] of arAxAnalysisByCallId.value) m.set(k, v.added);
    return m;
  });

  return { mutatedObjectsByCallId, addedObjectsByCallId };
}

// Walk the payload, collecting first-seen own_hash per envelope id.
// First-seen wins because the same id can appear multiple times in one
// payload (a single instance referenced from multiple fields), and all
// occurrences share the same own_hash.
function collectOwnHashes(root) {
  const out = new Map();
  walkEnvelopes(root, (env) => {
    const meta = env.__meta__;
    if (meta.own_hash && !out.has(meta.id)) {
      out.set(meta.id, meta.own_hash);
    }
  });
  return out;
}
