import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import fs from 'node:fs';

/*
 * MapLibre's worker bundle (`maplibre-gl-worker.mjs`, pulled in below via `?url`) contains its
 * own hardcoded `import ... from "./maplibre-gl-shared.mjs"` — a literal, unhashed relative path
 * baked into the file, resolved by the browser against the worker's own script URL at runtime.
 * The `?url` import copies only the worker file itself; Rollup never opens it to see that import,
 * so nothing else in the build provides that sibling. Without it the worker 404s on load, tiles
 * are fetched but never decoded, and every vector layer stays invisible while the raster base map
 * (needs no worker) renders normally — the failure this plugin exists to close off.
 */
function maplibreWorkerSharedChunk(): Plugin {
  return {
    name: 'maplibre-worker-shared-chunk',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'assets/maplibre-gl-shared.mjs',
        source: fs.readFileSync(
          path.resolve(__dirname, 'node_modules/maplibre-gl/dist/maplibre-gl-shared.mjs'),
          'utf-8',
        ),
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), maplibreWorkerSharedChunk()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    // 5173 by default; PORT lets a second instance run alongside the first without a flag.
    port: Number(process.env.PORT) || 5173,
    /*
     * The API is proxied rather than called cross-origin in development.
     *
     * This is not merely convenient: the refresh token is an `HttpOnly; SameSite=Strict`
     * cookie, and a cross-origin dev setup would have the browser refuse to store or send
     * it — so the developer would be debugging a cookie problem that does not exist in
     * production. Proxying keeps dev same-origin, exactly like the deployed topology
     * behind nginx.
     */
    proxy: {
      '/api': { target: 'http://localhost:8088', changeOrigin: true },
      '/.well-known': { target: 'http://localhost:8088', changeOrigin: true },

      /*
       * Base map tiles, proxied rather than fetched directly by the browser.
       *
       * Three reasons, in order of how painfully each was learned:
       *
       * 1. A browser on a managed network may stall or drop third-party requests while the
       *    same URL fetches perfectly from a terminal. MapLibre does not surface that as an
       *    error — it simply never completes its first render, and the map stays blank with
       *    no diagnostic at all. Same-origin requests cannot be filtered that way.
       * 2. It removes CORS from the equation entirely.
       * 3. OSM's tile policy expects an identifying User-Agent, which a browser will not let
       *    us set but a proxy will.
       *
       * The deployed topology needs the same mapping in nginx; see deploy/nginx.
       */
      '/basemap/osm': {
        target: 'https://tile.openstreetmap.org',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/basemap\/osm/, ''),
        headers: { 'User-Agent': 'AquaGrid/1.0 (water utility GIS; self-hosted)' },
      },
      '/basemap/esri-imagery': {
        target: 'https://server.arcgisonline.com',
        changeOrigin: true,
        rewrite: (path) =>
          path.replace(
            /^\/basemap\/esri-imagery/,
            '/ArcGIS/rest/services/World_Imagery/MapServer/tile',
          ),
      },
      /*
       * Label glyphs, for the symbol layers Layer Style Management can now switch on.
       *
       * MapLibre's own font endpoint: no API key, same "works on a clean checkout" property as the
       * tiles. Proxied for the same reasons they are — and additionally because the deployed CSP is
       * `connect-src 'self'`, so a direct request to demotiles.maplibre.org would be blocked in
       * production while working perfectly in development. Mirrors the /basemap/fonts/ rule in
       * deploy/nginx/nginx.conf; the two must be changed together.
       */
      '/basemap/fonts': {
        target: 'https://demotiles.maplibre.org',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/basemap\/fonts/, '/font'),
      },
      '/basemap/esri-reference': {
        target: 'https://server.arcgisonline.com',
        changeOrigin: true,
        rewrite: (path) =>
          path.replace(
            /^\/basemap\/esri-reference/,
            '/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile',
          ),
      },

      /*
       * Place search (Nominatim). Same-origin for the same three reasons as the tiles above,
       * plus the usage policy: Nominatim asks for ≤1 req/s and an identifying User-Agent, which
       * only a proxy can set from a browser. Mirrors the nginx /geocode/ rule.
       */
      '/geocode': {
        target: 'https://nominatim.openstreetmap.org',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/geocode/, ''),
        headers: { 'User-Agent': 'AquaGrid/1.0 (water utility GIS; self-hosted)' },
      },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: true,
    rollupOptions: {
      output: {
        /*
         * Manual chunks along module lines, so a shared dependency is downloaded once and
         * stays cached across releases that do not touch it.
         *
         * `vendor-map` is the heaviest of these by far. It is only reachable from the lazily
         * loaded GIS route, so a field technician on a constrained mobile link never downloads
         * the rendering engine just to type a password. ECharts gets the same treatment when
         * Module 13 introduces it.
         */
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-mui': ['@mui/material', '@mui/icons-material', '@emotion/react', '@emotion/styled'],
          'vendor-query': ['@tanstack/react-query', 'axios'],
          'vendor-map': ['maplibre-gl'],
        },
      },
    },
  },
});
