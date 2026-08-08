import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  build: {
    // Раскладку CRA сохраняем: :frontend:copyFrontend забирает из build/
    // index.html и static/** в ресурсы бэкенда.
    outDir: 'build',
    assetsDir: 'static',

    // Vite 8 минифицирует CSS через Lightning CSS, а тот вырезает вендорные
    // префиксы, которых не требуют его целевые браузеры: из сборки пропадали
    // -webkit-fit-content / -webkit-max-content, которые autoprefixer доклеил
    // ради ios_saf 11 из browserslist (см. postcss.config.js). Свой browserslist
    // ему не передать — targets минификатора Vite считает из build.cssTarget, —
    // поэтому оставляем минификатор Vite 7. Ради него esbuild и стоит в
    // devDependencies: в Vite 8 это опциональный peer, а не зависимость.
    cssMinify: 'esbuild',
  },

  server: {
    // Dev-сервер на :3000, API проксируется на бэкенд (:8080) — раньше это
    // делали поля `proxy` в package.json и src/setupProxy.js.
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // Чат стримит ответ токенами: буферизация и сжатие на лету всё
        // проглотили бы до конца запроса.
        headers: { 'Accept-Encoding': 'identity' },
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            proxyRes.headers['cache-control'] = 'no-cache, no-transform';
            proxyRes.headers['x-accel-buffering'] = 'no';
          });
        },
      },
    },
  },

  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
    include: ['src/**/*.test.{js,jsx}'],
  },
});
