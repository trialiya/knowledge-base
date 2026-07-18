import React from 'react';
import { createPortal } from 'react-dom';
import useEscape from './useEscape';
import './modalShell.css';

/**
 * Shared modal chrome: portal to document.body, overlay, backdrop-close on
 * mousedown (not click, so text selection ending outside doesn't dismiss it),
 * Escape-to-close, and the dialog role/aria-modal wiring. Components supply
 * only their header/body/footer content as children (or via the optional
 * `title`/`footer` slots).
 *
 * Props:
 *   open      — whether the modal is mounted/visible
 *   onClose() — backdrop click / Escape
 *   variant   — 'sm' | 'wide' | 'fullscreen' | undefined (default ~380px)
 *   role      — 'dialog' (default) | 'alertdialog'
 *   className — extra class(es) for the dialog box, for component-specific sizing/chrome
 *   title, footer — optional slots rendered around `children`
 */
const ModalShell = ({ open = true, onClose, variant, role = 'dialog', className = '', title, footer, children }) => {
  useEscape(() => {
    if (open) onClose();
  });

  if (!open) return null;

  const overlayClassName = ['modal-shell-overlay', variant && `modal-shell-overlay--${variant}`]
    .filter(Boolean)
    .join(' ');
  const dialogClassName = ['modal-shell', variant && `modal-shell--${variant}`, className].filter(Boolean).join(' ');

  // onClick stopPropagation (in addition to onMouseDown-close) matters when a modal is
  // rendered as a React child of a clickable ancestor (e.g. a tool-call row that opens
  // its own detail modal): React bubbles synthetic events through the *component* tree
  // even across a portal, so without this a click anywhere inside the modal would also
  // reach the ancestor's onClick and immediately reopen what onClose just closed.
  return createPortal(
    <div className={overlayClassName} onMouseDown={onClose} onClick={(e) => e.stopPropagation()}>
      <div className={dialogClassName} role={role} aria-modal="true" onMouseDown={(e) => e.stopPropagation()}>
        {title && <div className="modal-shell__title">{title}</div>}
        {children}
        {footer && <div className="modal-shell__footer">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
};

export default ModalShell;
