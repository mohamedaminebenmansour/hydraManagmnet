#!/usr/bin/env node
// ============================================================================
//  supervisor.mjs — keeps serve-dist.mjs alive forever
// ----------------------------------------------------------------------------
//  Forks serve-dist.mjs as a child process. If the child exits for any
//  reason, restarts it after a 2-second cooldown. Logs every restart.
//
//  More robust than a bash watchdog because it's a single Node process
//  that catches child exit codes and restarts immediately.
// ============================================================================

import { spawn } from 'node:child_process';
import { createWriteStream, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const FRONTEND_DIR = '/tmp/my-project/frontend';
const SERVE_SCRIPT = resolve(FRONTEND_DIR, 'serve-dist.mjs');
const LOG_FILE = '/tmp/my-project/logs/supervisor.log';
const SERVE_LOG = '/tmp/my-project/logs/frontend-dist.log';
const RESTART_COOLDOWN_MS = 2000;

mkdirSync('/tmp/my-project/logs', { recursive: true });

function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}`;
  console.log(line);
  appendFileSync(LOG_FILE, line + '\n');
}

let restartCount = 0;
let currentChild = null;

function startServe() {
  restartCount++;
  log(`Starting serve-dist.mjs (attempt #${restartCount})`);

  // Truncate the serve log on each restart so we can see fresh output
  const out = createWriteStream(SERVE_LOG, { flags: 'w' });

  const child = spawn('node', [SERVE_SCRIPT], {
    cwd: FRONTEND_DIR,
    env: { ...process.env, PORT: '3000' },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: false,
  });

  currentChild = child;

  child.stdout.on('data', (d) => out.write(d));
  child.stderr.on('data', (d) => {
    out.write(d);
    process.stderr.write(d);
  });

  child.on('exit', (code, signal) => {
    log(`serve-dist exited (code=${code}, signal=${signal}) — restarting in ${RESTART_COOLDOWN_MS}ms`);
    out.end();
    setTimeout(startServe, RESTART_COOLDOWN_MS);
  });

  child.on('error', (err) => {
    log(`serve-dist spawn error: ${err.message} — restarting in ${RESTART_COOLDOWN_MS}ms`);
    out.end();
    setTimeout(startServe, RESTART_COOLDOWN_MS);
  });

  log(`serve-dist started with PID ${child.pid}`);
  return child;
}

// Don't die on SIGHUP (the main reason backgrounded processes die)
process.on('SIGHUP', () => log('Got SIGHUP — ignoring'));
process.on('SIGINT', () => { log('Got SIGINT — exiting'); process.exit(0); });
process.on('SIGTERM', () => { log('Got SIGTERM — exiting'); process.exit(0); });

log('Supervisor started — will keep serve-dist.mjs alive');
startServe();
