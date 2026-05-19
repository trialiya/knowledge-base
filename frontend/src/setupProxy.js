const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function (app) {
  app.use(
    '/api',
    createProxyMiddleware({
      target: 'http://localhost:8080',
      changeOrigin: true,
      proxy_buffering: false, // <-- отключает буферизацию
      on: {
        proxyRes: (proxyRes, req, res) => {
          // Дополнительно исключаем кеширование и сжатие на лету
          proxyRes.headers['cache-control'] = 'no-cache, no-transform';
          proxyRes.headers['x-accel-buffering'] = 'no';
        },
      },
    }),
  );
};
