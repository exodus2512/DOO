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
  const { taskId, domain } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  try {
    // Load saved storage state if a domain is provided (enables session reuse)
    let storageState = null;
    if (domain) {
      storageState = await sessionManager.loadStorageState(domain);
      if (storageState) console.log(`[/session/init] Using saved session for domain: ${domain}`);
    }
    const sessionId = await sessionManager.initSession(taskId, storageState);
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

  const safeArgs = args ? { ...args } : {};
  delete safeArgs._observation;
  if (safeArgs.value && actionType === 'FILL') {
    safeArgs.value = safeArgs.value.length > 0 ? '[REDACTED]' : '';
  }
  console.log(`[/action/execute] Task ${taskId} | ${actionType} | args: ${JSON.stringify(safeArgs).substring(0, 200)}`);

  try {
    const result = await executeAction(session.page, actionType, args || {}, taskId, session);

    if (result.observation) {
      // Capture a screenshot for every action if the observation didn't include one
      // Password fields are masked via CSS injection before capture
      if (!result.observation.screenshot?.base64) {
        try {
          await session.page.evaluate(() => {
            const s = document.createElement('style'); s.id = '__doo_pwd_mask';
            s.textContent = 'input[type="password"]{-webkit-text-security:disc!important;color:transparent!important;text-shadow:0 0 0 #000!important}';
            document.head.appendChild(s);
          }).catch(() => {});
          const buf = await session.page.screenshot({ type: 'jpeg', quality: 50, fullPage: false });
          result.observation.screenshot = { available: true, base64: buf.toString('base64'), mime_type: 'image/jpeg' };
          await session.page.evaluate(() => { const s = document.getElementById('__doo_pwd_mask'); if (s) s.remove(); }).catch(() => {});
        } catch { /* best effort */ }
      }

      // Cache full observation on the session for element resolution
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
        value: el.value || '',
        placeholder: el.placeholder || null,
        type: el.type,
        href: el.href || null,
        enabled: el.enabled,
        required: el.required || false,
        autocomplete: el.autocomplete || null,
        name: el.name || null,
        id: el.id || null,
        ariaRequired: el.ariaRequired || null,
      }));
      // Forward challenge detection (CAPTCHA/OTP) to backend
      if (obs.challenges) {
        result.challenges = obs.challenges;
      }
      // Forward scroll position for pagination awareness
      if (obs.scroll) {
        result.scroll = obs.scroll;
      }
    }

    console.log(`[/action/execute] Task ${taskId} | ${actionType} | success=${result.success} | obsId=${result.observationId || 'none'} | url=${(result.currentUrl || '').substring(0, 60)}`);
    res.json(result);
  } catch (error) {
    console.error(`[/action/execute] Error for task ${taskId}:`, error.message);
    res.status(500).json({ success: false, error: error.message });
  }
});

// ─── Park Session (WAITING_FOR_USER) ─────────────────────────────────────────
app.post('/session/park', async (req, res) => {
  const { taskId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  const success = sessionManager.parkSession(taskId);
  if (!success) {
    return res.status(404).json({ error: 'Session not found' });
  }
  res.json({ success: true, parked: true });
});

// ─── Unpark Session (resume from WAITING_FOR_USER) ──────────────────────────
app.post('/session/unpark', async (req, res) => {
  const { taskId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId is required' });

  const session = sessionManager.getSession(taskId);
  if (!session) {
    return res.status(404).json({ error: 'Session not found — may have timed out' });
  }

  sessionManager.unparkSession(taskId);

  // Return a fresh observation so the backend can resume with current page state
  try {
    const { buildObservation } = require('./perception');
    const observation = await buildObservation(session.page, taskId, { includeScreenshot: true });
    session.lastObservation = observation;

    res.json({
      success: true,
      parked: false,
      currentUrl: observation.url,
      pageTitle: observation.title,
      pageText: observation.page_text?.text || '',
      observationId: observation.observation_id,
      interactiveElements: (observation.interactive_elements || []).map(el => ({
        index: el.index,
        tag: el._locator?.tagName || el.type,
        role: el.role,
        text: (el.text || '').substring(0, 80),
        label: (el.label || '').substring(0, 80),
        value: el.value || '',
        placeholder: el.placeholder || null,
        type: el.type,
        href: el.href || null,
        enabled: el.enabled,
        required: el.required || false,
        autocomplete: el.autocomplete || null,
        name: el.name || null,
        id: el.id || null,
        ariaRequired: el.ariaRequired || null,
      })),
      screenshot: observation.screenshot?.base64 || null,
    });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// ─── Bring Session to Front (human handoff with HEADLESS=false) ───────────────
app.post('/session/bring-to-front', async (req, res) => {
  const { taskId } = req.body;
  if (!taskId) return res.status(400).json({ error: 'taskId required' });
  const success = await sessionManager.bringToFront(taskId);
  res.json({ success });
});

// ─── Save Storage State (session reuse across tasks) ─────────────────────────
app.post('/session/save-storage', async (req, res) => {
  const { taskId, domain } = req.body;
  if (!taskId || !domain) return res.status(400).json({ error: 'taskId and domain required' });
  const success = await sessionManager.saveStorageState(taskId, domain);
  res.json({ success });
});

// ─── Fill OTP code (bypasses the model — code goes directly to the field) ────
app.post('/action/fill-otp', async (req, res) => {
  const { taskId, code, inputName } = req.body;
  if (!taskId || !code) return res.status(400).json({ error: 'taskId and code are required' });

  const session = sessionManager.getSession(taskId);
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }

  try {
    const page = session.page;
    let filled = false;

    // Try to find the OTP input by name/id first
    if (inputName) {
      const byName = page.locator(`input[name="${inputName}"], input[id="${inputName}"]`);
      if (await byName.count() > 0) {
        await byName.first().fill(code, { timeout: 5000 });
        filled = true;
      }
    }

    // Fallback: find OTP-like inputs
    if (!filled) {
      const otpInput = page.locator(
        'input[name*="otp" i], input[name*="code" i], input[name*="totp" i], ' +
        'input[id*="otp" i], input[id*="code" i], input[id*="totp" i], ' +
        'input[placeholder*="code" i], input[placeholder*="otp" i], ' +
        'input[aria-label*="code" i], input[aria-label*="otp" i], ' +
        'input[inputmode="numeric"][maxlength]'
      );
      if (await otpInput.count() > 0) {
        await otpInput.first().fill(code, { timeout: 5000 });
        filled = true;
      }
    }

    if (!filled) {
      return res.status(404).json({ success: false, error: 'Could not find OTP input field' });
    }

    console.log(`[/action/fill-otp] Task ${taskId} | OTP code filled (code redacted)`);
    await page.waitForTimeout(500);

    const { buildObservation } = require('./perception');
    const observation = await buildObservation(page, taskId, { includeScreenshot: true });
    session.lastObservation = observation;

    res.json({
      success: true,
      result: 'OTP code filled',
      currentUrl: observation.url,
      pageTitle: observation.title,
      observationId: observation.observation_id,
    });
  } catch (error) {
    console.error(`[/action/fill-otp] Error for task ${taskId}:`, error.message);
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
