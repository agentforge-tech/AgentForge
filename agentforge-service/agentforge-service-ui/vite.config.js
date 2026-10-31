import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'

export default defineConfig({
  plugins: [vue()],
  css: {
    preprocessorOptions: {
      scss: { api: 'modern' }
    }
  },
  server: {
    port: 5173,
    // 后端地址可通过 VITE_PROXY_TARGET 覆盖，便于本地换端口联调
    proxy: {
      '/api': { target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    // 使用 Rollup 默认的模块图拆包，避免 Element UI 与 Vue 被强制分到
    // 不同 vendor chunk 后产生循环依赖和初始化顺序问题。
    emptyOutDir: true
  }
})
