import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  resolve: {
    // Для кросс-фичевых импортов (например, components/*Panel в common/*),
    // чтобы путь не зависел от глубины вложенности. Внутри одной фичи
    // импорты остаются относительными — см. frontend-ui.md.
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },

  build: {
    // Не `build/`: это каталог сборки Gradle, там же живёт установка prettier
    // для spotless. Vite чистит outDir перед каждой сборкой, а недоснесённую
    // установку spotless не восстанавливает — каталог на месте, значит install
    // считается готовым. Симптом: spotlessCheck падает на каждом .css с
    // «Cannot find module './parser-postcss.js'», а .js/.jsx проходят —
    // парсеры prettier грузит лениво.
    // Раскладку CRA внутри сохраняем: :frontend:copyFrontend забирает
    // index.html и static/** в ресурсы бэкенда.
    outDir: 'build/dist',
    assetsDir: 'static',

    // Целевые браузеры задаёт дефолтный build.target Vite, из него же берётся
    // cssTarget — по нему Lightning CSS решает, какие вендорные префиксы
    // дописать, а какие вырезать как лишние. Своего browserslist в проекте нет:
    // Vite его не читает. Префикс, нужный браузеру старше цели, минификатор
    // выбросит, даже если написать его в CSS руками, — сначала двигайте target.
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
    environment: 'happy-dom',
    setupFiles: './src/setupTests.js',
    include: ['src/**/*.test.{js,jsx}'],
  },
});
