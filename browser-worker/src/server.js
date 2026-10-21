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

// ─── Observe (structured perception) ─────────────────────────────────────────
app.post('/session/observe', async (req, res) => {
  const { taskId, includeScreenshot } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  const session = sessionManager.getSession(taskId);
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }

  try {
    const { buildObservation } = require('./perception');
    const observation = await buildObservation(session.page, taskId, {
      includeScreenshot: includeScreenshot ?? true,
    });
    res.json(observation);
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
    const result = await executeAction(session.page, actionType, args || {}, taskId, session);

    if (result.observation) {
      // Cache full observation on the session for stale-check and element resolution
      session.lastObservation = result.observation;
      const obs = result.observation;
      // Flatten into legacy fields for backward compat
      result.currentUrl = obs.url;
      result.pageTitle = obs.title;
      result.pageText = obs.page_text?.text || '';
      result.accessibilityTree = JSON.stringify(
        (obs.interactive_elements || []).slice(0, 30), null, 2
      );
      if (obs.screenshot?.base64 && !result.screenshot) {
        result.screenshot = obs.screenshot.base64;
      }
      // Send the structured observation for element-index-based actions
      result.observationId = obs.observation_id;
      result.interactiveElements = (obs.interactive_elements || []).map(el => ({
        index: el.index,
        tag: el._locator?.tagName || el.type,
        role: el.role,
        text: (el.text || '').substring(0, 80),
        label: (el.label || '').substring(0, 80),
        placeholder: el.placeholder || null,
        type: el.type,
        href: el.href || null,
        enabled: el.enabled,
      }));
    }

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
  // Don't close the session here — the backend's finally block calls /session/close.
  // Closing eagerly races with in-flight actions, causing "session not found" errors.
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
