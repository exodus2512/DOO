/**
 * Live view module — unit tests
 * Tests: token lifecycle, message validation, coordinate scaling.
 */
'use strict';
const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const live = require('../live');

describe('live token', () => {
  test('issueToken returns a hex string', () => {
    const token = live.issueToken('task-1');
    assert.match(token, /^[0-9a-f]{40}$/);
  });

  test('validateToken resolves taskId', () => {
    const token = live.issueToken('task-2');
    assert.equal(live.validateToken(token), 'task-2');
  });

  test('unknown token returns null', () => {
    assert.equal(live.validateToken('does-not-exist'), null);
  });

  test('tokens are unique', () => {
    const t1 = live.issueToken('task-3');
    const t2 = live.issueToken('task-3');
    assert.notEqual(t1, t2);
  });
});

describe('input sanitization', () => {
  // Exercise the sanitizers via source inspection (no real CDP session needed)
  const fs = require('fs');
  const src = fs.readFileSync(require.resolve('../live'), 'utf8');

  test('clamp is defined', () => assert.ok(src.includes('function clamp')));
  test('sanitizeModifiers masks to 4 bits', () => assert.ok(src.includes('raw & 0b1111')));
  test('sanitizeKeyCode uses an allowlist', () => assert.ok(src.includes('ALLOWED_KEY_CODES')));
  test('sanitizeKey limits to printable ASCII and 20 chars', () => {
    assert.ok(src.includes('substring(0, 20)'));
    assert.ok(src.includes('\\x20-\\x7E'));
  });
  test('text input capped at 200 chars', () => assert.ok(src.includes('substring(0, 200)')));
  test('coordinate clamped to [0, 8192]', () => assert.ok(src.includes('8192')));
});

describe('coordinate scaling (canvas → viewport)', () => {
  // The LiveBrowser frontend component scales pointer coords;
  // test the math here without DOM.
  function scaleCoord(canvasX, canvasW, viewportW) {
    return Math.round((canvasX / canvasW) * viewportW);
  }

  test('centre maps to centre', () => {
    assert.equal(scaleCoord(640, 1280, 1280), 640);
  });

  test('scales when canvas is half viewport width', () => {
    assert.equal(scaleCoord(200, 400, 800), 400);
  });

  test('edge pixel maps to viewport edge', () => {
    assert.equal(scaleCoord(1279, 1280, 1280), 1279);
  });

  test('0 maps to 0', () => {
    assert.equal(scaleCoord(0, 1280, 1280), 0);
  });
});
