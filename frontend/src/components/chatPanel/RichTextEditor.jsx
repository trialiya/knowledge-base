import React, { forwardRef } from 'react';

/**
 * Чистый contentEditable-div: только рендер + проброс пропсов-обработчиков.
 * Вся логика (сериализация, триггеры, вставка чипов) остаётся в ChipEditor —
 * этот компонент ничего не знает про чипы, токены или picker/preview.
 */
const RichTextEditor = forwardRef(function RichTextEditor(
  { isEmpty, disabled, placeholder, onInput, onKeyDown, onPaste, onClick, onBlur },
  ref,
) {
  return (
    <div
      ref={ref}
      className={`message-input message-input--rich${isEmpty ? ' is-empty' : ''}`}
      contentEditable={!disabled}
      suppressContentEditableWarning
      role="textbox"
      aria-multiline="true"
      data-placeholder={placeholder}
      onInput={onInput}
      onKeyDown={onKeyDown}
      onPaste={onPaste}
      onClick={onClick}
      onBlur={onBlur}
    />
  );
});

export default RichTextEditor;
