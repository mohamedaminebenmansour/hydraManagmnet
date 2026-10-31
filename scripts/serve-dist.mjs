// serve-dist.mjs — minimal static + /api proxy for the Angular dist bundle.
// Production-grade, zero WebSocket noise (no dev-mode HMR warnings on console).
//
// - serves /home/z/my-project/frontend/dist/hydra-web/browser as static root
// - /api/** → http://127.0.0.1:8080/** (proxy with cookie pass-through)
// - /health → 200 (platform liveness probe)
// - SPA fallback: any non-asset path → index.html (so Angular router works
//   on deep links like /tresorerie/caisses/abc-123)
//
// Run: node /home/z/my-project/scripts/serve-dist.mjs (port 3000)

import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

const ROOT = '/home/z/my-project/frontend/dist/hydra-web/browser';
const PORT = Number(process.env.PORT) || 3000;
const API_TARGET = 'http://127.0.0.1:8080';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript; charset=utf-8',
  '.mjs':  'application/javascript; charset=utf-8',
  '.css':  'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg':  'image/svg+xml',
  '.png':  'image/png',
  '.jpg':  'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif':  'image/gif',
  '.ico':  'image/x-icon',
  '.woff': 'font/woff',
  '.woff2':'font/woff2',
  '.ttf':  'font/ttf',
  '.eot':  'application/vnd.ms-fontobject',
  '.webp': 'image/webp',
  '.txt':  'text/plain; charset=utf-8',
  '.map':  'application/json; charset=utf-8',
};

function pipeJsonError(res, code, message) {
  const body = JSON.stringify({ code, message });
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}

// Minimal HTTP proxy to backend (single host:port, no streaming tweaks needed).
function proxyToBackend(req, res) {
  const url = new URL(req.url, API_TARGET);
  const opts = {
    method: req.method,
    hostname: url.hostname,
    port: url.port,
    path: url.pathname + url.search,
    headers: { ...req.headers, host: url.host },
  };
  const upstream = http.request(opts, (up) => {
    res.writeHead(up.statusCode, up.headers);
    up.pipe(res, { end: true });
  });
  upstream.on('error', (e) => {
    console.error('[proxy] upstream error:', e.message);
    pipeJsonError(res, 502, 'Backend unreachable');
  });
  req.pipe(upstream, { end: true });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = u.pathname;

  // 1) Health probe
  if (pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', service: 'hydra-web', ts: Date.now() }));
    return;
  }

  // 2) API + auth → backend
  if (pathname.startsWith('/api/') || pathname.startsWith('/api')) {
    proxyToBackend(req, res);
    return;
  }

  // 3) Static asset from dist
  let rel = pathname === '/' ? '/index.html' : pathname;
  // strip leading slash
  rel = rel.replace(/^\/+/, '');
  const fsPath = path.join(ROOT, rel);

  // Security: stay inside ROOT
  if (!fsPath.startsWith(ROOT)) {
    pipeJsonError(res, 400, 'Bad path');
    return;
  }

  if (existsSync(fsPath)) {
    const st = await stat(fsPath).catch(() => null);
    if (st && st.isFile()) {
      const ext = path.extname(fsPath).toLowerCase();
      const ct = MIME[ext] || 'application/octet-stream';
      // Hashed asset filenames → cache forever; index.html must always revalidate
      const immutable = ext !== '.html' && /[A-Z0-9]{8,}\.(js|css|mjs)$/i.test(rel);
      const headers = { 'Content-Type': ct };
      if (immutable) headers['Cache-Control'] = 'public, max-age=31536000, immutable';
      else headers['Cache-Control'] = 'no-cache, no-store, must-revalidate';
      try {
        const buf = await readFile(fsPath);
        res.writeHead(200, headers);
        res.end(buf);
      } catch (e) {
        pipeJsonError(res, 500, 'Read error');
      }
      return;
    }
  }

  // 4) SPA fallback → index.html (Angular router takes over)
  const indexPath = path.join(ROOT, 'index.html');
  try {
    const buf = await readFile(indexPath);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache, no-store, must-revalidate' });
    res.end(buf);
  } catch (e) {
    pipeJsonError(res, 404, 'index.html missing — run `npm run build` first');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[serve-dist] listening on http://0.0.0.0:${PORT}`);
  console.log(`[serve-dist] static root: ${ROOT}`);
  console.log(`[serve-dist] /api/* → ${API_TARGET}`);
});
