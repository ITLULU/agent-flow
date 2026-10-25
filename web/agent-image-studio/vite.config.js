import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// agent-image-studio —— 独立生图前端（与 agent-flow-web 互不依赖）：
// 只做一件事：参考图定风格 → 换成我的内容 → 按渠道场景出对应尺寸的图。
//
// 端口纪律抄自 agent-flow-web 的实测教训：
// - host 显式 127.0.0.1：Node 把 'localhost' 解析成 ::1，本机 Wi-Fi IPv6 是 Off ⇒ 5174 "活着却连不上"；
// - 代理目标也写 127.0.0.1，避免同款 ::1 陷阱导致页面能开、/api 全 502；
// - 不用 host:true（那会绑 0.0.0.0 暴露到局域网）。
export default defineConfig({
  plugins: [vue()],
  base: './',
  cacheDir: process.env.VITE_CACHE_DIR || 'node_modules/.vite',
  build: {
    outDir: 'dist',
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: { '/api': process.env.VITE_API_TARGET || 'http://127.0.0.1:8080' },
  },
})
