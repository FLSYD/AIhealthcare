import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 仅在前端开发服务器代理接口，无需调整后端跨域或配置文件。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'HEALTHCARE_')
  const localApiTarget = 'http://127.0.0.1:18080'
  const configuredApiTarget = process.env.HEALTHCARE_API_TARGET || env.HEALTHCARE_API_TARGET || localApiTarget
  let apiTarget
  try {
    const parsedTarget = new URL(configuredApiTarget)
    if (
      parsedTarget.origin !== localApiTarget ||
      parsedTarget.pathname !== '/' ||
      parsedTarget.search ||
      parsedTarget.hash ||
      parsedTarget.username ||
      parsedTarget.password
    ) {
      throw new Error('target mismatch')
    }
    apiTarget = parsedTarget.origin
  } catch (error) {
    throw new Error(
      `HEALTHCARE_API_TARGET 必须为 ${localApiTarget}，禁止连接非本地或旧项目后端；当前值：${configuredApiTarget}`,
      { cause: error },
    )
  }
  const proxy = {
    '/api': { target: apiTarget, changeOrigin: true },
    '/uploads': { target: apiTarget, changeOrigin: true },
  }
  return {
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 15173,
    strictPort: true,
    proxy,
  },
  preview: { port: 4173, strictPort: true, proxy },
  build: {
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: { manualChunks: { vue: ['vue', 'vue-router'], element: ['element-plus'], charts: ['echarts'] } },
    },
  },
  }
})
