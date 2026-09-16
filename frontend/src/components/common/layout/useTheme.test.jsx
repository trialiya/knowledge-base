import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { STORAGE_KEY_THEME } from '@/constants/storage';

/** Тема, которую сейчас показывает страница, — то же, что видят стили. */
const shown = () => document.documentElement.dataset.theme;

/**
 * Подменённый matchMedia: тесты должны уметь сказать «в системе сейчас тёмная»
 * и «а теперь рассвело», не завися от машины, на которой их запустили.
 */
let systemDark = false;
const listeners = new Set();

beforeEach(() => {
  systemDark = false;
  listeners.clear();
  localStorage.clear();
  vi.stubGlobal('matchMedia', (query) => ({
    matches: query.includes('dark') && systemDark,
    addEventListener: (_event, fn) => listeners.add(fn),
    removeEventListener: (_event, fn) => listeners.delete(fn),
  }));
  vi.resetModules(); // стор живёт в модуле: тема читается из хранилища при импорте
});

/** Рассвело или стемнело в системе — как будто это сделала ОС. */
const systemSwitchesTo = (dark) =>
  act(() => {
    systemDark = dark;
    listeners.forEach((fn) => fn());
  });

/** Модуль импортируется в тесте, а не сверху: он читает хранилище на импорте. */
const mount = async () => {
  const { default: useTheme, THEMES } = await import('./useTheme');
  const Probe = () => {
    const { theme, resolved, setTheme } = useTheme();
    return (
      <>
        <span data-testid="choice">{theme}</span>
        <span data-testid="resolved">{resolved}</span>
        {THEMES.map((code) => (
          <button key={code} onClick={() => setTheme(code)}>
            {code}
          </button>
        ))}
      </>
    );
  };
  render(<Probe />);
  return {
    choice: () => screen.getByTestId('choice').textContent,
    resolved: () => screen.getByTestId('resolved').textContent,
    pick: (code) => userEvent.click(screen.getByRole('button', { name: code })),
  };
};

it('без выбора идёт за системой', async () => {
  systemDark = true;
  const s = await mount();
  expect(s.choice()).toBe('system');
  expect(s.resolved()).toBe('dark');
  expect(shown()).toBe('dark');
});

it('выбранная тема переживает перезагрузку', async () => {
  const first = await mount();
  await first.pick('dark');
  expect(localStorage.getItem(STORAGE_KEY_THEME)).toBe('dark');

  cleanup(); // перезагрузка страницы — это новое дерево, а не второе рядом
  vi.resetModules();
  const second = await mount();
  expect(second.choice()).toBe('dark');
  expect(shown()).toBe('dark');
});

it('выбранная руками тема не идёт за системой', async () => {
  // Человек включил светлую днём; закат не вправе это отменить.
  const s = await mount();
  await s.pick('light');
  await systemSwitchesTo(true);
  expect(s.resolved()).toBe('light');
  expect(shown()).toBe('light');
});

it('«как в системе» перерисовывает меню на закате', async () => {
  // Выбор при этом не меняется — если бы снимок стора состоял из него одного,
  // React не увидел бы, что перерисовывать есть что.
  const s = await mount();
  expect(s.resolved()).toBe('light');

  await systemSwitchesTo(true);
  expect(s.choice()).toBe('system');
  expect(s.resolved()).toBe('dark');
  expect(shown()).toBe('dark');
});

it('битое значение в хранилище не ломает приложение', async () => {
  localStorage.setItem(STORAGE_KEY_THEME, 'сепия');
  const s = await mount();
  expect(s.choice()).toBe('system');
  expect(shown()).toBe('light');
});
