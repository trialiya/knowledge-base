import React, { useCallback, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import useEscape from './useEscape';
import './modalShell.css';

// Stack of currently-open modal instance ids, topmost last. Lets a stacked
// modal's Escape handler ignore the keypress when it isn't the frontmost one,
// so one Escape closes only the modal on top instead of every open modal.
let openStack = [];
let nextId = 0;

function useTopmost(active) {
  const idRef = useRef(null);
  if (idRef.current === null) idRef.current = ++nextId;

  useEffect(() => {
    if (!active) return undefined;
    const id = idRef.current;
    openStack.push(id);
    return () => {
      openStack = openStack.filter((x) => x !== id);
    };
  }, [active]);

  return useCallback(() => openStack[openStack.length - 1] === idRef.current, []);
}

// A mousedown-to-close is followed by a native `click` on mouseup. React flushes the
// mousedown's resulting unmount synchronously (discrete event), so by the time that
// click fires this modal can already be gone — leaving nothing here to stop the click
// from reaching (or bubbling, in React's portal-aware component tree, to) a clickable
// ancestor and instantly reopening what onClose just closed. Swallow that one click in
// the capture phase, before it reaches any target or bubble-phase handler.
function swallowNextClick() {
  const swallow = (e) => e.stopPropagation();
  document.addEventListener('click', swallow, { capture: true, once: true });
  // Safety net: if no click follows this mousedown (e.g. mouseup outside the window),
  // don't leave the listener armed to swallow an unrelated later click.
  setTimeout(() => document.removeEventListener('click', swallow, { capture: true }), 0);
}

/**
 * Shared modal chrome: portal to document.body, overlay, backdrop-close on
 * mousedown (not click, so text selection ending outside doesn't dismiss it),
 * Escape-to-close (only for the topmost modal when stacked), and the dialog
 * role/aria-modal wiring. Components supply only their header/body/footer
 * content as children.
 *
 * Props:
 *   open      — whether the modal is mounted/visible
 *   onClose() — backdrop click / Escape
 *   variant   — 'sm' | 'wide' | 'fullscreen' | undefined (default ~380px)
 *   role      — 'dialog' (default) | 'alertdialog'
 *   className — extra class(es) for the dialog box, for component-specific sizing/chrome
 */
const ModalShell = ({ open = true, onClose, variant, role = 'dialog', className = '', children }) => {
  const isTopmost = useTopmost(open);

  // Stable across renders (empty deps) so useEscape's listener isn't torn down and
  // re-added on every render of the modal's content; latest open/onClose read via refs.
  const openRef = useRef(open);
  openRef.current = open;
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const onEscape = useCallback(() => {
    if (openRef.current && isTopmost()) onCloseRef.current();
  }, [isTopmost]);
  useEscape(onEscape);

  if (!open) return null;

  const overlayClassName = ['modal-shell-overlay', variant && `modal-shell-overlay--${variant}`]
    .filter(Boolean)
    .join(' ');
  const dialogClassName = ['modal-shell', variant && `modal-shell--${variant}`, className].filter(Boolean).join(' ');

  const handleBackdropMouseDown = () => {
    swallowNextClick();
    onClose();
  };

  // onClick stopPropagation here (in addition to onMouseDown-close on the backdrop) matters
  // when a modal is a React child of a clickable ancestor (e.g. a tool-call row that opens
  // its own detail modal): React bubbles synthetic events through the *component* tree even
  // across a portal, so without this, clicking anything inside the dialog — including a
  // close/cancel button whose own onClick just closed it — would also reach the ancestor's
  // onClick and immediately reopen what was just closed.
  return createPortal(
    <div className={overlayClassName} onMouseDown={handleBackdropMouseDown}>
      <div
        className={dialogClassName}
        role={role}
        aria-modal="true"
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>,
    document.body,
  );
};

export default ModalShell;
