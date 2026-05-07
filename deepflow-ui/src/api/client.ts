import type {
  CallRow,
  MutationsResponse,
  ObjectHistoryRow,
  PayloadRow,
  RequestRow,
  SessionRow,
  SessionSize,
  ThreadRow,
  ValueSearchHit
} from '../types';

const BASE = '/api';

async function request<T>(path: string): Promise<T> {
  const res = await fetch(BASE + path, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
  }
  return res.json() as Promise<T>;
}

export interface CallTreeQuery {
  thread?: string;
  requestId?: number | string | null;
}

export const api = {
  health: (): Promise<{ status: string }> =>
    request('/health'),
  listSessions: (): Promise<SessionRow[]> =>
    request('/sessions'),
  listThreads: (sessionId: string): Promise<ThreadRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/threads`),
  listRequests: (sessionId: string): Promise<RequestRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/requests`),
  sessionSize: (sessionId: string): Promise<SessionSize> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/size`),
  callTree: (sessionId: string, q: CallTreeQuery = {}): Promise<CallRow[]> => {
    const qs = new URLSearchParams();
    if (q.thread) qs.set('thread', q.thread);
    if (q.requestId != null) qs.set('request_id', String(q.requestId));
    const suffix = qs.toString() ? `?${qs}` : '';
    return request(`/sessions/${encodeURIComponent(sessionId)}/calltree${suffix}`);
  },
  callPayloads: (callId: string): Promise<PayloadRow[]> =>
    request(`/calls/${encodeURIComponent(callId)}/payloads`),
  requestPayloads: (sessionId: string, requestId: number | string): Promise<PayloadRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/requests/${encodeURIComponent(String(requestId))}/payloads`),
  objectHistory: (objectId: string | number): Promise<ObjectHistoryRow[]> =>
    request(`/objects/${encodeURIComponent(String(objectId))}/history`),
  analysisMutations: (sessionId: string, requestId: number | string): Promise<MutationsResponse> => {
    const qs = new URLSearchParams();
    qs.set('session_id', sessionId);
    qs.set('request_id', String(requestId));
    return request(`/analysis/mutations?${qs}`);
  },
  valueSearch: (
    sessionId: string,
    requestId: number | string | null,
    value: string,
    mode: 'exact' | 'substring' = 'exact'
  ): Promise<ValueSearchHit[]> => {
    const qs = new URLSearchParams();
    qs.set('session_id', sessionId);
    if (requestId != null) qs.set('request_id', String(requestId));
    qs.set('value', value);
    if (mode === 'substring') qs.set('mode', 'substring');
    return request(`/analysis/value-search?${qs}`);
  }
};
