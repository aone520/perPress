/**
 * Vite 构建配置：注册 Vue 插件、@ 路径别名、开发端口 5173，
 * 并将 /api 与 /agent 前缀请求代理到本地后端服务（8080 端口）
 */
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/agent': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
