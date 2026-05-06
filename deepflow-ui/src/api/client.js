const BASE = '/api';

async function request(path) {
  const res = await fetch(BASE + path, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
  }
  return res.json();
}

export const api = {
  health: () => request('/health'),
  listSessions: () => request('/sessions'),
  listThreads: (sessionId) => request(`/sessions/${encodeURIComponent(sessionId)}/threads`),
  listRequests: (sessionId) => request(`/sessions/${encodeURIComponent(sessionId)}/requests`),
  callTree: (sessionId, { thread, requestId } = {}) => {
    const qs = new URLSearchParams();
    if (thread) qs.set('thread', thread);
    if (requestId != null) qs.set('request_id', requestId);
    const suffix = qs.toString() ? `?${qs}` : '';
    return request(`/sessions/${encodeURIComponent(sessionId)}/calltree${suffix}`);
  },
  callPayloads: (callId) => request(`/calls/${encodeURIComponent(callId)}/payloads`),
  requestPayloads: (sessionId, requestId) =>
    request(`/sessions/${encodeURIComponent(sessionId)}/requests/${encodeURIComponent(requestId)}/payloads`),
  objectHistory: (objectId) => request(`/objects/${encodeURIComponent(objectId)}/history`),
  analysisMutations: (sessionId, requestId) => {
    const qs = new URLSearchParams();
    qs.set('session_id', sessionId);
    qs.set('request_id', requestId);
    return request(`/analysis/mutations?${qs}`);
  }
};
