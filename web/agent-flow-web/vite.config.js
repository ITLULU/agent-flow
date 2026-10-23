import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 构建产物直接进 Spring Boot 静态资源目录：单容器即可交付，无需额外 nginx。
// 开发态用 5173 + /api 代理，生产态同源，前端代码不需要区分环境。
export default defineConfig({
  plugins: [vue()],
  base: './',
  cacheDir: process.env.VITE_CACHE_DIR || 'node_modules/.vite',
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  build: {
    outDir: fileURLToPath(new URL('../../server/agent-flow-server/server-application/src/main/resources/static', import.meta.url)),
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: { '/api': process.env.VITE_API_TARGET || 'http://localhost:8080' },
  },
})
