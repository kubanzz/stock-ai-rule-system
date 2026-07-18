import process from 'node:process';

import { defineConfig } from '@vben/vite-config';

export default defineConfig(async () => {
  const backendTarget =
    process.env.VITE_STOCK_BACKEND_URL ?? 'http://localhost:8080';

  return {
    application: {},
    vite: {
      server: {
        proxy: {
          '/api': {
            changeOrigin: true,
            // Spring Boot 后端服务地址
            target: backendTarget,
            ws: true,
          },
        },
      },
    },
  };
});
