import { useState, useRef, useCallback, useEffect } from 'react';
import { TOOLTIP_WIDTH, TOOLTIP_GAP, TOOLTIP_HEIGHT_ESTIMATE } from '@/constants/ui';

/**
 * Всплывающая карточка над ссылкой в отрендеренном markdown: задержки показа и
 * скрытия, позиция у ссылки и переход мыши со ссылки на карточку без мигания.
 * Общая для всех видов ссылок, у которых карточка есть (документ, файл, коммит):
 * вид ссылки решает, что в карточке, а не как она появляется.
 *
 * Карточка меняет высоту, когда приезжает содержимое, поэтому вызывающий зовёт
 * `calcPos` из своего эффекта по ответу превью: знать, что именно он грузит,
 * этому хуку незачем.
 */
export default function useLinkTooltip() {
  const [visible, setVisible] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const enterTimer = useRef(null);
  const leaveTimer = useRef(null);
  const linkRef = useRef(null);
  const tooltipRef = useRef(null);

  const calcPos = useCallback(() => {
    if (!linkRef.current) return;
    const rect = linkRef.current.getBoundingClientRect();
    const left = Math.min(Math.max(rect.left, TOOLTIP_GAP), window.innerWidth - TOOLTIP_WIDTH - TOOLTIP_GAP);
    const tooltipH = tooltipRef.current ? tooltipRef.current.offsetHeight : TOOLTIP_HEIGHT_ESTIMATE;
    const spaceBelow = window.innerHeight - rect.bottom - TOOLTIP_GAP;

    const top =
      spaceBelow >= tooltipH || spaceBelow >= rect.top - TOOLTIP_GAP
        ? rect.bottom + TOOLTIP_GAP
        : rect.top - tooltipH - TOOLTIP_GAP;

    setPos({ top, left });
  }, []);

  const onMouseEnter = useCallback(() => {
    clearTimeout(leaveTimer.current);
    enterTimer.current = setTimeout(() => {
      calcPos();
      setVisible(true);
    }, 180);
  }, [calcPos]);

  const onMouseLeave = useCallback(() => {
    clearTimeout(enterTimer.current);
    leaveTimer.current = setTimeout(() => setVisible(false), 200);
  }, []);

  const keepOpen = useCallback(() => clearTimeout(leaveTimer.current), []);

  // Спрятать сразу: ссылка открывает модалку или уводит в другой раздел, и
  // карточка, оставшись смонтированной под оверлеем, «выглядывала» бы после.
  const hide = useCallback(() => {
    clearTimeout(enterTimer.current);
    setVisible(false);
  }, []);

  useEffect(
    () => () => {
      clearTimeout(enterTimer.current);
      clearTimeout(leaveTimer.current);
    },
    [],
  );

  return { visible, pos, linkRef, tooltipRef, calcPos, onMouseEnter, onMouseLeave, keepOpen, hide };
}

/** Клик, который должен обработать сам браузер (новая вкладка/окно), а не SPA. */
export function isBrowserClick(e) {
  return e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0;
}
