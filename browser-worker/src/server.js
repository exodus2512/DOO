/**
 * DOO Browser Worker - Express HTTP Server
 * Receives action requests from the Spring backend and executes them via Playwright.
 */

const express = require('express');
const sessionManager = require('./sessions');
const { executeAction, getPageState } = require('./actions');
const { validateUrl, validateActionType } = require('./security');

const app = express();
app.use(express.json({ limit: '10mb' }));

const PORT = process.env.BROWSER_WORKER_PORT || 3001;
const WORKER_SECRET = process.env.BROWSER_WORKER_SECRET || 'dev-secret-change-in-production';

// ─── Authentication middleware ──────────────────────────────────────────────
function requireSecret(req, res, next) {
  const secret = req.headers['x-worker-secret'];
  if (secret !== WORKER_SECRET) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}

app.use(requireSecret);

// ─── Health ─────────────────────────────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    service: 'doo-browser-worker',
    activeSessions: sessionManager.sessions.size,
    timestamp: new Date().toISOString()
  });
});

// ─── Session Init ────────────────────────────────────────────────────────────
app.post('/session/init', async (req, res) => {
  const { taskId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  try {
    const sessionId = await sessionManager.initSession(taskId);
    console.log(`[/session/init] Session ${sessionId} created for task ${taskId}`);
    res.json({ success: true, sessionId });
  } catch (error) {
    console.error(`[/session/init] Error for task ${taskId}:`, error.message);
    res.status(500).json({ success: false, error: error.message });
  }
});

// ─── Get State ───────────────────────────────────────────────────────────────
app.post('/session/state', async (req, res) => {
  const { taskId, sessionId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  const session = sessionManager.getSession(taskId);
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }

  try {
    const state = await getPageState(session.page, true);
    // Take a screenshot
    const screenshot = await session.page.screenshot({ 
      type: 'jpeg', quality: 50, fullPage: false 
    }).catch(() => null);
    
    res.json({
      ...state,
      screenshot: screenshot ? screenshot.toString('base64') : null
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// ─── Execute Action ───────────────────────────────────────────────────────────
app.post('/action/execute', async (req, res) => {
  const { taskId, sessionId, actionType, args } = req.body;
  
  if (!taskId || !actionType) {
    return res.status(400).json({ error: 'taskId and actionType are required' });
  }

  // Check cancellation
  if (sessionManager.isCancelled(taskId)) {
    return res.status(409).json({ success: false, error: 'Task has been cancelled' });
  }

  const session = sessionManager.getSession(taskId);
  if (!session) {
    return res.status(404).json({ success: false, error: 'Session not found. Task may have been cancelled.' });
  }

  console.log(`[/action/execute] Task ${taskId} | ${actionType} | args: ${JSON.stringify(args || {}).substring(0, 100)}`);

  try {
    const result = await executeAction(session.page, actionType, args || {});
    res.json(result);
  } catch (error) {
    console.error(`[/action/execute] Error for task ${taskId}:`, error.message);
    res.status(500).json({ success: false, error: error.message });
  }
});

// ─── Close Session ───────────────────────────────────────────────────────────
app.post('/session/close', async (req, res) => {
  const { taskId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  await sessionManager.closeSession(taskId);
  res.json({ success: true });
});

// ─── Cancel Task ─────────────────────────────────────────────────────────────
app.post('/task/cancel', async (req, res) => {
  const { taskId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  sessionManager.cancelTask(taskId);
  await sessionManager.closeSession(taskId);
  res.json({ success: true });
});

// ─── Start Server ─────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`[DOO Browser Worker] Listening on port ${PORT}`);
  console.log(`[DOO Browser Worker] Headless: ${process.env.HEADLESS !== 'false'}`);
});

// ─── Graceful shutdown ────────────────────────────────────────────────────────
process.on('SIGTERM', async () => {
  console.log('[BrowserWorker] SIGTERM received, shutting down...');
  await sessionManager.closeAll();
  process.exit(0);
});

process.on('SIGINT', async () => {
  console.log('[BrowserWorker] SIGINT received, shutting down...');
  await sessionManager.closeAll();
  process.exit(0);
});

module.exports = app;
