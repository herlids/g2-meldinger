import { defineConfig } from 'vite'

// Relative stier, siden .ehpk lastes fra pakken og ikke fra en webserver-rot.
export default defineConfig({
  base: './',
  build: { target: 'es2022' },
  server: { host: true },
})
