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
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 按依赖来源拆分公共包，提升缓存命中率、避免单页打包体积过大。
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('element-ui')) return 'vendor-element'
          if (id.includes('/node_modules/vue') || id.includes('/node_modules/@vue')) return 'vendor-vue'
          if (id.includes('highlight.js')) return 'vendor-highlight'
          if (id.includes('markdown-it')) return 'vendor-markdown'
        }
      }
    }
  }
})
