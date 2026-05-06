// Formatting helpers for displaying envelopes, signatures, hashes
// and timestamps in compact rows. Pure functions; no Vue.

import { isEnvelope, isCycleRef } from './envelope.js';

// Drop the package prefix from a fully-qualified class name.
//   "com.example.BookEntity" -> "BookEntity"
export function shortClass(c) {
  if (!c) return 'Object';
  return String(c).split('.').pop();
}

// Trim a method signature down to "Class.method".
//   "com.example.BookService.normalizeIsbns(java.util.List)"
//     -> "BookService.normalizeIsbns"
export function shortSig(s) {
  if (!s) return '';
  return s.replace(/\(.*$/, '').split('.').slice(-2).join('.');
}

// Truncate a hex hash for display. ∅ for null/empty.
export function shortHash(h) {
  if (!h) return '∅';
  return String(h).slice(0, 16);
}

// Strip the date prefix off a timestamp string and keep HH:MM:SS.mmm.
export function fmtTime(ts) {
  if (!ts) return '';
  return String(ts).replace(/^\d{4}-\d\d-\d\d /, '').slice(0, 12);
}

// Render a single value for inline display in diff rows / watch rows.
// Envelopes collapse to "ClassName #id"; cycles to "↺ #id"; arrays
// to "[len]"; plain objects to "{keyCount}". Primitives JSON-stringify.
export function formatValue(v) {
  if (v === undefined) return '∅';
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (typeof v !== 'object') return String(v);
  if (Array.isArray(v)) return `[${v.length}]`;
  if (isEnvelope(v)) return `${shortClass(v.__meta__.class)} #${v.__meta__.id}`;
  if (isCycleRef(v)) return `↺ #${v.ref_id}`;
  return `{${Object.keys(v).filter(k => k !== '__meta__').length}}`;
}

// Safe JSON.parse; returns the original string on failure (so callers
// who pass already-parsed payloads through don't break) and null for
// null/undefined input.
export function tryParse(s) {
  if (s == null) return null;
  if (typeof s !== 'string') return s;
  try { return JSON.parse(s); } catch (_) { return s; }
}
