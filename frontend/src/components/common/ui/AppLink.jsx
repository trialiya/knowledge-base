/** Клик, который должен обработать сам браузер (новая вкладка/окно), а не SPA. */
function isBrowserClick(e) {
  return e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0;
}

/**
 * Ссылка на место внутри приложения: настоящий `href`, чтобы средняя кнопка,
 * Ctrl/Cmd/Shift-клик и «копировать адрес» работали как у браузера, а обычный
 * клик — переход без перезагрузки через `onNavigate(e)`. Средняя кнопка сюда не
 * приходит вовсе (auxclick), её открывает браузер.
 *
 * Остальные пропсы — на `<a>` как есть (`ref`, `className`, `title`, наведение
 * карточки) и после своих: `onClick` вызывающего заменяет переход целиком.
 */
const AppLink = ({ href, onNavigate, children, ...rest }) => {
  const onClick = (e) => {
    if (isBrowserClick(e)) return;
    e.preventDefault();
    onNavigate(e);
  };
  return (
    <a href={href} onClick={onClick} {...rest}>
      {children}
    </a>
  );
};

export default AppLink;
