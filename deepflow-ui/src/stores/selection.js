import { defineStore } from 'pinia';

export const useSelectionStore = defineStore('selection', {
  state: () => ({
    selectedSessionId: null,
    selectedThread: null,
    selectedCallId: null,
    objectHistoryId: null
  }),
  actions: {
    selectSession(id) { this.selectedSessionId = id; },
    selectThread(name) { this.selectedThread = name; },
    selectCall(id) { this.selectedCallId = id; },
    openObjectHistory(id) { this.objectHistoryId = id; }
  }
});
