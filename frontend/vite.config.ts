import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/#environment-variables
export default defineConfig(({ mode }) => {
  // 本地开发从 .env.local 读，生产由 Vercel 注入
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:3001'

  return {
    plugins: [react()],
    // Vercel 子路径部署时需要 base: "/app/"，根域部署保持默认
    base: env.VITE_BASE || '/',
    server: {
      port: 5173,
      host: true,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true
        },
        '/socket.io': {
          target: apiTarget,
          changeOrigin: true,
          ws: true
        }
      }
    },
    build: {
      outDir: 'dist',
      sourcemap: false
    }
  }
})
