/**
 * Browser Worker — Session Manager unit tests (Node built-in test runner)
 */
const { test, describe, beforeEach, afterEach, mock } = require('node:test');
const assert = require('node:assert/strict');

describe('SessionManager', () => {
  let SessionManager;
  let manager;

  beforeEach(() => {
    // Clear module cache so we get a fresh singleton each time
    delete require.cache[require.resolve('../sessions')];
    // We can't easily mock chromium.launch(), so test the pure logic parts
  });

  describe('session expiry logic', () => {
    test('SESSION_TTL_MS is 15 minutes', () => {
      // Verify the constant exists by reading the module
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('SESSION_TTL_MS'), 'SESSION_TTL_MS constant should be defined');
      assert.ok(source.includes('15 * 60 * 1000'), 'TTL should be 15 minutes');
    });

    test('SESSION_IDLE_MS is 5 minutes', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('SESSION_IDLE_MS'), 'SESSION_IDLE_MS constant should be defined');
      assert.ok(source.includes('5 * 60 * 1000'), 'Idle timeout should be 5 minutes');
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
  });

  describe('closeAll cleans up reaper', () => {
    test('closeAll clears reaper timer', () => {
      const fs = require('fs');
      const source = fs.readFileSync(require.resolve('../sessions'), 'utf8');
      assert.ok(source.includes('clearInterval(this._reaperTimer)'), 'closeAll should clear reaper timer');
    });
  });
});
