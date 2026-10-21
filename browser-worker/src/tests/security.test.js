/**
 * Browser Worker — Security module unit tests (Node built-in test runner)
 */
const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { validateUrl, validateActionType } = require('../security');

describe('validateUrl', () => {
  // ── Allowed URLs ────────────────────────────────────────────────────────
  test('allows https public URL', () => {
    assert.doesNotThrow(() => validateUrl('https://www.google.com'));
  });
  test('allows http public URL', () => {
    assert.doesNotThrow(() => validateUrl('http://example.com'));
  });
  test('allows Wikipedia URL with path', () => {
    assert.doesNotThrow(() => validateUrl('https://en.wikipedia.org/wiki/Test'));
  });

  // ── file:// blocked ──────────────────────────────────────────────────────
  test('blocks file:// URL', () => {
    assert.throws(() => validateUrl('file:///etc/passwd'), /not allowed/i);
  });
  test('blocks data: URL', () => {
    assert.throws(() => validateUrl('data:text/html,<h1>hi</h1>'), /not allowed|not permitted/i);
  });
  test('blocks javascript: URL', () => {
    assert.throws(() => validateUrl('javascript:alert(1)'), /not allowed|not permitted/i);
  });

  // ── localhost blocked ────────────────────────────────────────────────────
  test('blocks localhost', () => {
    assert.throws(() => validateUrl('http://localhost'), /not permitted/i);
  });
  test('blocks localhost with port', () => {
    assert.throws(() => validateUrl('http://localhost:8080/api'), /not permitted/i);
  });
  test('blocks 127.0.0.1', () => {
    assert.throws(() => validateUrl('http://127.0.0.1'), /not permitted/i);
  });

  // ── Private IP ranges ────────────────────────────────────────────────────
  test('blocks 192.168.x.x', () => {
    assert.throws(() => validateUrl('http://192.168.1.1'), /not permitted/i);
  });
  test('blocks 10.x.x.x', () => {
    assert.throws(() => validateUrl('http://10.0.0.1'), /not permitted/i);
  });
  test('blocks 172.16.x.x', () => {
    assert.throws(() => validateUrl('http://172.16.0.1'), /not permitted/i);
  });

  // ── Cloud metadata ───────────────────────────────────────────────────────
  test('blocks AWS metadata endpoint', () => {
    assert.throws(() => validateUrl('http://169.254.169.254/latest/meta-data'), /not permitted/i);
  });

  // ── Edge cases ──────────────────────────────────────────────────────────
  test('blocks null', () => {
    assert.throws(() => validateUrl(null), /must not be blank/i);
  });
  test('blocks empty string', () => {
    assert.throws(() => validateUrl(''), /must not be blank/i);
  });
  test('blocks invalid URL', () => {
    assert.throws(() => validateUrl('not a url at all!!'), /Invalid URL/i);
  });

  // ── Test fixture exception ──────────────────────────────────────────────
  test('allows localhost:8081 for test fixture', () => {
    assert.doesNotThrow(() => validateUrl('http://localhost:8081/index.html'));
  });
  test('allows 127.0.0.1:8081 for test fixture', () => {
    assert.doesNotThrow(() => validateUrl('http://127.0.0.1:8081/index.html'));
  });
  test('blocks localhost on other ports', () => {
    assert.throws(() => validateUrl('http://localhost:3000'), /not permitted/i);
  });

  // ── Additional scheme tests ─────────────────────────────────────────────
  test('blocks ftp: URL', () => {
    assert.throws(() => validateUrl('ftp://ftp.example.com'), /not allowed|not permitted/i);
  });
  test('blocks blob: URL', () => {
    assert.throws(() => validateUrl('blob:http://example.com/abc'), /not allowed|not permitted/i);
  });

  // ── URL length limit ────────────────────────────────────────────────────
  test('blocks excessively long URL', () => {
    const longUrl = 'https://example.com/' + 'a'.repeat(2100);
    assert.throws(() => validateUrl(longUrl), /maximum length/i);
  });

  // ── GCP metadata ────────────────────────────────────────────────────────
  test('blocks GCP metadata.google.internal', () => {
    assert.throws(() => validateUrl('http://metadata.google.internal/computeMetadata'), /not permitted/i);
  });

  // ── Link-local addresses ────────────────────────────────────────────────
  test('blocks link-local 169.254.x.x', () => {
    assert.throws(() => validateUrl('http://169.254.1.1'), /not permitted/i);
  });
});

describe('validateActionType', () => {
  test('allows NAVIGATE', () => {
    assert.strictEqual(validateActionType('NAVIGATE'), 'NAVIGATE');
  });
  test('allows lowercase navigate', () => {
    assert.strictEqual(validateActionType('navigate'), 'NAVIGATE');
  });
  test('allows CLICK', () => {
    assert.doesNotThrow(() => validateActionType('CLICK'));
  });
  test('allows SCREENSHOT', () => {
    assert.doesNotThrow(() => validateActionType('SCREENSHOT'));
  });
  test('blocks EXECUTE_SCRIPT', () => {
    assert.throws(() => validateActionType('EXECUTE_SCRIPT'), /not permitted/i);
  });
  test('blocks SHELL_COMMAND', () => {
    assert.throws(() => validateActionType('SHELL_COMMAND'), /not permitted/i);
  });
  test('blocks PURCHASE', () => {
    assert.throws(() => validateActionType('PURCHASE'), /not permitted/i);
  });
  test('blocks unknown action', () => {
    assert.throws(() => validateActionType('MYSTERY_ACTION'), /Unknown action/i);
  });
  test('blocks null', () => {
    assert.throws(() => validateActionType(null), /must not be blank/i);
  });

  // ── Additional blocked actions ──────────────────────────────────────────
  test('blocks CHECKOUT', () => {
    assert.throws(() => validateActionType('CHECKOUT'), /not permitted/i);
  });
  test('blocks LOGIN', () => {
    assert.throws(() => validateActionType('LOGIN'), /not permitted/i);
  });
  test('blocks DELETE', () => {
    assert.throws(() => validateActionType('DELETE'), /not permitted/i);
  });
  test('blocks FILE_WRITE', () => {
    assert.throws(() => validateActionType('FILE_WRITE'), /not permitted/i);
  });
  test('blocks HTTP_REQUEST', () => {
    assert.throws(() => validateActionType('HTTP_REQUEST'), /not permitted/i);
  });
  test('blocks SUBSCRIBE', () => {
    assert.throws(() => validateActionType('SUBSCRIBE'), /not permitted/i);
  });

  // ── All allowed actions pass ────────────────────────────────────────────
  test('allows all permitted action types', () => {
    const allowed = ['NAVIGATE', 'CLICK', 'FILL', 'EXTRACT_TEXT', 'SCREENSHOT', 'SCROLL', 'WAIT', 'DONE', 'GO_BACK', 'GET_CONTENT'];
    for (const action of allowed) {
      assert.doesNotThrow(() => validateActionType(action), `${action} should be allowed`);
    }
  });
});
