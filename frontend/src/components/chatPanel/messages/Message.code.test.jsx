import { render } from '@testing-library/react';
import Message from './Message';

// Блок кода в ответе модели приходит любым из вариантов разметки, и шапку с языком
// и копированием обязан получить каждый: однострочный заборчик без языка — такой же
// блок, как и все, а «есть перевод строки» его от строчного кода не отличает.

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

const preview = (text) => {
  const { container } = render(<Message text={text} sender="ai" mid="m1" />);
  return container.querySelector('.md-preview');
};

test.each([
  ['заборчик с языком', '```js\nconst a = 1;\nconst b = 2;\n```', 'js'],
  ['однострочный заборчик с языком', '```js\nconst a = 1;\n```', 'js'],
  ['заборчик без языка', '```\nline1\nline2\n```', ''],
  ['однострочный заборчик без языка', '```\njust one line\n```', ''],
  ['блок отступом в четыре пробела', '    only line', ''],
])('%s — блок с шапкой', (_name, text, lang) => {
  const md = preview(text);
  const block = md.querySelector('.code-block');
  expect(block).not.toBeNull();
  expect(block.querySelector('.code-block__lang').textContent).toBe(lang);
  expect(block.querySelector('.code-block__copy')).not.toBeNull();
});

test('блок не вложен во второй pre — иначе рамка в рамке', () => {
  const md = preview('```js\nconst a = 1;\n```');
  expect(md.querySelectorAll('pre')).toHaveLength(1);
  expect(md.querySelector('.code-block').parentElement).toBe(md);
});

test('хвостовой перевод строки заборчика не доезжает до кода', () => {
  const md = preview('```\njust one line\n```');
  expect(md.querySelector('code').textContent).toBe('just one line');
});

test('строчный код остаётся строчным', () => {
  const md = preview('текст с `inline()` внутри абзаца');
  expect(md.querySelector('p > code').textContent).toBe('inline()');
  expect(md.querySelector('.code-block')).toBeNull();
});

// Служебный проп react-markdown уезжал на DOM-узел атрибутом node="[object Object]".
test('служебные пропсы react-markdown не попадают в разметку', () => {
  const md = preview('текст с `inline()` и [ссылкой](/?doc=7)\n\n```js\nconst a = 1;\n```');
  md.querySelectorAll('*').forEach((el) => expect(el.getAttribute('node')).toBeNull());
});
