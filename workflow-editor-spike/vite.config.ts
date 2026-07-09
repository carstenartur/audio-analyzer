import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Proxy API calls to the WorkflowEditorHttpAdapter running on port 8080
    proxy: {
      '/workflow': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
