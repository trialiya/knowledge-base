import { useEffect, useEffectEvent, useRef } from 'react';
import { hasOpenModal, hasOverlay } from '@/components/common/layout/overlayStack';
import { isFindShortcut, isTypingTarget } from '@/components/common/search/findShortcut';

/**
 * Клавиатура find-бара чата: Ctrl/Cmd+F открывает бар (или фокусирует уже открытый),
 * Escape закрывает. Ref поля бара заводится здесь и возвращается — его вешают на поле,
 * а фокусирует его только этот хук.
 *
 * @param {object} p
 * @param {boolean} p.isActive вкладка «Чат» открыта — иначе шорткаты не перехватываются
 * @param {boolean} p.canSearch в активном чате есть что искать (не черновик, не битый чат)
 * @param {object} p.search результат useInChatSearch
 * @returns {object} ref для поля ввода find-бара
 */
export default function useChatFindShortcut({ isActive, canSearch, search }) {
  const inputRef = useRef(null);

  // Тело шортката — useEffectEvent: слушатель вешается один раз на вкладку, но
  // внутри читает всегда свежие canSearch/search. Держать их в
  // зависимостях эффекта нельзя — объект useInChatSearch пересоздаётся каждый
  // рендер, то есть слушатель переподписывался бы на каждый чанк стриминга.
  // Условия у Ctrl+F и Escape разные, и намеренно — те же, что у бара открытого
  // файла (см. useAddressFind). Escape уступает любому оверлею (им закрывают
  // верхнее, а верхнее сейчас диалог или поповер) и любому полю ввода: в чате
  // Escape ждут отмена инлайн-переименования и @mention-подсказка композера, а
  // наш слушатель на перехвате видит нажатие раньше них (см. isTypingTarget).
  // Ctrl+F уступает только диалогу, у которого есть свой бар (ModalShell →
  // useModalFind); поповер искать не умеет, и уступив ему, мы отдали бы нажатие
  // браузерному поиску по всей странице.
  const onChatSearchKey = useEffectEvent((e) => {
    if (!canSearch) return;
    if (e.key === 'Escape') {
      if (hasOverlay() || isTypingTarget(e)) return;
      if (search.open) search.close();
      return;
    }
    if (hasOpenModal()) return;
    e.preventDefault();
    if (search.open) {
      inputRef.current?.focus();
      inputRef.current?.select();
    } else {
      search.openBar();
    }
  });

  // Ctrl/Cmd+F открывает (или фокусирует уже открытый) find-бар текущего чата —
  // только пока вкладка «Чат» активна, иначе перехватывали бы поиск в других
  // вкладках. Перехват, а не всплытие: свои слушатели оверлеи вешают на
  // всплытие, и к очереди чата меню от этого же Escape уже закрылось бы.
  useEffect(() => {
    if (!isActive) return undefined;
    const onKeyDown = (e) => {
      if (e.key === 'Escape' || isFindShortcut(e)) onChatSearchKey(e);
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [isActive]);

  return inputRef;
}
