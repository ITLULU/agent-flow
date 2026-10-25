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
    // 显式绑 IPv4 回环：本机 Wi-Fi 的 IPv6 是 Off，而 vite 默认 host='localhost' 会被 Node 解析成
    // ::1 并只监听 IPv6 —— 结果进程活着、端口在听，浏览器却是"无法访问"（curl 报 Exec format error）。
    // 也不用 host:true，那会绑 0.0.0.0 把 dev server 暴露到局域网。
    host: '127.0.0.1',
    port: 5173,
    // 代理目标同样写死 127.0.0.1：留 localhost 就可能在 Node 侧解析成 ::1，前端页面能开了、/api 却全 502
    proxy: { '/api': process.env.VITE_API_TARGET || 'http://127.0.0.1:8080' },
  },
})
