#!/usr/bin/env node

/**
 * OSRS Claude Proxy — OpenAI-compatible HTTP server that routes requests
 * through Claude Code CLI with a PERSISTENT SESSION.
 *
 * Instead of stateless one-shot calls, this maintains a single Claude session
 * across the entire bot run. Context accumulates naturally — Claude remembers
 * every game state and action from the session.
 *
 * First request:  claude -p --session-id <uuid> --system-prompt "..." (establishes session)
 * Subsequent:     claude -p --resume <uuid> (continues with full context)
 *
 * The proxy strips conversation history from the bot's requests since Claude
 * already remembers it from the session. Only the current game state is forwarded.
 *
 * Usage:
 *   node server.mjs
 *   PROXY_PORT=9000 LOG_BODIES=1 node server.mjs
 *
 * Bot config:
 *   apiBaseUrl = http://localhost:8082/v1
 */

import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { appendFileSync, readFileSync, writeFileSync, unlinkSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// ─── Configuration ────────────────────────────────────────────────────────────

const PORT = parseInt(process.env.PROXY_PORT || '8082', 10);
const DEFAULT_MODEL = process.env.CLAUDE_MODEL || 'claude-sonnet-4-6';
const REQUEST_TIMEOUT_MS = 110_000; // Under the bot's 120s read timeout
const LOG_BODIES = process.env.LOG_BODIES === '1';
const MAX_BODY_BYTES = 1_048_576; // 1MB max request body
const TRAINING_LOG = process.env.TRAINING_LOG || '/tmp/training_turns.jsonl';

// ─── Wiki Context (injected on first turn of each session) ───────────────────

const __dirname = dirname(fileURLToPath(import.meta.url));
const WIKI_CONTEXT_PATH = process.env.WIKI_CONTEXT || resolve(__dirname, 'wiki_context.txt');
let wikiContext = '';
try {
  if (existsSync(WIKI_CONTEXT_PATH)) {
    wikiContext = readFileSync(WIKI_CONTEXT_PATH, 'utf-8');
    console.log(`Loaded wiki context: ${(wikiContext.length / 1024).toFixed(0)} KB (~${Math.round(wikiContext.length / 4).toLocaleString()} tokens)`);
  }
} catch (e) {
  console.warn(`Failed to load wiki context from ${WIKI_CONTEXT_PATH}: ${e.message}`);
}

// ─── Session State ────────────────────────────────────────────────────────────

let sessionId = randomUUID();
let sessionInitialized = false;
let sessionSystemPrompt = null;  // Cached from first request
let sessionTurnCount = 0;

// ─── Manual Override State ───────────────────────────────────────────────────

/** Manual mode: proxy writes game state to file, blocks until response file appears.
 *  Enable with MANUAL_MODE=1 env var. */
const MANUAL_MODE = process.env.MANUAL_MODE === '1';
const MANUAL_STATE_FILE = '/tmp/bot_pending_state.txt';
const MANUAL_RESPONSE_FILE = '/tmp/bot_response.txt';
const MANUAL_POLL_MS = 500;
const MANUAL_TIMEOUT_MS = 300_000; // 5 minutes to respond

// ─── Active subprocess tracking (for cleanup on shutdown) ─────────────────────

let activeProc = null;

// ─── Logging ──────────────────────────────────────────────────────────────────

function log(level, message, data = {}) {
  const entry = { time: new Date().toISOString(), level, message, ...data };
  if (level === 'error') {
    console.error(JSON.stringify(entry));
  } else {
    console.log(JSON.stringify(entry));
  }
}

// ─── Stats ────────────────────────────────────────────────────────────────────

const stats = {
  total: 0,
  success: 0,
  errors: 0,
  rateLimits: 0,
  sessionResets: 0,
  totalLatencyMs: 0,
  startTime: Date.now(),
};

// ─── Rate Limit Backoff ───────────────────────────────────────────────────────

let consecutiveErrors = 0;
let backoffUntil = 0;

function applyBackoff() {
  consecutiveErrors++;
  const backoffMs = Math.min(1000 * Math.pow(2, consecutiveErrors), 60_000);
  backoffUntil = Date.now() + backoffMs;
  log('warn', `Backoff applied: ${backoffMs}ms`, { consecutiveErrors });
}

function clearBackoff() {
  consecutiveErrors = 0;
  backoffUntil = 0;
}

// ─── Request Queue (serialized, max 1 concurrent) ────────────────────────────

class RequestQueue {
  constructor() {
    this.running = 0;
    this.queue = [];
  }

  enqueue(fn) {
    return new Promise((resolve, reject) => {
      this.queue.push({ fn, resolve, reject });
      this._drain();
    });
  }

  _drain() {
    while (this.running < 1 && this.queue.length > 0) {
      const { fn, resolve, reject } = this.queue.shift();
      this.running++;
      fn()
        .then(resolve)
        .catch(reject)
        .finally(() => {
          this.running--;
          this._drain();
        });
    }
  }

  get pending() { return this.queue.length; }
  get active() { return this.running; }
}

const requestQueue = new RequestQueue();

// ─── Message Extraction ──────────────────────────────────────────────────────

/**
 * Extract the system prompt and ONLY the latest user message from the bot's request.
 *
 * The bot sends full conversation history (system + user/assistant pairs + current),
 * but since we maintain a persistent Claude session, the history is redundant —
 * Claude already remembers it. We only forward the current game state.
 */
function extractCurrentMessage(messages) {
  let systemPrompt = null;
  let lastUserMessage = null;

  for (const msg of messages) {
    if (msg.role === 'system') {
      systemPrompt = msg.content;
    } else if (msg.role === 'user') {
      lastUserMessage = msg.content;
    }
  }

  return { systemPrompt, prompt: lastUserMessage || '(empty)' };
}

// ─── Session Management ───────────────────────────────────────────────────────

/**
 * Reset the session — creates a new session ID so the next call starts fresh.
 * Called on repeated errors or when context might be corrupted.
 */
function resetSession(reason) {
  const oldId = sessionId;
  sessionId = randomUUID();
  sessionInitialized = false;
  sessionSystemPrompt = null;
  sessionTurnCount = 0;
  stats.sessionResets++;
  log('warn', `Session reset: ${reason}`, { oldSessionId: oldId, newSessionId: sessionId });

  // Mark session boundary in training log
  try {
    appendFileSync(TRAINING_LOG, JSON.stringify({
      event: 'session_reset',
      ts: new Date().toISOString(),
      old_session: oldId,
      new_session: sessionId,
      reason,
    }) + '\n');
  } catch {}
}

// ─── Claude CLI Call ──────────────────────────────────────────────────────────

/**
 * Call Claude via `claude -p` with session persistence.
 *
 * First call:  --session-id <uuid> --system-prompt "..." (creates session)
 * Subsequent:  --resume <uuid> (continues session, Claude has full context)
 *
 * Accepts an AbortSignal to kill the subprocess on timeout.
 */
function callClaude(systemPrompt, prompt, model, signal) {
  return new Promise((resolve, reject) => {
    const args = [
      '-p',
      '--output-format', 'json',
      '--tools', '',
      '--permission-mode', 'bypassPermissions',
    ];

    if (model) {
      args.push('--model', model);
    }

    if (!sessionInitialized) {
      // First call — create the session with system prompt
      args.push('--session-id', sessionId);
      if (systemPrompt) {
        args.push('--system-prompt', systemPrompt);
        sessionSystemPrompt = systemPrompt;
      }
      log('info', 'Creating new session', { sessionId, model });
    } else {
      // Subsequent calls — resume existing session (Claude has full context)
      args.push('--resume', sessionId);
    }

    const env = { ...process.env };
    delete env.CLAUDECODE; // Prevent nested session detection

    const proc = spawn('claude', args, {
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    activeProc = proc;

    const stdoutChunks = [];
    let stdoutLen = 0;
    let stderr = '';
    let settled = false;

    function settle(fn) {
      if (!settled) {
        settled = true;
        activeProc = null;
        fn();
      }
    }

    // Kill subprocess if the AbortSignal fires (timeout)
    if (signal) {
      const onAbort = () => {
        settle(() => {
          proc.kill('SIGTERM');
          // Escalate to SIGKILL if process doesn't exit within 5s
          const killTimer = setTimeout(() => {
            try { proc.kill('SIGKILL'); } catch {}
          }, 5000);
          killTimer.unref();
          reject(new Error('Request timeout'));
        });
      };
      if (signal.aborted) {
        onAbort();
        return;
      }
      signal.addEventListener('abort', onAbort, { once: true });
    }

    proc.stdout.on('data', chunk => {
      if (stdoutLen < 5_000_000) { // Cap at 5MB
        stdoutChunks.push(chunk);
        stdoutLen += chunk.length;
      }
    });
    proc.stderr.on('data', chunk => { stderr += chunk; });

    // Handle stdin pipe errors (if claude exits before we write)
    proc.stdin.on('error', () => {}); // Swallowed — handled via proc close/error

    // On the first turn of a session, prepend wiki context so Claude has
    // comprehensive OSRS game knowledge for the entire session
    if (!sessionInitialized && wikiContext) {
      const contextPreamble = '[OSRS_WIKI_REFERENCE]\n'
        + 'The following is a compressed reference of the Old School RuneScape wiki. '
        + 'Use this knowledge to make informed decisions about game mechanics, items, '
        + 'NPCs, locations, skills, and quests. Do NOT respond to this reference — '
        + 'just absorb it and respond only to the [GAME STATE] that follows.\n\n'
        + wikiContext
        + '\n[/OSRS_WIKI_REFERENCE]\n\n';
      proc.stdin.write(contextPreamble);
      log('info', 'Wiki context injected', { chars: wikiContext.length });
    }
    proc.stdin.write(prompt);
    proc.stdin.end();

    proc.on('error', (err) => {
      settle(() => reject(new Error(`Failed to spawn claude: ${err.message}`)));
    });

    proc.on('close', (code) => {
      settle(() => {
        if (code !== 0) {
          const errMsg = stderr.trim() || `claude exited with code ${code}`;
          reject(new Error(errMsg));
          return;
        }

        // Mark session as initialized after first successful call
        if (!sessionInitialized) {
          sessionInitialized = true;
          log('info', 'Session established', { sessionId });
        }
        sessionTurnCount++;

        // Parse JSON output
        const stdout = Buffer.concat(stdoutChunks).toString('utf-8');
        try {
          const output = JSON.parse(stdout);
          const resultText = String(output.result ?? output.text ?? stdout);
          const costUsd = output.cost_usd ?? output.total_cost_usd ?? 0;
          resolve({ resultText, costUsd });
        } catch (parseErr) {
          resolve({ resultText: stdout.trim(), costUsd: 0 });
        }
      });
    });
  });
}

// ─── Manual Mode (file-based handoff to Claude Code session) ─────────────────

/**
 * In manual mode, writes the game state to a file and polls until a response
 * file appears. This lets the current Claude Code session be the bot's brain:
 *   1. Proxy writes game state → /tmp/bot_pending_state.txt
 *   2. Claude Code reads it, analyzes, writes response → /tmp/bot_response.txt
 *   3. Proxy picks up response, deletes both files, returns to bot
 */
function callManual(systemPrompt, prompt) {
  return new Promise((resolve, reject) => {
    // Clean up any stale response file
    try { unlinkSync(MANUAL_RESPONSE_FILE); } catch {}

    // Write game state for Claude Code to read
    const statePayload = JSON.stringify({
      turn: sessionTurnCount,
      session: sessionId,
      system_prompt: sessionInitialized ? '(unchanged)' : systemPrompt,
      game_state: prompt,
      ts: new Date().toISOString(),
    }, null, 2);
    writeFileSync(MANUAL_STATE_FILE, statePayload);
    log('info', 'Manual mode: wrote game state, waiting for response...', {
      stateFile: MANUAL_STATE_FILE,
      responseFile: MANUAL_RESPONSE_FILE,
    });

    if (!sessionInitialized) {
      sessionSystemPrompt = systemPrompt;
      sessionInitialized = true;
    }
    sessionTurnCount++;

    // Poll for response file
    const deadline = Date.now() + MANUAL_TIMEOUT_MS;
    const timer = setInterval(() => {
      if (Date.now() > deadline) {
        clearInterval(timer);
        reject(new Error('Manual mode: response timeout (5 min)'));
        return;
      }
      try {
        if (existsSync(MANUAL_RESPONSE_FILE)) {
          const response = readFileSync(MANUAL_RESPONSE_FILE, 'utf-8').trim();
          if (response.length > 0) {
            clearInterval(timer);
            // Clean up files
            try { unlinkSync(MANUAL_RESPONSE_FILE); } catch {}
            try { unlinkSync(MANUAL_STATE_FILE); } catch {}
            log('info', 'Manual mode: got response', { chars: response.length });
            resolve({ resultText: response, costUsd: 0 });
          }
        }
      } catch {}
    }, MANUAL_POLL_MS);
  });
}

// ─── Response Helpers ─────────────────────────────────────────────────────────

function sendJson(res, statusCode, body) {
  const json = JSON.stringify(body);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(json),
    'Access-Control-Allow-Origin': '*',
  });
  res.end(json);
}

function sendError(res, statusCode, message, type = 'api_error') {
  sendJson(res, statusCode, {
    error: { message, type, code: statusCode }
  });
}

function wrapResponse(content, model) {
  return {
    id: `chatcmpl-${randomUUID()}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: model || DEFAULT_MODEL,
    choices: [{
      index: 0,
      message: { role: 'assistant', content },
      finish_reason: 'stop'
    }],
    usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 }
  };
}

// ─── Error Classification ─────────────────────────────────────────────────────

function isRateLimitError(error) {
  const msg = (error?.message || '').toLowerCase();
  return msg.includes('rate limit') ||
         msg.includes('status 429') ||
         msg.includes('too many requests') ||
         msg.includes('overloaded');
}

function isAuthError(error) {
  const msg = (error?.message || '').toLowerCase();
  return msg.includes('unauthorized') ||
         msg.includes('not authenticated') ||
         msg.includes('authentication failed') ||
         msg.includes('credential');
}

function isSessionError(error) {
  const msg = (error?.message || '').toLowerCase();
  return (msg.includes('session') && (msg.includes('not found') || msg.includes('expired') || msg.includes('invalid'))) ||
         msg.includes('session error') ||
         msg.includes('session corrupt');
}

// ─── Request Body Parser ──────────────────────────────────────────────────────

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let totalBytes = 0;
    req.on('data', chunk => {
      totalBytes += chunk.length;
      if (totalBytes > MAX_BODY_BYTES) {
        req.destroy();
        reject(new Error('Request body too large'));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf-8')));
    req.on('error', reject);
  });
}

// ─── Chat Completions Handler ─────────────────────────────────────────────────

async function handleChatCompletion(req, res) {
  const startTime = Date.now();
  stats.total++;

  // Check backoff
  if (Date.now() < backoffUntil) {
    const retryAfter = Math.ceil((backoffUntil - Date.now()) / 1000);
    stats.rateLimits++;
    res.setHeader('Retry-After', String(retryAfter));
    sendError(res, 429, `Rate limited, retry after ${retryAfter}s`, 'rate_limit_error');
    log('warn', 'Request rejected (backoff active)', { retryAfter });
    return;
  }

  // Parse request body
  let body;
  try {
    const raw = await readBody(req);
    body = JSON.parse(raw);
  } catch (e) {
    const status = e.message === 'Request body too large' ? 413 : 400;
    sendError(res, status, e.message, 'invalid_request_error');
    return;
  }

  const messages = body.messages;
  if (!Array.isArray(messages) || messages.length === 0) {
    sendError(res, 400, 'messages array is required and must be non-empty', 'invalid_request_error');
    return;
  }

  // CLAUDE_MODEL env var overrides whatever the bot sends
  const model = process.env.CLAUDE_MODEL || body.model || DEFAULT_MODEL;

  // Extract only the current game state (session has the history)
  const { systemPrompt, prompt } = extractCurrentMessage(messages);

  // Detect system prompt changes — reset session if the bot's prompt changed
  if (sessionInitialized && systemPrompt && sessionSystemPrompt
      && systemPrompt !== sessionSystemPrompt) {
    resetSession('system prompt changed');
  }

  log('info', 'Request received', {
    model,
    sessionTurn: sessionTurnCount,
    promptChars: prompt.length,
    queueDepth: requestQueue.pending,
    sessionId: sessionId.substring(0, 8),
  });

  if (LOG_BODIES) {
    log('debug', 'Request prompt', { prompt });
  }

  // Route to manual mode or Claude CLI
  const ac = new AbortController();
  const timeoutHandle = setTimeout(() => ac.abort(), MANUAL_MODE ? MANUAL_TIMEOUT_MS : REQUEST_TIMEOUT_MS);
  try {
    const result = MANUAL_MODE
      ? await requestQueue.enqueue(() => callManual(systemPrompt, prompt))
      : await requestQueue.enqueue(() => callClaude(systemPrompt, prompt, model, ac.signal));

    clearTimeout(timeoutHandle);
    const latencyMs = Date.now() - startTime;
    stats.success++;
    stats.totalLatencyMs += latencyMs;
    clearBackoff();

    log('info', 'Request completed', {
      latencyMs,
      resultChars: result.resultText.length,
      costUsd: result.costUsd,
      sessionTurn: sessionTurnCount,
    });

    if (LOG_BODIES) {
      log('debug', 'Response body', { result: result.resultText });
    }

    sendJson(res, 200, wrapResponse(result.resultText, model));

    // Write structured training turn for distillation
    try {
      appendFileSync(TRAINING_LOG, JSON.stringify({
        turn: sessionTurnCount,
        ts: new Date().toISOString(),
        session: sessionId,
        game_state: prompt,
        response: result.resultText,
        latency_ms: latencyMs,
      }) + '\n');
    } catch {}

  } catch (error) {
    clearTimeout(timeoutHandle);
    const latencyMs = Date.now() - startTime;
    stats.errors++;
    stats.totalLatencyMs += latencyMs;

    const errMsg = error?.message || 'Unknown error';
    log('error', 'Request failed', { error: errMsg, latencyMs, sessionTurn: sessionTurnCount });

    if (isSessionError(error)) {
      // Session corrupted or lost — reset and retry on next request
      resetSession(errMsg);
      sendError(res, 500, `Session error (will retry with fresh session): ${errMsg}`, 'api_error');
    } else if (isRateLimitError(error)) {
      stats.rateLimits++;
      applyBackoff();
      const retryAfter = Math.ceil((backoffUntil - Date.now()) / 1000);
      res.setHeader('Retry-After', String(retryAfter));
      sendError(res, 429, errMsg, 'rate_limit_error');
    } else if (isAuthError(error)) {
      sendError(res, 401, `Authentication error: ${errMsg}. Run 'claude login' to authenticate.`, 'authentication_error');
    } else if (errMsg.includes('timeout') || errMsg.includes('Timeout')) {
      // Reset session if it was never established (first-request timeout)
      if (!sessionInitialized) {
        resetSession('first request timeout — session may be in unknown state');
      }
      sendError(res, 504, errMsg, 'timeout_error');
    } else {
      // After 3 consecutive errors, reset the session
      applyBackoff();
      if (consecutiveErrors >= 3) {
        resetSession(`${consecutiveErrors} consecutive errors: ${errMsg}`);
      }
      sendError(res, 500, errMsg, 'api_error');
    }
  }
}

// ─── HTTP Server ──────────────────────────────────────────────────────────────

let shuttingDown = false;

const server = createServer(async (req, res) => {
  try {
    if (shuttingDown) {
      sendError(res, 503, 'Server is shutting down', 'api_error');
      return;
    }

    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      });
      res.end();
      return;
    }

    const url = new URL(req.url, `http://localhost:${PORT}`);
    const path = url.pathname;

    // Health check
    if (req.method === 'GET' && path === '/health') {
      const avgLatency = stats.success > 0 ? Math.round(stats.totalLatencyMs / stats.success) : 0;
      sendJson(res, 200, {
        status: 'ok',
        uptime: Math.round(process.uptime()),
        model: DEFAULT_MODEL,
        session: {
          id: sessionId.substring(0, 8) + '...',
          initialized: sessionInitialized,
          turns: sessionTurnCount,
          resets: stats.sessionResets,
        },
        queue: { active: requestQueue.active, pending: requestQueue.pending },
        backoff: backoffUntil > Date.now() ? Math.ceil((backoffUntil - Date.now()) / 1000) : 0,
        stats: {
          total: stats.total,
          success: stats.success,
          errors: stats.errors,
          rateLimits: stats.rateLimits,
          avgLatencyMs: avgLatency
        }
      });
      return;
    }

    // Models list
    if (req.method === 'GET' && (path === '/v1/models' || path === '/models')) {
      sendJson(res, 200, {
        object: 'list',
        data: [{ id: DEFAULT_MODEL, object: 'model', owned_by: 'anthropic' }]
      });
      return;
    }

    // Session reset (manual)
    if (req.method === 'POST' && path === '/reset') {
      resetSession('manual reset');
      sendJson(res, 200, { status: 'ok', newSessionId: sessionId.substring(0, 8) + '...' });
      return;
    }

    // Chat completions
    if (req.method === 'POST' && (path === '/v1/chat/completions' || path === '/chat/completions')) {
      await handleChatCompletion(req, res);
      return;
    }

    sendError(res, 404, `Not found: ${req.method} ${path}`, 'invalid_request_error');
  } catch (err) {
    log('error', 'Unhandled server error', { error: err?.message || String(err) });
    if (!res.headersSent) {
      sendError(res, 500, 'Internal server error', 'api_error');
    }
  }
});

// ─── Graceful Shutdown ────────────────────────────────────────────────────────

function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  log('info', `Received ${signal}, shutting down gracefully...`);

  // Kill any in-flight subprocess
  if (activeProc) {
    try { activeProc.kill('SIGTERM'); } catch {}
  }

  server.close(() => {
    const avgLatency = stats.success > 0 ? Math.round(stats.totalLatencyMs / stats.success) : 0;
    log('info', 'Session summary', {
      uptime: Math.round(process.uptime()),
      sessionId: sessionId.substring(0, 8),
      sessionTurns: sessionTurnCount,
      ...stats,
      avgLatencyMs: avgLatency
    });
    process.exit(0);
  });

  setTimeout(() => {
    log('warn', 'Forced shutdown after timeout');
    process.exit(1);
  }, 30_000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

// Catch unhandled rejections to prevent silent crashes
process.on('unhandledRejection', (reason) => {
  log('error', 'Unhandled rejection', { error: String(reason) });
});

// ─── Start ────────────────────────────────────────────────────────────────────

server.listen(PORT, () => {
  console.log('');
  console.log('╔══════════════════════════════════════════════════════════');
  console.log(`║  OSRS Claude Proxy ${MANUAL_MODE ? '(MANUAL MODE)' : '(Persistent Session)'}`);
  console.log('╠══════════════════════════════════════════════════════════');
  console.log(`║  Port:      ${PORT}`);
  console.log(`║  Model:     ${MANUAL_MODE ? 'MANUAL (Claude Code session)' : DEFAULT_MODEL}`);
  console.log(`║  Session:   ${sessionId.substring(0, 8)}...`);
  console.log(`║  Logging:   ${LOG_BODIES ? 'verbose (bodies)' : 'standard'}`);
  console.log(`║  Training:  ${TRAINING_LOG}`);
  console.log(`║  Wiki:      ${wikiContext ? `${(wikiContext.length / 1024).toFixed(0)} KB loaded` : 'none'}`);
  if (MANUAL_MODE) {
    console.log('╠══════════════════════════════════════════════════════════');
    console.log(`║  State →    ${MANUAL_STATE_FILE}`);
    console.log(`║  Response ← ${MANUAL_RESPONSE_FILE}`);
    console.log(`║  Timeout:   ${MANUAL_TIMEOUT_MS / 1000}s per turn`);
  }
  console.log('╠══════════════════════════════════════════════════════════');
  console.log(`║  Bot config: apiBaseUrl = http://localhost:${PORT}/v1`);
  console.log(`║  Health:     curl http://localhost:${PORT}/health`);
  console.log(`║  Reset:      curl -X POST http://localhost:${PORT}/reset`);
  console.log('╚══════════════════════════════════════════════════════════');
  console.log('');
});
