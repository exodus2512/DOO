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

describe('Challenge detection logic', () => {
  // These tests validate the isVisible guard and bot_block text pattern
  // by inspecting the source directly (no real browser required).
  const fs = require('fs');
  const source = fs.readFileSync(require.resolve('../perception'), 'utf8');

  test('isVisible requires minimum 100x40 bounding box', () => {
    // The guard must check width < 100 || height < 40
    assert.ok(source.includes('r.width < 100'), 'width guard missing');
    assert.ok(source.includes('r.height < 40'), 'height guard missing');
  });

  test('isVisible checks display, visibility, and opacity', () => {
    assert.ok(source.includes("s.display !== 'none'"), 'display guard missing');
    assert.ok(source.includes("s.visibility !== 'hidden'"), 'visibility guard missing');
    assert.ok(source.includes("s.opacity !== '0'"), 'opacity guard missing');
  });

  test('reCAPTCHA detection gates on visible iframe or .g-recaptcha, not bare data-sitekey', () => {
    // Must require isVisible on the iframe/widget, not just any [data-sitekey]
    assert.ok(source.includes('iframe[src*="recaptcha/api2"]'), 'must target api2 iframe');
    assert.ok(source.includes('.g-recaptcha'), 'must check .g-recaptcha widget');
    // Old pattern was [data-sitekey] without visibility check — must NOT exist alone
    assert.ok(!source.includes("'[data-sitekey]'"), 'bare data-sitekey selector must be gone');
  });

  test('hCaptcha detection gates on visible iframe or .h-captcha', () => {
    assert.ok(source.includes('iframe[src*="hcaptcha"]'), 'hcaptcha iframe missing');
    assert.ok(source.includes('.h-captcha'), '.h-captcha widget missing');
  });

  test('Turnstile detection gates on visible iframe or .cf-turnstile', () => {
    assert.ok(source.includes('iframe[src*="challenges.cloudflare"]'), 'turnstile iframe missing');
    assert.ok(source.includes('.cf-turnstile'), '.cf-turnstile widget missing');
  });

  test('bot_block pattern covers standard block-page phrases', () => {
    const match = source.match(/BOT_BLOCK_RE\s*=\s*\/(.+?)\//);
    assert.ok(match, 'BOT_BLOCK_RE pattern not found');
    const re = new RegExp(match[1], 'i');
    const phrases = [
      'unusual traffic',
      'access denied',
      'verify you are human',
      'just a moment',
      'press & hold',
      'are you a robot',
    ];
    for (const phrase of phrases) {
      assert.ok(re.test(phrase), `BOT_BLOCK_RE should match: "${phrase}"`);
    }
  });

  test('bot_block result has type and reason fields', () => {
    assert.ok(source.includes("type: 'bot_block'"), "bot_block type missing");
    assert.ok(source.includes('reason:'), 'bot_block reason missing');
  });

  test('generic captcha fallback only triggers for visible elements', () => {
    // Must call isVisible() inside the for loop, not just querySelector
    const genericSection = source.slice(
      source.indexOf('Generic visible CAPTCHA'),
      source.indexOf('Bot-block detection')
    );
    assert.ok(genericSection.includes('isVisible(el)'), 'generic captcha must check isVisible');
  });
});
