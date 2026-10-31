#!/usr/bin/env node
/**
 * serve-dist.mjs — production preview server for the HYDRA frontend.
 *
 * Why: the Angular dev server (ng serve) always injects the vite client, whose
 * WebSocket cannot traverse the platform preview proxy — the browser console
 * then shows "WebSocket connection failed / [vite] failed to connect" noise on
 * every page load. Serving the PRODUCTION build has zero WebSocket usage, no
 * dev-mode warnings, and faster loads.
 *
 * Behaviour:
 *   - serves ./dist/hydra-web/browser (SPA fallback to index.html)
 *   - proxies /api/* → http://127.0.0.1:8080 (backend)
 *
 * Local development with hot reload: npm run start:dev (dev server).
 */
import http from 'node:http';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { join, normalize, extname, resolve } from 'node:path';

const PORT = Number(process.env.PORT || 3000);
const BACKEND = process.env.BACKEND_ORIGIN || 'http://127.0.0.1:8080';
const DIST = resolve(process.cwd(), 'dist/hydra-web/browser');

if (!existsSync(DIST)) {
  console.error(`[serve-dist] dist not found at ${DIST} — run "npm run build" first.`);
  process.exit(1);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
  '.webmanifest': 'application/manifest+json'
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  // ── /api proxy → backend ────────────────────────────────────────────
  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
    const headers = { ...req.headers };
    headers.host = BACKEND.replace(/^https?:\/\//, '');
    delete headers['accept-encoding']; // keep identity so errors stay readable
    const proxied = http.request(
      BACKEND + url.pathname + url.search,
      { method: req.method, headers },
      (pr) => {
        res.writeHead(pr.statusCode || 502, pr.headers);
        pr.pipe(res);
      }
    );
    proxied.on('error', (err) => {
      console.error('[serve-dist] /api proxy error:', err.message);
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'application/json' });
      }
      res.end(JSON.stringify({ code: 'BACKEND_UNREACHABLE', message: 'Backend indisponible' }));
    });
    req.pipe(proxied);
    return;
  }

  // ── static files with SPA fallback ──────────────────────────────────
  let rel = decodeURIComponent(url.pathname);
  try { rel = normalize(rel).replace(/^(\.\.[/\\])+/, ''); } catch { rel = '/'; }
  let filePath = join(DIST, rel);
  const inside = filePath.startsWith(DIST);
  let stat = inside && existsSync(filePath) ? statSync(filePath) : null;
  if (stat?.isDirectory()) {
    filePath = join(filePath, 'index.html');
    stat = existsSync(filePath) ? statSync(filePath) : null;
  }
  if (!stat) {
    filePath = join(DIST, 'index.html'); // SPA fallback
    stat = existsSync(filePath) ? statSync(filePath) : null;
  }
  if (!stat) {
    res.writeHead(500).end('dist/hydra-web/browser/index.html manquant — lancez "npm run build"');
    return;
  }
  const type = MIME[extname(filePath).toLowerCase()] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
  res.end(readFileSync(filePath));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[serve-dist] HYDRA frontend (production build) on http://0.0.0.0:${PORT} — /api → ${BACKEND}`);
});
