/**
 * Browser Worker — Session Manager unit tests (Node built-in test runner)
 */
const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

describe('SessionManager', () => {
  describe('session expiry logic', () => {
    test('SESSION_TTL_MS defaults to 60 minutes', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('SESSION_TTL_MS'), 'SESSION_TTL_MS constant should be defined');
      assert.ok(source.includes('60 * 60 * 1000'), 'TTL should be 60 minutes');
    });

    test('SESSION_IDLE_MS defaults to 15 minutes', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('SESSION_IDLE_MS'), 'SESSION_IDLE_MS constant should be defined');
      assert.ok(source.includes('15 * 60 * 1000'), 'Idle timeout should be 15 minutes');
    });

    test('REAPER_INTERVAL_MS is 1 minute', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('REAPER_INTERVAL_MS'), 'REAPER_INTERVAL_MS constant should be defined');
      assert.ok(source.includes('60 * 1000'), 'Reaper interval should be 1 minute');
    });

    test('_reapExpiredSessions method exists', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('_reapExpiredSessions'), 'Reaper method should be defined');
    });

    test('getSession updates lastActivity timestamp', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('lastActivity'), 'Sessions should track lastActivity');
      assert.ok(source.includes('session.lastActivity = Date.now()'), 'getSession should update lastActivity');
    });

    test('initSession sets createdAt and lastActivity', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('createdAt: now'), 'Session should store createdAt');
      assert.ok(source.includes('lastActivity: now'), 'Session should store lastActivity');
    });

    test('--disable-web-security is NOT in browser launch args', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(!source.includes('--disable-web-security'), 'Should not disable web security');
    });
  });

  describe('closeAll cleans up reaper', () => {
    test('closeAll clears reaper timer', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('clearInterval(this._reaperTimer)'), 'closeAll should clear reaper timer');
    });
  });

  describe('headed mode and persistent profile config', () => {
    const fs = require('fs');
    const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');

    test('IS_HEADED honours HEADLESS=false and HEADED=true', () => {
      assert.ok(source.includes("process.env.HEADLESS === 'false'"), 'HEADLESS=false check missing');
      assert.ok(source.includes("process.env.HEADED === 'true'"), 'HEADED=true check missing');
      assert.ok(source.includes('IS_HEADED'), 'IS_HEADED constant missing');
    });

    test('PROFILE_MODE defaults to persistent', () => {
      assert.ok(source.includes("PROFILE_MODE = process.env.PROFILE_MODE || 'persistent'"), 'PROFILE_MODE default wrong');
    });

    test('persistent mode uses launchPersistentContext with profile dir', () => {
      assert.ok(source.includes('launchPersistentContext'), 'launchPersistentContext missing');
      assert.ok(source.includes('PROFILE_DIR'), 'PROFILE_DIR missing');
    });

    test('locale and timezone come from env with sensible defaults', () => {
      assert.ok(source.includes("BROWSER_LOCALE"), 'BROWSER_LOCALE constant missing');
      assert.ok(source.includes("BROWSER_TIMEZONE"), 'BROWSER_TIMEZONE constant missing');
      assert.ok(source.includes("'en-IN'"), 'default locale must be en-IN');
      assert.ok(source.includes("'Asia/Kolkata'"), 'default timezone must be Asia/Kolkata');
    });

    test('hardcoded userAgent string removed', () => {
      assert.ok(!source.includes('Mozilla/5.0 (Windows NT'), 'hardcoded userAgent must be removed');
    });

    test('navigator.webdriver addInitScript removed', () => {
      // Must not exist as an await statement (executable code); a comment is fine.
      assert.ok(!source.includes('await context.addInitScript'), 'await context.addInitScript call must be removed');
    });

    test('humanPause exported from actions for test use', () => {
      const actionsSrc = fs.readFileSync(require.resolve('../actions'), 'utf8');
      assert.ok(actionsSrc.includes('humanPause'), 'humanPause must be defined in actions.js');
      assert.ok(actionsSrc.includes('Math.random()'), 'humanPause must use random jitter');
      assert.ok(!actionsSrc.includes('page.waitForTimeout'), 'fixed waitForTimeout calls must be replaced');
    });

    test('backoff on 429 implemented', () => {
      const actionsSrc = fs.readFileSync(require.resolve('../actions'), 'utf8');
      assert.ok(actionsSrc.includes('maybeBackoff'), 'maybeBackoff helper missing');
      assert.ok(actionsSrc.includes("response.status() === 429"), '429 check missing');
    });
  });
});
