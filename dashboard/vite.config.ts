import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * The dashboard is served under /dashboard/ in production, so every asset URL has to be built
 * with that prefix. Spring maps exactly one resource handler, /dashboard/**, which keeps the API
 * and the actuator surface untouched; an absolute base of '/' would emit /assets/... and 404.
 *
 * There is no `define` block and no VITE_ prefixed secret anywhere in this repository. Anything
 * Vite substitutes at build time ends up in the JavaScript bundle in plain text, so a build-time
 * admin token is a published admin token. The browser gets its token from the operator at
 * runtime and keeps it in sessionStorage; see src/api/token.ts.
 */
export default defineConfig({
  base: '/dashboard/',
  plugins: [react()],
  server: {
    port: 5173,
    // development only. The backend is same-origin in production, so there is no CORS
    // configuration on the Java side and none is wanted.
    proxy: {
      '/api': {
        target: process.env.DISTROQ_API_URL ?? 'http://localhost:8080',
        changeOrigin: false
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // a source map would ship the original TypeScript; nothing secret is in it, but there is no
    // reason to publish the source of an operations tool to every browser that loads it
    emptyOutDir: true
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    css: false,
    restoreMocks: true,
    clearMocks: true
  }
});
