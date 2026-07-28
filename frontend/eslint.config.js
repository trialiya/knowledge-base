// Замена eslint-config-react-app, который приезжал внутри react-scripts и
// гонялся на каждой сборке CRA. Prettier (spotless format 'react') его не
// заменяет: он форматтер и не видит ни неиспользуемую переменную, ни забытую
// зависимость в useEffect. Набор правил здесь намеренно повторяет то, что
// реально гонялось до перехода на Vite, — не больше.
import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';

export default [
  { ignores: ['build/**', 'node_modules/**'] },

  js.configs.recommended,
  // Нужен ради jsx-uses-vars/jsx-uses-react: без них no-unused-vars не видит,
  // что импорт использован в JSX, и ругается на каждый компонент.
  react.configs.flat.recommended,

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
    settings: { react: { version: 'detect' } },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      // Ради чего всё и затевалось.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',

      // Форматирование целиком за Prettier — ESLint в него не лезет.
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // PropTypes в проекте не используются (как и в eslint-config-react-app).
      'react/prop-types': 'off',
      // Автоматический JSX-рантайм: React импортировать не обязательно. Но
      // jsx-uses-react из recommended оставляем включённым, иначе там, где
      // `import React` всё-таки есть, no-unused-vars посчитает его мёртвым.
      'react/react-in-jsx-scope': 'off',
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
