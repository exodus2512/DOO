/**
 * DOO Browser Worker - Live View via CDP Screencast
 * Streams JPEG frames over WebSocket; relays mouse/keyboard back to the page.
 * One controller per task. Protected by a short-lived token.
 */
'use strict';
const crypto = require('crypto');

// In-memory token store: token -> { taskId, expiresAt }
const liveTokens = new Map();

const TOKEN_TTL_MS = 10 * 60 * 1000; // 10 minutes

function issueToken(taskId) {
  const token = crypto.randomBytes(20).toString('hex');
  liveTokens.set(token, { taskId, expiresAt: Date.now() + TOKEN_TTL_MS });
  return token;
}

function validateToken(token) {
  const entry = liveTokens.get(token);
  if (!entry) return null;
  if (Date.now() > entry.expiresAt) { liveTokens.delete(token); return null; }
  return entry.taskId;
}

function cleanExpiredTokens() {
  const now = Date.now();
  for (const [tok, entry] of liveTokens) {
    if (now > entry.expiresAt) liveTokens.delete(tok);
  }
}
setInterval(cleanExpiredTokens, 60_000).unref?.();

/**
 * Attach a live CDP screencast session to a Playwright page.
 * @param {import('ws').WebSocket} ws
 * @param {import('playwright').Page} page
 */
async function startLiveSession(ws, page) {
  const cdp = await page.context().newCDPSession(page);

  await cdp.send('Page.startScreencast', {
    format: 'jpeg',
    quality: 60,
    everyNthFrame: 2,
  });

  // Forward frames to WebSocket
  cdp.on('Page.screencastFrame', async ({ data, metadata, sessionId }) => {
    if (ws.readyState !== ws.constructor.OPEN) return;
    const msg = JSON.stringify({
      type: 'frame',
      data,
      w: metadata.deviceWidth,
      h: metadata.deviceHeight,
      ts: metadata.timestamp,
    });
    ws.send(msg);
    await cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
  });

  // Relay input from client → page
  ws.on('message', async (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    try {
      if (msg.type === 'mouse') {
        // Validate coordinates: must be numbers within [0, 8192]
        const x = clamp(Number(msg.x), 0, 8192);
        const y = clamp(Number(msg.y), 0, 8192);
        const button = ['left', 'right', 'middle', 'none'].includes(msg.button) ? msg.button : 'none';
        const mouseType = ['mousePressed', 'mouseReleased', 'mouseMoved', 'mouseWheel'].includes(msg.action)
          ? msg.action : 'mouseMoved';
        await cdp.send('Input.dispatchMouseEvent', {
          type: mouseType,
          x, y,
          button,
          clickCount: mouseType === 'mousePressed' ? 1 : 0,
          modifiers: sanitizeModifiers(msg.modifiers),
        });
      } else if (msg.type === 'wheel') {
        const x = clamp(Number(msg.x), 0, 8192);
        const y = clamp(Number(msg.y), 0, 8192);
        await cdp.send('Input.dispatchMouseEvent', {
          type: 'mouseWheel',
          x, y,
          deltaX: clamp(Number(msg.deltaX || 0), -1000, 1000),
          deltaY: clamp(Number(msg.deltaY || 0), -1000, 1000),
        });
      } else if (msg.type === 'key') {
        const keyType = ['keyDown', 'keyUp', 'rawKeyDown'].includes(msg.action) ? msg.action : 'keyDown';
        const code = sanitizeKeyCode(msg.code);
        if (!code) return;
        await cdp.send('Input.dispatchKeyEvent', {
          type: keyType,
          code,
          key: sanitizeKey(msg.key),
          windowsVirtualKeyCode: Number(msg.windowsVirtualKeyCode) || 0,
          modifiers: sanitizeModifiers(msg.modifiers),
        });
      } else if (msg.type === 'text') {
        const text = String(msg.text || '').substring(0, 200); // cap length
        if (text) await cdp.send('Input.insertText', { text });
      }
    } catch (e) {
      // Input relay errors are non-fatal — log at debug level only
      console.debug('[LiveView] input relay error:', e.message);
    }
  });

  // Cleanup
  function cleanup() {
    cdp.send('Page.stopScreencast').catch(() => {});
    cdp.detach().catch(() => {});
  }
  ws.on('close', cleanup);
  ws.on('error', cleanup);

  return { cdp, cleanup };
}

function clamp(v, min, max) {
  return Math.max(min, Math.min(max, isFinite(v) ? v : min));
}

function sanitizeModifiers(raw) {
  // CDP modifier bitmask: 1=Alt, 2=Ctrl, 4=Meta, 8=Shift
  return typeof raw === 'number' ? (raw & 0b1111) : 0;
}

const ALLOWED_KEY_CODES = new Set([
  'KeyA','KeyB','KeyC','KeyD','KeyE','KeyF','KeyG','KeyH','KeyI','KeyJ','KeyK','KeyL','KeyM',
  'KeyN','KeyO','KeyP','KeyQ','KeyR','KeyS','KeyT','KeyU','KeyV','KeyW','KeyX','KeyY','KeyZ',
  'Digit0','Digit1','Digit2','Digit3','Digit4','Digit5','Digit6','Digit7','Digit8','Digit9',
  'Enter','Backspace','Delete','Tab','Escape','Space','ArrowLeft','ArrowRight','ArrowUp','ArrowDown',
  'Home','End','PageUp','PageDown','ShiftLeft','ShiftRight','ControlLeft','ControlRight',
  'AltLeft','AltRight','MetaLeft','MetaRight','Period','Comma','Slash','Backslash','Semicolon',
  'Quote','BracketLeft','BracketRight','Backquote','Minus','Equal','F1','F2','F3','F4','F5',
  'F6','F7','F8','F9','F10','F11','F12','NumpadEnter','NumpadDecimal',
]);

function sanitizeKeyCode(code) {
  return ALLOWED_KEY_CODES.has(code) ? code : null;
}

function sanitizeKey(key) {
  if (typeof key !== 'string') return '';
  return key.substring(0, 20).replace(/[^\x20-\x7E]/g, '');
}

module.exports = { issueToken, validateToken, startLiveSession };
