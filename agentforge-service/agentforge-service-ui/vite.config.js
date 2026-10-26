import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'
import { resolve } from 'node:path'

// 单站点多页面：
//   /        → 我的模型（管理列表，src/pages/models）
//   /agent/published/{aid} → 已发布 Agent 对话页（src/pages/published）
// 两个页面各自独立入口、共享一套构建产物，统一由本配置开发与打包。
function rewriteChatPath() {
  const redirect = (req) => {
    const [path, query] = (req.url || '').split('?')
    if (path === '/chat' || path.startsWith('/chat/') || path.startsWith('/agent/published/') || path === '/agents' || path === '/agents/config') {
      const target = path === '/agents/config' ? '/agents/' : path === '/agents' ? '/agents/' : '/chat/'
      req.url = target + (query ? '?' + query : '')
    }
  }
  return {
    name: 'agentforge-chat-path',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        redirect(req)
        next()
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use((req, _res, next) => {
        redirect(req)
        next()
      })
    }
  }
}

export default defineConfig({
  plugins: [vue(), rewriteChatPath()],
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
      input: {
        index: resolve(process.cwd(), 'index.html'),
        'agents/index': resolve(process.cwd(), 'agents/index.html'),
        'chat/index': resolve(process.cwd(), 'chat/index.html')
      },
      output: {
        // 按依赖来源拆分公共包，提升缓存命中率、避免单页打包体积过大。
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('element-ui')) return 'vendor-element'
          if (id.includes('highlight.js')) return 'vendor-highlight'
          if (id.includes('markdown-it')) return 'vendor-markdown'
          return 'vendor'
        }
      }
    }
  }
})
