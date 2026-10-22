/**
 * DOO Browser Worker - Polite Client & Real Browser Tests
 */
const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');

const {
  humanPause,
  maybeBackoff,
  throttleDomainNavigation,
  withDomainLock,
  getDomainCooldown,
  isDomainInCooldown,
  getDomainFromUrl,
  domainLastNavTime,
  domainCooldowns,
} = require('../actions');

describe('Polite Client Behaviour', () => {

  describe('humanPause', () => {
    test('pauses within specified bounds', async () => {
      const start = Date.now();
      await humanPause(50, 100);
      const elapsed = Date.now() - start;
      assert.ok(elapsed >= 45, `Pause should be >= 50ms (got ${elapsed}ms)`);
      assert.ok(elapsed <= 250, `Pause should be reasonable (got ${elapsed}ms)`);
    });

    test('default range is 300ms to 1200ms', () => {
      const actionsSrc = fs.readFileSync(require.resolve('../actions'), 'utf8');
      assert.ok(actionsSrc.includes('humanPause(min = 300, max = 1200)'), 'Default bounds should be 300-1200ms');
    });
  });

  describe('URL & Domain Extraction', () => {
    test('extracts hostname from HTTP/HTTPS URLs', () => {
      assert.equal(getDomainFromUrl('https://en.wikipedia.org/wiki/Main_Page'), 'en.wikipedia.org');
      assert.equal(getDomainFromUrl('http://example.com:8080/path?q=1'), 'example.com');
      assert.equal(getDomainFromUrl('https://SUB.DOMAIN.ORG/'), 'sub.domain.org');
    });

    test('returns null for invalid or non-http URLs', () => {
      assert.equal(getDomainFromUrl(null), null);
      assert.equal(getDomainFromUrl(''), null);
      assert.equal(getDomainFromUrl('about:blank'), null);
      assert.equal(getDomainFromUrl('data:text/html,hello'), null);
    });
  });

  describe('Single Page Per Domain Lock', () => {
    test('serializes concurrent tasks on the same domain', async () => {
      const order = [];
      const domain = 'concurrent-test.org';

      const task1 = withDomainLock(domain, async () => {
        await new Promise(r => setTimeout(r, 60));
        order.push('task1');
      });

      const task2 = withDomainLock(domain, async () => {
        order.push('task2');
      });

      await Promise.all([task1, task2]);
      assert.deepEqual(order, ['task1', 'task2'], 'Tasks on the same domain must run sequentially');
    });

    test('allows concurrent tasks on different domains', async () => {
      let started1 = false;
      let started2 = false;

      const p1 = withDomainLock('domain-a.org', async () => {
        started1 = true;
        await new Promise(r => setTimeout(r, 50));
      });

      const p2 = withDomainLock('domain-b.org', async () => {
        started2 = true;
      });

      await p2;
      assert.equal(started2, true, 'Task on domain-b should start immediately');
      await p1;
    });
  });

  describe('Domain Navigation Rate Limit (min 2s)', () => {
    test('throttles rapid sequential navigations to the same domain', async () => {
      const testDomain = 'ratelimit-test.org';
      domainLastNavTime.set(testDomain, Date.now() - 500); // 500ms ago

      const start = Date.now();
      // Throttle for min 200ms in test to keep test fast
      await throttleDomainNavigation(testDomain, 200);
      const elapsed = Date.now() - start;
      assert.ok(elapsed <= 50, `Should not wait if already elapsed`);

      // Now simulate a navigation that just happened 10ms ago
      domainLastNavTime.set(testDomain, Date.now());
      const throttleStart = Date.now();
      await throttleDomainNavigation(testDomain, 100);
      const throttleElapsed = Date.now() - throttleStart;
      assert.ok(throttleElapsed >= 70, `Should wait remaining time to honor interval (got ${throttleElapsed}ms)`);
    });
  });

  describe('Exponential Backoff & Retry-After Handling', () => {
    test('parses integer seconds from Retry-After header', async () => {
      const testDomain = 'backoff-seconds.org';
      const mockResponse = {
        status: () => 429,
        headers: () => ({ 'retry-after': '1' }),
      };

      const start = Date.now();
      await maybeBackoff(mockResponse, testDomain);
      const elapsed = Date.now() - start;
      assert.ok(elapsed >= 900, `Should wait ~1s per Retry-After header (got ${elapsed}ms)`);
      assert.ok(isDomainInCooldown(testDomain), 'Domain should enter cooldown after 429');
    });

    test('parses HTTP date from Retry-After header', async () => {
      const testDomain = 'backoff-date.org';
      const futureDate = new Date(Date.now() + 1500).toUTCString();
      const mockResponse = {
        status: () => 429,
        headers: () => ({ 'retry-after': futureDate }),
      };

      const start = Date.now();
      await maybeBackoff(mockResponse, testDomain);
      const elapsed = Date.now() - start;
      assert.ok(elapsed >= 500, `Should wait until HTTP date in Retry-After (got ${elapsed}ms)`);
    });

    test('handles 503 service unavailable with exponential backoff and domain cooldown', async () => {
      const testDomain = 'service-503.org';
      const mockResponse = {
        status: () => 503,
        headers: () => ({}),
      };

      await maybeBackoff(mockResponse, testDomain);
      assert.ok(isDomainInCooldown(testDomain), 'Domain should enter cooldown after 503');
      assert.ok(getDomainCooldown(testDomain) > 0, 'Cooldown remaining should be positive');
    });
  });

  describe('Real Browser & Clean Fingerprint Configuration', () => {
    const sessionsSrc = fs.readFileSync(require.resolve('../sessions'), 'utf8');

    test('uses channel: "chrome" in headed mode', () => {
      assert.ok(sessionsSrc.includes("channel: 'chrome'"), 'Should use Google Chrome channel when headed');
      assert.ok(sessionsSrc.includes('_launchPersistent'), 'Should have helper for persistent context launch');
    });

    test('no stealth plugins or webdriver spoofing patches', () => {
      assert.ok(!sessionsSrc.includes('--disable-blink-features=AutomationControlled'), 'No AutomationControlled bypass');
      assert.ok(!sessionsSrc.includes('.addInitScript('), 'No addInitScript tampering');
      assert.ok(!sessionsSrc.includes('puppeteer-extra-plugin-stealth'), 'No stealth plugin');
    });

    test('configures default locale and timezone from env', () => {
      assert.ok(sessionsSrc.includes("process.env.BROWSER_LOCALE   || 'en-IN'"));
      assert.ok(sessionsSrc.includes("process.env.BROWSER_TIMEZONE || 'Asia/Kolkata'"));
    });

    test('logs launcher used and real userAgent, warns on HeadlessChrome', () => {
      assert.ok(sessionsSrc.includes('Launcher used:'), 'Should log which launcher was used');
      assert.ok(sessionsSrc.includes('User-Agent:'), 'Should log real userAgent');
      assert.ok(sessionsSrc.includes('HeadlessChrome'), 'Should check for HeadlessChrome');
      assert.ok(sessionsSrc.includes('Many sites with anti-bot protection will block this session'), 'Should warn on HeadlessChrome');
      assert.ok(!sessionsSrc.includes('userAgent: \'Mozilla'), 'Must not set a custom hardcoded User-Agent');
    });
  });

});
