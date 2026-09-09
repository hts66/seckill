import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    server: {
      host: true, // 监听 0.0.0.0，手机连同一 WiFi 可用电脑局域网 IP 访问
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
        // WebSocket 聊天：网关在 8080 完成 JWT 握手鉴权
        '/ws': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          ws: true,
        },
      },
    },
  }
})
