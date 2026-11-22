import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  envDir: '..',
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    // Override the root .env so tests can never reach a real service.
    env: {
      VITE_SUPABASE_URL: 'http://supabase.test',
      VITE_SUPABASE_PUBLISHABLE_KEY: 'test-publishable-key',
      VITE_API_BASE_URL: 'http://api.test/api/v1',
    },
  },
})
