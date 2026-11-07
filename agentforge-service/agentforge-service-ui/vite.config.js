import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue2'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    css: {
      preprocessorOptions: {
        scss: { api: 'modern' }
      }
    },
    server: {
      port: 5173,
      // 后端地址按 .env.<mode> 读取，仍允许命令行环境变量覆盖。
      proxy: {
        '/api': { target: env.VITE_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true }
      }
    },
    build: {
      outDir: 'dist',
      // 使用 Rollup 默认的模块图拆包，避免 Element UI 与 Vue 被强制分到
      // 不同 vendor chunk 后产生循环依赖和初始化顺序问题。
      emptyOutDir: true
    }
  }
})
