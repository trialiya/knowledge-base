/** Nearest scrollable ancestor (the element whose scroll we must move). */
function getScrollParent(el) {
  let node = el.parentElement;
  while (node) {
    const { overflowY } = window.getComputedStyle(node);
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight + 1) {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

/**
 * Scrolls the heading into view by moving its actual scroll container's
 * scrollTop, instead of relying on scrollIntoView() — which, in this layout
 * (body/#root locked with overflow:hidden), may try to scroll a non-scrollable
 * ancestor and visibly do nothing. Falls back to scrollIntoView if no
 * scrollable ancestor is found.
 */
export function scrollToHeading(target) {
  const scroller = getScrollParent(target);
  if (scroller) {
    const tRect = target.getBoundingClientRect();
    const sRect = scroller.getBoundingClientRect();
    const top = scroller.scrollTop + (tRect.top - sRect.top) - 8;
    scroller.scrollTo({ top, behavior: 'smooth' });
  } else {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
