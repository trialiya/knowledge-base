// Замена eslint-config-react-app, который приезжал внутри react-scripts и
// гонялся на каждой сборке CRA. Prettier (spotless format 'react') его не
// заменяет: он форматтер и не видит ни неиспользуемую переменную, ни забытую
// зависимость в useEffect.
import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';

export default [
  { ignores: ['build/**', 'node_modules/**'] },

  js.configs.recommended,
  // Нужен ради jsx-uses-vars: без него no-unused-vars не видит, что импорт
  // использован в JSX, и ругается на каждый компонент.
  react.configs.flat.recommended,
  // Ради чего всё и затевалось: rules-of-hooks и exhaustive-deps. Вместе с ними
  // приезжают правила React Compiler — чистота рендера, стабильность
  // мемоизации, работа с рефами; три из них выключены ниже.
  reactHooks.configs.flat['recommended-latest'],

  {
    files: ['src/**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: globals.browser,
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    // Версия задаётся явно, а не через 'detect': автодетект eslint-plugin-react
    // читает файловую систему через API контекста, которого в ESLint 10 больше
    // нет, и линт падает на первом же компоненте.
    settings: { react: { version: '19.2' } },
    rules: {
      // Три правила компилятора выключены: код им не соответствует, и привести
      // его в соответствие — не правка конфига, а переписывание эффектов и
      // «зеркал» в рефах. Включать по одному, разобрав места.
      //   set-state-in-effect (33 места) — setState прямо в эффекте, каскадный
      //     рендер. Лечится переносом в обработчик, вычислением при рендере или
      //     сбросом по key — каждое место отдельно.
      //   refs (10 мест) — идиома `xRef.current = x` в теле компонента, чтобы
      //     колбэк видел свежее значение и не пересоздавался. Замена —
      //     useEffectEvent, но эффект-события нельзя передавать в другие хуки,
      //     а эти рефы используются именно так (usePreviewCache, useFileTree).
      //   immutability (3 места, ChatWindow) — рефы, поднятые из localStorage и
      //     правящиеся позже; их читают при выборе модели и режима на первом же
      //     рендере, поэтому инициализацию не отложить в эффект.
      'react-hooks/set-state-in-effect': 'off',
      'react-hooks/refs': 'off',
      'react-hooks/immutability': 'off',

      // Форматирование целиком за Prettier — ESLint в него не лезет.
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // PropTypes в проекте не используются (как и в eslint-config-react-app).
      'react/prop-types': 'off',
      // Автоматический JSX-рантайм: React импортировать не нужно. Оба правила
      // держим выключенными вместе — react-in-jsx-scope не требует импорт, а
      // без jsx-uses-react no-unused-vars ловит `import React`, если его всё
      // же завезли. Нужен Fragment/StrictMode — импортируйте их поимённо.
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
    },
  },

  {
    // В тестах живут глобалки vitest (globals: true) и node-билтины (fs/path).
    files: ['src/**/*.test.{js,jsx}', 'src/setupTests.js'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node, ...globals.vitest },
    },
  },
];
