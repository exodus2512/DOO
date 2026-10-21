/**
 * Browser Worker — Perception Module unit tests
 */
const { test, describe } = require('node:test');
const assert = require('node:assert/strict');

describe('Perception Module', () => {
  const fs = require('fs');
  const source = fs.readFileSync(require.resolve('../perception'), 'utf8');

  test('exports buildObservation function', () => {
    const mod = require('../perception');
    assert.ok(typeof mod.buildObservation === 'function');
  });

  test('exports resolveElement function', () => {
    const mod = require('../perception');
    assert.ok(typeof mod.resolveElement === 'function');
  });

  test('uses schema version doo.perception.v1', () => {
    const mod = require('../perception');
    assert.equal(mod.SCHEMA_VERSION, 'doo.perception.v1');
  });

  test('MAX_PAGE_TEXT_LENGTH is 8000', () => {
    const mod = require('../perception');
    assert.equal(mod.MAX_PAGE_TEXT_LENGTH, 8000);
  });

  test('MAX_INTERACTIVE_ELEMENTS is 100', () => {
    const mod = require('../perception');
    assert.equal(mod.MAX_INTERACTIVE_ELEMENTS, 100);
  });

  test('generateObservationId produces unique IDs with obs_ prefix', () => {
    const mod = require('../perception');
    const id1 = mod.generateObservationId();
    const id2 = mod.generateObservationId();
    assert.ok(id1.startsWith('obs_'));
    assert.ok(id2.startsWith('obs_'));
    assert.notEqual(id1, id2);
  });

  test('observation structure includes required fields', () => {
    assert.ok(source.includes('schema_version'));
    assert.ok(source.includes('observation_id'));
    assert.ok(source.includes('task_id'));
    assert.ok(source.includes('page_text'));
    assert.ok(source.includes('interactive_elements'));
    assert.ok(source.includes('observed_at'));
  });

  test('interactive elements include role, label, type, enabled, visible', () => {
    assert.ok(source.includes("role:"));
    assert.ok(source.includes("label:"));
    assert.ok(source.includes("enabled:"));
    assert.ok(source.includes("visible:"));
  });

  test('password values are masked', () => {
    assert.ok(source.includes("isPassword"));
    assert.ok(source.includes("'***'"));
  });

  test('resolveElement validates element_index bounds', () => {
    assert.ok(source.includes('out of range'));
  });

  test('resolveElement uses multiple strategies (id, role, label, name)', () => {
    assert.ok(source.includes('Strategy 1: By ID'));
    assert.ok(source.includes('Strategy 2: By role'));
    assert.ok(source.includes('Strategy 3: By label'));
    assert.ok(source.includes('Strategy 4: By name'));
    assert.ok(source.includes('Strategy 5: By bounding box'));
  });
});

describe('Challenge detection logic — source invariants', () => {
  const fs = require('fs');
  const source = fs.readFileSync(require.resolve('../perception'), 'utf8');

  test('isVisible requires minimum 100x40 bounding box', () => {
    assert.ok(source.includes('r.width < 100'), 'width guard missing');
    assert.ok(source.includes('r.height < 40'), 'height guard missing');
  });

  test('isVisible checks display, visibility, and opacity', () => {
    assert.ok(source.includes("s.display === 'none'"), 'display guard missing');
    assert.ok(source.includes("s.visibility === 'hidden'"), 'visibility guard missing');
    assert.ok(source.includes("s.opacity === '0'"), 'opacity guard missing');
  });

  test('viewport intersection ratio requires >=70% overlap', () => {
    assert.ok(source.includes('viewportIntersectionRatio'), 'viewportIntersectionRatio function missing');
    assert.ok(source.includes('>= 0.7'), '70% threshold missing');
  });

  test('isInvisibleRecaptcha skips size=invisible src', () => {
    assert.ok(source.includes('size=invisible'), 'size=invisible check missing');
    assert.ok(source.includes('isInvisibleRecaptcha'), 'isInvisibleRecaptcha function missing');
  });

  test('isInvisibleRecaptcha skips .grecaptcha-badge ancestors', () => {
    assert.ok(source.includes('grecaptcha-badge'), 'grecaptcha-badge ancestor check missing');
  });

  test('reCAPTCHA only triggers on bframe or anchor (not any api2 iframe)', () => {
    assert.ok(source.includes('bframe'), 'bframe check missing');
    assert.ok(source.includes('anchor'), 'anchor check missing');
    assert.ok(source.includes('isInvisibleRecaptcha(iframe)'), 'invisible check in rc loop missing');
  });

  test('hCaptcha and Turnstile use visible + in-viewport rule', () => {
    assert.ok(source.includes('iframe[src*="hcaptcha"]'), 'hcaptcha iframe missing');
    assert.ok(source.includes('.h-captcha'), '.h-captcha widget missing');
    assert.ok(source.includes('iframe[src*="challenges.cloudflare"]'), 'turnstile iframe missing');
    assert.ok(source.includes('.cf-turnstile'), '.cf-turnstile widget missing');
  });

  test('reportCaptcha logs matchedSelector, iframeSrc, boundingRect, intersectionRatio', () => {
    assert.ok(source.includes('matchedSelector'), 'matchedSelector in log missing');
    assert.ok(source.includes('iframeSrc'), 'iframeSrc in log missing');
    assert.ok(source.includes('boundingRect'), 'boundingRect in log missing');
    assert.ok(source.includes('viewportIntersectionRatio:'), 'intersectionRatio in log missing');
  });

  test('[CAPTCHA] log line emitted for each captcha challenge', () => {
    assert.ok(source.includes('[CAPTCHA]'), '[CAPTCHA] log prefix missing');
    assert.ok(source.includes('provider='), 'provider field missing from log');
    assert.ok(source.includes('selector='), 'selector field missing from log');
    assert.ok(source.includes('intersection='), 'intersection field missing from log');
  });

  test('bot_block pattern covers standard block-page phrases', () => {
    const match = source.match(/BOT_BLOCK_RE\s*=\s*\/(.+?)\//);
    assert.ok(match, 'BOT_BLOCK_RE pattern not found');
    const re = new RegExp(match[1], 'i');
    for (const phrase of ['unusual traffic','access denied','verify you are human','just a moment','press & hold','are you a robot']) {
      assert.ok(re.test(phrase), `BOT_BLOCK_RE should match: "${phrase}"`);
    }
  });

  test('generic captcha fallback only triggers for visible elements', () => {
    const genericSection = source.slice(
      source.indexOf('Generic fallback'),
      source.indexOf('Bot-block detection')
    );
    assert.ok(genericSection.includes('isVisible(el)'), 'generic captcha must check isVisible');
  });
});

describe('Challenge detection — fixture page tests (using Playwright)', () => {
  // These tests use real Chromium to exercise the CAPTCHA detection against
  // hand-crafted fixture HTML pages. They require @playwright/test to be installed.
  // Run via: node --test (slow tests skipped in unit-test CI via SKIP_BROWSER_TESTS)
  const { chromium } = require('playwright');
  const { buildObservation } = require('../perception');
  const SKIP = process.env.SKIP_BROWSER_TESTS === '1';

  // Fixture HTML helpers
  function makeFixture(bodyHtml) {
    return `data:text/html,<html><body style="margin:0;width:1280px;height:800px">${encodeURIComponent(bodyHtml)}</body></html>`;
  }

  // Invisible badge: an iframe whose src contains size=invisible.
  // Must NOT trigger a captcha challenge.
  test('invisible reCAPTCHA badge does NOT trigger captcha', { skip: SKIP }, async () => {
    const browser = await chromium.launch({ headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
      // Simulate the invisible badge iframe: matches recaptcha/api2 but has size=invisible
      await page.setContent(`<html><body style="margin:0">
        <div class="grecaptcha-badge" style="width:256px;height:60px;position:fixed;bottom:14px;right:14px">
          <iframe src="https://www.google.com/recaptcha/api2/anchor?ar=1&k=site&size=invisible&co=aH&hl=en"
                  style="width:256px;height:60px"></iframe>
        </div>
      </body></html>`);
      const obs = await buildObservation(page, 'test-invisible', {});
      const captchas = (obs.challenges || []).filter(c => c.type === 'captcha');
      assert.equal(captchas.length, 0,
        `invisible badge must NOT trigger captcha, got: ${JSON.stringify(captchas)}`);
    } finally { await browser.close(); }
  });

  // v2 checkbox iframe: api2/anchor WITHOUT size=invisible, large, in-viewport.
  // Must trigger a captcha challenge.
  test('reCAPTCHA v2 checkbox anchor iframe triggers captcha', { skip: SKIP }, async () => {
    const browser = await chromium.launch({ headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
      // Anchor iframe without size=invisible — the v2 checkbox widget
      await page.setContent(`<html><body style="margin:0">
        <div style="width:304px;height:78px">
          <iframe src="https://www.google.com/recaptcha/api2/anchor?ar=1&k=site&co=aH&hl=en"
                  style="width:304px;height:78px;display:block"></iframe>
        </div>
      </body></html>`);
      const obs = await buildObservation(page, 'test-v2-anchor', {});
      const captchas = (obs.challenges || []).filter(c => c.type === 'captcha' && c.provider === 'recaptcha');
      assert.equal(captchas.length, 1,
        `v2 anchor iframe must trigger exactly 1 captcha, got: ${JSON.stringify(captchas)}`);
    } finally { await browser.close(); }
  });

  // bframe popup: api2/bframe, large, in-viewport.
  // Must trigger a captcha challenge.
  test('reCAPTCHA bframe image-grid popup triggers captcha', { skip: SKIP }, async () => {
    const browser = await chromium.launch({ headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
      await page.setContent(`<html><body style="margin:0">
        <iframe src="https://www.google.com/recaptcha/api2/bframe?hl=en&v=abc"
                style="width:300px;height:420px;display:block"></iframe>
      </body></html>`);
      const obs = await buildObservation(page, 'test-bframe', {});
      const captchas = (obs.challenges || []).filter(c => c.type === 'captcha' && c.provider === 'recaptcha');
      assert.equal(captchas.length, 1,
        `bframe iframe must trigger exactly 1 captcha, got: ${JSON.stringify(captchas)}`);
    } finally { await browser.close(); }
  });
});
