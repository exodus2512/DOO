/**
 * DOO Browser Worker - Session Manager
 * Manages Playwright browser contexts per task.
 */

const { chromium } = require('playwright');

const SESSION_TTL_MS = Number(process.env.SESSION_TTL_MS) || 60 * 60 * 1000;  // 60min absolute
const SESSION_IDLE_MS = Number(process.env.SESSION_IDLE_MS) || 15 * 60 * 1000; // 15min idle
const PARKED_IDLE_MS = Number(process.env.PARKED_IDLE_MS) || 35 * 60 * 1000;  // 35min for parked (waiting-for-user) sessions
const REAPER_INTERVAL_MS = 60 * 1000;

class SessionManager {
  constructor() {
    this.browser = null;
    this.sessions = new Map(); // taskId -> { context, page, sessionId, cancelToken, createdAt, lastActivity, parked }
    this._startReaper();
  }

  _startReaper() {
    this._reaperTimer = setInterval(() => this._reapExpiredSessions(), REAPER_INTERVAL_MS);
    if (this._reaperTimer.unref) this._reaperTimer.unref();
  }

  async _reapExpiredSessions() {
    const now = Date.now();
    for (const [taskId, session] of this.sessions) {
      const age = now - session.createdAt;
      const idle = now - session.lastActivity;
      const idleLimit = session.parked ? PARKED_IDLE_MS : SESSION_IDLE_MS;
      if (age > SESSION_TTL_MS || idle > idleLimit) {
        console.log(`[BrowserWorker] Reaping expired session for task ${taskId} (age=${Math.round(age/1000)}s, idle=${Math.round(idle/1000)}s, parked=${!!session.parked})`);
        await this.closeSession(taskId);
      }
    }
  }

  async ensureBrowser() {
    if (!this.browser || !this.browser.isConnected()) {
      console.log('[BrowserWorker] Launching Chromium...');
      this.browser = await chromium.launch({
        headless: process.env.HEADLESS !== 'false',
        args: [
          '--no-sandbox',
          '--disable-setuid-sandbox',
          '--disable-dev-shm-usage',
          '--disable-gpu',
          '--disable-blink-features=AutomationControlled',
        ]
      });
      console.log('[BrowserWorker] Browser ready');
    }
    return this.browser;
  }

  async initSession(taskId) {
    // Close existing session for this task if any
    await this.closeSession(taskId);

    const browser = await this.ensureBrowser();
    
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      ignoreHTTPSErrors: false,
      // Permissions: none by default
      geolocation: undefined,
    });

    // Stealth: override navigator.webdriver
    await context.addInitScript(() => {
      Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    });

    // Block ads/trackers to reduce noise
    await context.route('**/*.{png,jpg,gif,webp,svg,ico,woff,woff2}', async (route) => {
      // Allow images on regular pages but block heavy ad networks
      const url = route.request().url();
      if (url.includes('doubleclick') || url.includes('googlesyndication') || 
          url.includes('amazon-adsystem') || url.includes('adservice')) {
        await route.abort();
      } else {
        await route.continue();
      }
    });

    const page = await context.newPage();
    
    // Set reasonable timeouts
    page.setDefaultTimeout(15000);
    page.setDefaultNavigationTimeout(30000);

    const sessionId = `session-${taskId}-${Date.now()}`;
    const cancelToken = { cancelled: false };
    const now = Date.now();

    this.sessions.set(taskId, {
      context, page, sessionId, cancelToken,
      createdAt: now, lastActivity: now,
      lastObservation: null, // stores the most recent full doo.perception.v1 observation
      parked: false, // true when task is WAITING_FOR_USER — browser stays alive, idle timeout extended
    });
    
    console.log(`[BrowserWorker] Session ${sessionId} initialized for task ${taskId}`);
    return sessionId;
  }

  getSession(taskId) {
    const session = this.sessions.get(taskId);
    if (session) session.lastActivity = Date.now();
    return session;
  }

  async closeSession(taskId) {
    const session = this.sessions.get(taskId);
    if (session) {
      try {
        await session.context.close();
      } catch (e) {
        console.warn(`[BrowserWorker] Error closing session for ${taskId}:`, e.message);
      }
      this.sessions.delete(taskId);
      console.log(`[BrowserWorker] Session closed for task ${taskId}`);
    }
  }

  parkSession(taskId) {
    const session = this.sessions.get(taskId);
    if (session) {
      session.parked = true;
      session.lastActivity = Date.now();
      console.log(`[BrowserWorker] Session parked for task ${taskId} (browser stays alive)`);
    }
    return !!session;
  }

  unparkSession(taskId) {
    const session = this.sessions.get(taskId);
    if (session) {
      session.parked = false;
      session.lastActivity = Date.now();
      console.log(`[BrowserWorker] Session unparked for task ${taskId}`);
    }
    return !!session;
  }

  isParked(taskId) {
    const session = this.sessions.get(taskId);
    return session ? session.parked : false;
  }

  cancelTask(taskId) {
    const session = this.sessions.get(taskId);
    if (session) {
      session.cancelToken.cancelled = true;
    }
  }

  isCancelled(taskId) {
    const session = this.sessions.get(taskId);
    return session ? session.cancelToken.cancelled : false;
  }

  async closeAll() {
    if (this._reaperTimer) clearInterval(this._reaperTimer);
    for (const [taskId] of this.sessions) {
      await this.closeSession(taskId);
    }
    if (this.browser) {
      await this.browser.close().catch(() => {});
      this.browser = null;
    }
  }
}

module.exports = new SessionManager(); // Singleton
