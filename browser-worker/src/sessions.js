/**
 * DOO Browser Worker - Session Manager
 * Manages Playwright browser contexts per task.
 */

const { chromium } = require('playwright');

class SessionManager {
  constructor() {
    this.browser = null;
    this.sessions = new Map(); // taskId -> { context, page, sessionId, cancelToken }
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
          '--disable-web-security', // Allow cross-origin for research tasks
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

    this.sessions.set(taskId, { context, page, sessionId, cancelToken });
    
    console.log(`[BrowserWorker] Session ${sessionId} initialized for task ${taskId}`);
    return sessionId;
  }

  getSession(taskId) {
    return this.sessions.get(taskId);
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
