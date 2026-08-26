import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Сборка стенда фикстур — отдельная от сборки приложения (frontend/vite.config.js):
 * у неё свой вход, свой outDir и никакого прокси на бэкенд, потому что бэкенда
 * ей не нужно. Псевдоним `@` тот же — компоненты импортируются как в приложении.
 */
export default defineConfig({
  root: fileURLToPath(new URL('./', import.meta.url)),
  // Относительные пути в бандле: стенд раздаётся статикой с временного порта.
  base: './',
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('../../../src', import.meta.url)) } },
  build: { outDir: fileURLToPath(new URL('./dist', import.meta.url)), emptyOutDir: true },
});
