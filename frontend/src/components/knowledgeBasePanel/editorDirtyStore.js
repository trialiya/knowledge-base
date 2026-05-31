/**
 * Module-level "dirty" registry for the Markdown editor(s).
 *
 * The detail panel renders <DocumentDetail key={node.id} />, so switching
 * documents UNMOUNTS the editor — its internal `dirty` state is gone by the
 * time navigation decisions are made. We therefore lift the flag here, to a
 * tiny shared store (same idiom as the module cache in useDocPreview.js), so
 * the navigation guard in useKnowledgeBase can read it BEFORE selecting a new
 * node.
 *
 * Keyed by instance id rather than a single boolean: an inline editor and a
 * fullscreen "expand" editor can be mounted at the same time, and a fresh
 * (clean) instance must NOT clobber another instance's unsaved state. Dirty
 * means "any registered editor has unsaved changes".
 */

const dirtySources = new Set();
const listeners = new Set();

function emit() {
  const dirty = dirtySources.size > 0;
  listeners.forEach((cb) => cb(dirty));
}

/** Mark/unmark one editor instance (by id) as having unsaved changes. */
export function setEditorDirty(id, value) {
  const before = dirtySources.size > 0;
  if (value) dirtySources.add(id);
  else dirtySources.delete(id);
  if (before !== dirtySources.size > 0) emit();
}

/** Drop all dirty marks (e.g. after the user confirms discarding). */
export function clearEditorDirty() {
  if (dirtySources.size === 0) return;
  dirtySources.clear();
  emit();
}

export function isEditorDirty() {
  return dirtySources.size > 0;
}

/** Subscribe to changes; returns an unsubscribe fn. */
export function subscribeEditorDirty(cb) {
  listeners.add(cb);
  return () => listeners.delete(cb);
}
