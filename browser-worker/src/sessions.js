/**
 * DOO Browser Worker - Session Manager
 * Manages Playwright browser contexts per task.
 *
 * HEADED / VISIBLE mode
 *   HEADLESS=false  — explicit headless off
 *   HEADED=true     — shorthand synonym for HEADLESS=false
 *   Default: headless on (CI / Docker)
 *
 * PERSISTENT PROFILE mode (default on)
 *   PROFILE_MODE=persistent  — use launchPersistentContext per domain;
 *                              profile dir = STORAGE_STATE_DIR/profiles/<domain>
 *   PROFILE_MODE=storage     — use newContext + storageState JSON (legacy)
 *   STORAGE_STATE_DIR        — base dir (default ~/.doo-worker/storage-states)
 *
 * LOCALE / TIMEZONE
 *   BROWSER_LOCALE    — BCP-47 locale (default: en-IN)
 *   BROWSER_TIMEZONE  — IANA timezone (default: Asia/Kolkata)
 *
 * VIEWPORT
 *   BROWSER_WIDTH  / BROWSER_HEIGHT (default: 1280 × 800)
 */

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const STORAGE_STATE_DIR = process.env.STORAGE_STATE_DIR
  || path.join(os.homedir(), '.doo-worker', 'storage-states');
fs.mkdirSync(STORAGE_STATE_DIR, { recursive: true });

const PROFILE_DIR = path.join(STORAGE_STATE_DIR, 'profiles');
fs.mkdirSync(PROFILE_DIR, { recursive: true });

// headed = HEADLESS=false OR HEADED=true
const IS_HEADED = process.env.HEADLESS === 'false' || process.env.HEADED === 'true';
const PROFILE_MODE = process.env.PROFILE_MODE || 'persistent'; // 'persistent' | 'storage'

const BROWSER_LOCALE   = process.env.BROWSER_LOCALE   || 'en-IN';
const BROWSER_TIMEZONE = process.env.BROWSER_TIMEZONE || 'Asia/Kolkata';
const BROWSER_WIDTH    = Number(process.env.BROWSER_WIDTH)  || 1280;
const BROWSER_HEIGHT   = Number(process.env.BROWSER_HEIGHT) || 800;

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

  _launchArgs() {
    const args = [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
    ];
    // Keep AutomationControlled disabled — it prevents some bot-detection flags
    // in real browsers without injecting fake JS properties.
    args.push('--disable-blink-features=AutomationControlled');
    return args;
  }

  async ensureBrowser() {
    // In persistent-profile mode we don't share a single browser instance —
    // each domain gets its own launchPersistentContext (see initSession).
    if (PROFILE_MODE !== 'persistent') {
      if (!this.browser || !this.browser.isConnected()) {
        console.log(`[BrowserWorker] Launching Chromium (headless=${!IS_HEADED})...`);
        this.browser = await chromium.launch({
          headless: !IS_HEADED,
          args: this._launchArgs(),
        });
        console.log('[BrowserWorker] Browser ready');
      }
      return this.browser;
    }
    return null; // persistent mode: no shared browser
  }

  async initSession(taskId, domain = null) {
    // Close existing session for this task if any
    await this.closeSession(taskId);

    const contextOptions = {
      viewport: { width: BROWSER_WIDTH, height: BROWSER_HEIGHT },
      locale: BROWSER_LOCALE,
      timezoneId: BROWSER_TIMEZONE,
      ignoreHTTPSErrors: false,
    };
    // No hardcoded userAgent: let Playwright use the real browser UA.
    // No navigator.webdriver addInitScript: setting userAgent to a fake value
    // or patching navigator is more detectable than the default Playwright UA.

    let context;
    if (PROFILE_MODE === 'persistent' && domain) {
      // Each domain gets its own persistent profile directory.
      // This survives restarts and stores cookies, localStorage, IndexedDB.
      const safeDomain = domain.replace(/[^a-z0-9.-]/gi, '_');
      const profileDir = path.join(PROFILE_DIR, safeDomain);
      fs.mkdirSync(profileDir, { recursive: true });
      console.log(`[BrowserWorker] Using persistent profile for ${domain} (${profileDir})`);
      context = await chromium.launchPersistentContext(profileDir, {
        headless: !IS_HEADED,
        args: this._launchArgs(),
        ...contextOptions,
      });
    } else {
      // Fallback: load storageState JSON if available, or start fresh
      const storageState = domain ? await this.loadStorageState(domain) : null;
      if (storageState) contextOptions.storageState = storageState;
      const browser = await this.ensureBrowser();
      context = await browser.newContext(contextOptions);
    }

    // Block heavy ad-network domains to reduce noise (not images in general)
    await context.route('**', async (route) => {
      const url = route.request().url();
      if (url.includes('doubleclick') || url.includes('googlesyndication') ||
          url.includes('amazon-adsystem') || url.includes('adservice.google')) {
        await route.abort();
      } else {
        await route.continue();
      }
    });

    const page = context.pages()[0] || await context.newPage();
    page.setDefaultTimeout(15000);
    page.setDefaultNavigationTimeout(30000);

    const sessionId = `session-${taskId}-${Date.now()}`;
    const cancelToken = { cancelled: false };
    const now = Date.now();

    this.sessions.set(taskId, {
      context, page, sessionId, cancelToken,
      domain: domain || null,
      createdAt: now, lastActivity: now,
      lastObservation: null,
      parked: false,
    });

    console.log(`[BrowserWorker] Session ${sessionId} initialized for task ${taskId}` +
      (domain ? ` (domain=${domain}, mode=${PROFILE_MODE})` : ''));
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
      // Save storage state before closing (storage mode only; persistent mode auto-saves to profile dir)
      if (PROFILE_MODE !== 'persistent' && session.domain) {
        await this.saveStorageState(taskId, session.domain).catch(() => {});
      }
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

  async bringToFront(taskId) {
    const session = this.sessions.get(taskId);
    if (!session || !session.page) return false;
    try {
      await session.page.bringToFront();
      return true;
    } catch (e) {
      console.warn(`[BrowserWorker] bringToFront failed for ${taskId}:`, e.message);
      return false;
    }
  }

  async saveStorageState(taskId, domain) {
    const session = this.sessions.get(taskId);
    if (!session) return false;
    try {
      const safeDomain = domain.replace(/[^a-z0-9.-]/gi, '_');
      const filePath = path.join(STORAGE_STATE_DIR, `${safeDomain}.json`);
      await session.context.storageState({ path: filePath });
      console.log(`[BrowserWorker] Saved storage state for ${domain} → ${filePath}`);
      return true;
    } catch (e) {
      console.warn(`[BrowserWorker] Could not save storage state for ${domain}:`, e.message);
      return false;
    }
  }

  async loadStorageState(domain) {
    try {
      const safeDomain = domain.replace(/[^a-z0-9.-]/gi, '_');
      const filePath = path.join(STORAGE_STATE_DIR, `${safeDomain}.json`);
      if (!fs.existsSync(filePath)) return null;
      const state = JSON.parse(fs.readFileSync(filePath, 'utf8'));
      if (!state.cookies && !state.origins) return null;
      console.log(`[BrowserWorker] Loaded storage state for ${domain}`);
      return state;
    } catch (e) {
      console.warn(`[BrowserWorker] Could not load storage state for ${domain}:`, e.message);
      return null;
    }
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
