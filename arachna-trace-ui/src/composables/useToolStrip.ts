import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';

export type ToolId = 'mutations' | 'watch' | 'origin' | 'search';

export interface ToolMeta { label: string; icon: string }

export interface UseToolStripArgs {
  // Live counts for the badge on each tool icon. Read by the badge
  // computed; the view passes whatever its data sources expose.
  badges: ComputedRef<Record<ToolId, number>>;
}

export interface UseToolStrip {
  // null = collapsed (icons only). Toggling the same id collapses it.
  activeTool: Ref<ToolId | null>;
  toggleTool: (id: ToolId) => void;
  setActiveTool: (id: ToolId | null) => void;

  // User-resizable width of the content panel; persisted to
  // localStorage. Min/max clamped on read and on every drag tick.
  toolWidth: Ref<number>;
  onResizeStart: (e: MouseEvent) => void;

  toolMeta: Record<ToolId, ToolMeta>;
  toolIds: ToolId[];
  toolBadge: ComputedRef<Record<ToolId, number>>;
  activeToolLabel: ComputedRef<string>;
}

const TOOL_WIDTH_KEY = 'arachna-trace-tool-content-width';
const TOOL_WIDTH_DEFAULT = 360;
const TOOL_WIDTH_MIN = 220;
const TOOL_WIDTH_MAX = 1100;

const TOOL_META: Record<ToolId, ToolMeta> = {
  mutations: { label: 'Mutations', icon: '⟳' },
  watch:     { label: 'Watches',   icon: '⊙' },
  origin:    { label: 'Origin',    icon: '↤' },
  search:    { label: 'Search',    icon: '⌕' }
};
const TOOL_IDS: ToolId[] = ['mutations', 'watch', 'origin', 'search'];

function readStoredToolWidth(): number {
  try {
    const v = localStorage.getItem(TOOL_WIDTH_KEY);
    if (!v) return TOOL_WIDTH_DEFAULT;
    const n = parseInt(v, 10);
    if (!Number.isFinite(n)) return TOOL_WIDTH_DEFAULT;
    return Math.min(TOOL_WIDTH_MAX, Math.max(TOOL_WIDTH_MIN, n));
  } catch { return TOOL_WIDTH_DEFAULT; }
}

export function useToolStrip(args: UseToolStripArgs): UseToolStrip {
  const activeTool = ref<ToolId | null>(null);
  const toolWidth = ref<number>(readStoredToolWidth());

  function toggleTool(id: ToolId): void {
    activeTool.value = activeTool.value === id ? null : id;
  }
  function setActiveTool(id: ToolId | null): void {
    activeTool.value = id;
  }

  // Drag state lives in the closure rather than refs — the values are
  // only meaningful for the duration of a single drag and never read
  // by the template.
  let dragStartX = 0;
  let dragStartWidth = 0;

  function onResizeMove(e: MouseEvent): void {
    // Panel sits on the right; dragging the handle leftward should
    // widen the panel, so subtract the cursor delta.
    const delta = dragStartX - e.clientX;
    const next = dragStartWidth + delta;
    toolWidth.value = Math.min(TOOL_WIDTH_MAX, Math.max(TOOL_WIDTH_MIN, next));
  }
  function onResizeEnd(): void {
    document.removeEventListener('mousemove', onResizeMove);
    document.removeEventListener('mouseup', onResizeEnd);
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
    try { localStorage.setItem(TOOL_WIDTH_KEY, String(toolWidth.value)); } catch { /* quota / private mode */ }
  }
  function onResizeStart(e: MouseEvent): void {
    dragStartX = e.clientX;
    dragStartWidth = toolWidth.value;
    document.addEventListener('mousemove', onResizeMove);
    document.addEventListener('mouseup', onResizeEnd);
    // Suppress text selection and lock the cursor so the drag feels
    // like a UI gesture rather than a stray drag-select on the page.
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
  }

  const activeToolLabel = computed(() =>
    activeTool.value ? TOOL_META[activeTool.value].label : '');

  return {
    activeTool,
    toggleTool,
    setActiveTool,
    toolWidth,
    onResizeStart,
    toolMeta: TOOL_META,
    toolIds: TOOL_IDS,
    toolBadge: args.badges,
    activeToolLabel
  };
}
