// Formatting helpers for displaying envelopes, signatures, hashes
// and timestamps in compact rows. Pure functions; no Vue.

import { isCycleRef, isEnvelope } from './envelope';

// Drop the package prefix from a fully-qualified class name.
//   "com.example.BookEntity" -> "BookEntity"
export function shortClass(c: string | null | undefined): string {
  if (!c) return 'Object';
  return String(c).split('.').pop() ?? 'Object';
}

// Trim a method signature down to "Class.method".
//   "com.example.BookService.normalizeIsbns(java.util.List)"
//     -> "BookService.normalizeIsbns"
export function shortSig(s: string | null | undefined): string {
  if (!s) return '';
  return s.replace(/\(.*$/, '').split('.').slice(-2).join('.');
}

// Truncate a hex hash for display. ∅ for null/empty.
export function shortHash(h: string | null | undefined): string {
  if (!h) return '∅';
  return String(h).slice(0, 16);
}

// Strip the date prefix off a timestamp string and keep HH:MM:SS.mmm.
export function fmtTime(ts: string | null | undefined): string {
  if (!ts) return '';
  return String(ts).replace(/^\d{4}-\d\d-\d\d /, '').slice(0, 12);
}

// Render a single value for inline display in diff rows / watch rows.
// Envelopes collapse to "ClassName #id"; cycles to "↺ #id"; arrays
// to "[len]"; plain objects to "{keyCount}". Primitives JSON-stringify.
export function formatValue(v: unknown): string {
  if (v === undefined) return '∅';
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (typeof v !== 'object') return String(v);
  if (Array.isArray(v)) return `[${v.length}]`;
  if (isEnvelope(v)) return `${shortClass(v.__meta__.class)} #${v.__meta__.id}`;
  if (isCycleRef(v)) return `↺ #${v.ref_id}`;
  const obj = v as Record<string, unknown>;
  return `{${Object.keys(obj).filter(k => k !== '__meta__').length}}`;
}

// Byte count with B / KB / MB / GB suffix. 1024-based, one decimal
// for KB/MB, two for GB. Used wherever payload / session sizes appear
// — the status bar, inspection-card section headers, anywhere else
// raw byte counts would be hard to read at a glance.
export function fmtBytes(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`;
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

// Safe JSON.parse; returns the original input on failure (so callers
// who pass already-parsed payloads through don't break) and null for
// null/undefined input.
export function tryParse<T = unknown>(s: unknown): T | null {
  if (s == null) return null;
  if (typeof s !== 'string') return s as T;
  try { return JSON.parse(s) as T; } catch (_) { return s as unknown as T; }
}
