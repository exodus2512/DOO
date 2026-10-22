/**
 * DOO Browser Worker - Page Classification & Fixture Tests
 * Tests classification into: NORMAL / LOGIN / CAPTCHA_VISIBLE / BOT_BLOCK
 */
const { test, describe, before, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const http = require('http');
const { chromium } = require('playwright');

const { classifyPage, buildObservation } = require('../perception');
const fixtureServer = require('../../../test-fixture/server');

const TEST_PORT = 8089;
const BASE_URL = `http://localhost:${TEST_PORT}`;

describe('Page Classification System', () => {
  let server;
  let browser;
  let page;

  before(async () => {
    // Start test fixture server on port 8089
    await new Promise((resolve) => {
      server = fixtureServer.listen(TEST_PORT, resolve);
    });

    // Launch headless chromium for fixture tests
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      locale: 'en-IN',
      timezoneId: 'Asia/Kolkata',
    });
    page = await context.newPage();
  });

  after(async () => {
    if (browser) await browser.close();
    if (server) await new Promise((resolve) => server.close(resolve));
  });

  describe('Pure Classification Logic (Unit Tests)', () => {
    test('classifies HTTP 403 as BOT_BLOCK', () => {
      const state = classifyPage({ title: 'Forbidden', page_text: { text: 'Forbidden' } }, 403);
      assert.equal(state, 'BOT_BLOCK');
    });

    test('classifies HTTP 429 as BOT_BLOCK', () => {
      const state = classifyPage({ title: 'Too Many Requests', page_text: { text: 'Too Many Requests' } }, 429);
      assert.equal(state, 'BOT_BLOCK');
    });

    test('classifies Cloudflare "Just a moment..." as BOT_BLOCK even if 200', () => {
      const state = classifyPage({
        title: 'Just a moment...',
        page_text: { text: 'Checking your browser before accessing website' }
      }, 200);
      assert.equal(state, 'BOT_BLOCK');
    });

    test('classifies "Attention Required! | Cloudflare" as BOT_BLOCK', () => {
      const state = classifyPage({
        title: 'Attention Required! | Cloudflare',
        page_text: { text: 'Cloudflare Ray ID: 12345' }
      }, 200);
      assert.equal(state, 'BOT_BLOCK');
    });

    test('classifies "unusual traffic from your computer network" as BOT_BLOCK', () => {
      const state = classifyPage({
        title: 'Network Check',
        page_text: { text: 'Our systems have detected unusual traffic from your computer network.' }
      }, 200);
      assert.equal(state, 'BOT_BLOCK');
    });

    test('classifies visible captcha challenge as CAPTCHA_VISIBLE', () => {
      const state = classifyPage({
        title: 'Human Verification',
        page_text: { text: 'Please check the box below' },
        challenges: [{ type: 'captcha', provider: 'recaptcha' }],
      }, 200);
      assert.equal(state, 'CAPTCHA_VISIBLE');
    });

    test('classifies password field as LOGIN', () => {
      const state = classifyPage({
        title: 'Sign In',
        page_text: { text: 'Enter your credentials' },
        interactive_elements: [
          { role: 'textbox', type: 'text', name: 'username' },
          { role: 'textbox', type: 'password', name: 'password' },
        ],
      }, 200);
      assert.equal(state, 'LOGIN');
    });

    test('classifies clean standard page as NORMAL', () => {
      const state = classifyPage({
        title: 'Official Portal Documentation',
        page_text: { text: 'Welcome to the public API and knowledge repository.' },
        interactive_elements: [
          { role: 'textbox', type: 'text', name: 'search' },
          { role: 'button', text: 'Search' },
        ],
      }, 200);
      assert.equal(state, 'NORMAL');
    });
  });

  describe('Live Fixture Observations (Integration Tests)', () => {
    test('1. Normal Page Fixture: classified as NORMAL', async () => {
      await page.goto(`${BASE_URL}/normal.html`);
      const obs = await buildObservation(page, 'test-normal', { includeScreenshot: false });
      assert.equal(obs.page_state, 'NORMAL', 'Normal page fixture should classify as NORMAL');
      assert.equal(obs.challenges, null, 'Should have no challenges detected');
    });

    test('2. Visible CAPTCHA Fixture: classified as CAPTCHA_VISIBLE', async () => {
      await page.goto(`${BASE_URL}/captcha-visible.html`);
      const obs = await buildObservation(page, 'test-captcha', { includeScreenshot: false });
      assert.equal(obs.page_state, 'CAPTCHA_VISIBLE', 'Visible CAPTCHA fixture must classify as CAPTCHA_VISIBLE');
      assert.ok(obs.challenges && obs.challenges.length > 0, 'Challenges array must not be empty');
      assert.equal(obs.challenges[0].type, 'captcha');
    });

    test('3. Invisible Badge Fixture: NOT classified as CAPTCHA_VISIBLE', async () => {
      await page.goto(`${BASE_URL}/invisible-badge.html`);
      const obs = await buildObservation(page, 'test-invisible', { includeScreenshot: false });
      assert.notEqual(obs.page_state, 'CAPTCHA_VISIBLE', 'Invisible v3 badge must NOT classify as CAPTCHA_VISIBLE');
      assert.equal(obs.page_state, 'NORMAL', 'Should classify as NORMAL form');
    });

    test('4. Cloudflare Block Page Fixture: classified as BOT_BLOCK', async () => {
      const response = await page.goto(`${BASE_URL}/cloudflare-block.html`);
      const obs = await buildObservation(page, 'test-cloudflare', {
        includeScreenshot: false,
        responseStatus: response.status()
      });
      assert.equal(obs.page_state, 'BOT_BLOCK', 'Cloudflare block page fixture must classify as BOT_BLOCK');
    });

    test('5. 429 Too Many Requests Fixture: classified as BOT_BLOCK with Retry-After', async () => {
      const response = await page.goto(`${BASE_URL}/rate-limit-429`);
      assert.equal(response.status(), 429, 'Server should return 429 status code');
      assert.equal(response.headers()['retry-after'], '5', 'Server should return Retry-After header');

      const obs = await buildObservation(page, 'test-429', {
        includeScreenshot: false,
        responseStatus: response.status()
      });
      assert.equal(obs.page_state, 'BOT_BLOCK', '429 Rate limit fixture must classify as BOT_BLOCK');
    });
  });

});
