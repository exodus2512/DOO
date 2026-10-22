/**
 * DOO Browser Worker — Perception Module
 * Builds structured, versioned observations from the DOM.
 * Contract: doo.perception.v1
 */

const crypto = require('crypto');

const SCHEMA_VERSION = 'doo.perception.v1';
const MAX_PAGE_TEXT_LENGTH = 8000;
const MAX_INTERACTIVE_ELEMENTS = 100;

function generateObservationId() {
  return 'obs_' + crypto.randomBytes(8).toString('hex');
}

/**
 * Build a full structured observation from the current page state.
 * @param {import('playwright').Page} page
 * @param {string} taskId
 * @param {object} options
 * @param {boolean} [options.includeScreenshot=false]
 * @param {boolean} [options.includeBoundingBoxes=true]
 * @returns {Promise<object>} doo.perception.v1 observation
 */
async function buildObservation(page, taskId, options = {}) {
  const includeScreenshot = options.includeScreenshot ?? false;
  const includeBoundingBoxes = options.includeBoundingBoxes ?? true;
  const observationId = generateObservationId();
  const observedAt = new Date().toISOString();

  const currentUrl = page.url();
  const pageTitle = await page.title().catch(() => '');

  // Extract page text (excluding scripts, styles, nav, footer)
  // Password field values are excluded by masking type=password inputs in the clone
  let pageTextResult;
  try {
    pageTextResult = await page.evaluate((maxLen) => {
      const clone = document.cloneNode(true);
      const toRemove = clone.querySelectorAll('script, style, noscript, nav, header, footer, svg, [role="navigation"], [role="banner"], [aria-hidden="true"]');
      toRemove.forEach(el => el.remove());
      // Mask password field values in text extraction
      clone.querySelectorAll('input[type="password"]').forEach(el => { el.value = ''; });
      const raw = (clone.body || clone).innerText || '';
      const text = raw.substring(0, maxLen);
      return { text, truncated: raw.length > maxLen };
    }, MAX_PAGE_TEXT_LENGTH);
  } catch {
    pageTextResult = { text: '', truncated: false };
  }

  // Extract interactive elements with accessibility info
  let interactiveElements = [];
  let elementsTruncated = false;
  try {
    const rawElements = await page.evaluate((maxElements) => {
      const selectors = [
        'a[href]',
        'button',
        'input:not([type="hidden"])',
        'select',
        'textarea',
        '[role="button"]',
        '[role="link"]',
        '[role="textbox"]',
        '[role="combobox"]',
        '[role="checkbox"]',
        '[role="radio"]',
        '[role="tab"]',
        '[role="menuitem"]',
        '[contenteditable="true"]',
      ];
      const seen = new Set();
      const results = [];

      for (const sel of selectors) {
        for (const el of document.querySelectorAll(sel)) {
          if (seen.has(el)) continue;
          seen.add(el);

          const rect = el.getBoundingClientRect();
          const isVisible = rect.width > 0 && rect.height > 0 &&
            window.getComputedStyle(el).visibility !== 'hidden' &&
            window.getComputedStyle(el).display !== 'none';

          if (!isVisible) continue;
          const isButtonInput = ['button', 'submit', 'reset'].includes(el.type);
          const role = el.getAttribute('role') ||
            (el.tagName === 'A' ? 'link' :
             el.tagName === 'BUTTON' ? 'button' :
             el.tagName === 'INPUT' ? (isButtonInput ? 'button' :
               el.type === 'checkbox' ? 'checkbox' :
               el.type === 'radio' ? 'radio' : 'textbox') :
             el.tagName === 'SELECT' ? 'combobox' :
             el.tagName === 'TEXTAREA' ? 'textbox' :
             'generic');

          const label = el.getAttribute('aria-label') ||
            el.getAttribute('aria-labelledby') && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent ||
            el.labels?.[0]?.textContent?.trim() ||
            el.getAttribute('title') ||
            el.getAttribute('alt') ||
            el.getAttribute('placeholder') ||
            (isButtonInput ? el.value : '') ||
            '';

          const isPassword = el.type === 'password';
          const text = el.innerText?.substring(0, 200)?.trim() || '';
          const value = isPassword ? '***' : (el.value?.substring(0, 200) || '');

          const required = el.required || el.getAttribute('aria-required') === 'true';
          const autocomplete = el.getAttribute('autocomplete') || null;

          results.push({
            role,
            label: label.substring(0, 200),
            text: text.substring(0, 200),
            value: isPassword ? '***' : value,
            placeholder: el.getAttribute('placeholder') || null,
            type: el.type || el.tagName.toLowerCase(),
            enabled: !el.disabled,
            visible: true,
            required,
            autocomplete,
            href: el.tagName === 'A' ? el.href : null,
            isPassword,
            boundingBox: {
              x: Math.round(rect.x),
              y: Math.round(rect.y),
              width: Math.round(rect.width),
              height: Math.round(rect.height),
            },
            // Data for building a stable locator
            tagName: el.tagName.toLowerCase(),
            id: el.id || null,
            name: el.name || null,
            className: el.className?.toString()?.substring(0, 200) || null,
            placeholder: el.getAttribute('placeholder') || null,
            ariaRequired: el.getAttribute('aria-required') || null,
          });

          if (results.length >= maxElements) break;
        }
        if (results.length >= maxElements) break;
      }

      return { elements: results, truncated: results.length >= maxElements };
    }, MAX_INTERACTIVE_ELEMENTS);

    elementsTruncated = rawElements.truncated;
    interactiveElements = rawElements.elements.map((el, idx) => {
      const mapped = {
        index: idx,
        tag: el.tagName,
        role: el.role,
        label: el.label,
        text: el.text,
        value: el.value || '',
        placeholder: el.placeholder,
        type: el.type,
        enabled: el.enabled,
        visible: el.visible,
        href: el.href,
        required: el.required || false,
        autocomplete: el.autocomplete || null,
        name: el.name || null,
        id: el.id || null,
        ariaRequired: el.ariaRequired || null,
      };
      if (includeBoundingBoxes) {
        mapped.bounding_box = el.boundingBox;
      }
      // Store locator hints internally for action resolution
      mapped._locator = {
        tagName: el.tagName,
        id: el.id,
        name: el.name,
        label: el.label,
        text: el.text,
        role: el.role,
        placeholder: el.placeholder,
      };
      return mapped;
    });
  } catch (e) {
    // If element extraction fails, continue with empty list
  }

  // Screenshot if requested — mask password fields before capture
  let screenshotData = null;
  if (includeScreenshot) {
    try {
      // Inject a temporary overlay to mask password field contents
      await page.evaluate(() => {
        const style = document.createElement('style');
        style.id = '__doo_pwd_mask';
        style.textContent = 'input[type="password"] { -webkit-text-security: disc !important; color: transparent !important; text-shadow: 0 0 0 #000 !important; }';
        document.head.appendChild(style);
      }).catch(() => {});

      const buffer = await page.screenshot({
        type: 'jpeg',
        quality: 60,
        fullPage: false,
      });
      screenshotData = {
        available: true,
        base64: buffer.toString('base64'),
        mime_type: 'image/jpeg',
      };

      // Remove the temporary mask style
      await page.evaluate(() => {
        const s = document.getElementById('__doo_pwd_mask');
        if (s) s.remove();
      }).catch(() => {});
    } catch {
      screenshotData = { available: false };
    }
  }

  // Detect CAPTCHA widgets and OTP/2FA patterns
  let challenges = [];
  try {
    challenges = await page.evaluate(() => {
      const found = [];
      const vw = window.innerWidth;
      const vh = window.innerHeight;

      /**
       * Compute what fraction (0–1) of the element's bounding box is inside
       * the current viewport. Requires at least 70% overlap to count.
       */
      function viewportIntersectionRatio(r) {
        const ix = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
        const iy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
        const area = r.width * r.height;
        return area > 0 ? (ix * iy) / area : 0;
      }

      /**
       * Check whether an element is user-visible:
       *   - bounding box ≥ 100×40 px
       *   - not hidden by CSS (display/visibility/opacity)
       *   - ≥ 70 % of its area is inside the viewport
       */
      function isVisible(el) {
        const r = el.getBoundingClientRect();
        if (r.width < 100 || r.height < 40) return false;
        const s = window.getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
        return viewportIntersectionRatio(r) >= 0.7;
      }

      /**
       * Return true if a recaptcha iframe should be skipped:
       *   - src contains "size=invisible" (v3 / invisible badge)
       *   - any ancestor has class "grecaptcha-badge" (the floating badge)
       */
      function isInvisibleRecaptcha(iframe) {
        const src = iframe.src || '';
        if (src.includes('size=invisible')) return true;
        let el = iframe.parentElement;
        while (el) {
          if (el.classList && el.classList.contains('grecaptcha-badge')) return true;
          el = el.parentElement;
        }
        return false;
      }

      /** Strip query string from a URL for logging. */
      function stripQuery(url) {
        try { return url.split('?')[0]; } catch { return url; }
      }

      /** Push a captcha entry and build a compact log record. */
      function reportCaptcha(provider, matchedSelector, el) {
        const r = el.getBoundingClientRect();
        const ratio = Math.round(viewportIntersectionRatio(r) * 100) / 100;
        const src = stripQuery(el.src || el.getAttribute('src') || '');
        found.push({
          type: 'captcha',
          provider,
          _log: {
            matchedSelector,
            iframeSrc: src,
            boundingRect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
            viewportIntersectionRatio: ratio,
          },
        });
      }

      // ── reCAPTCHA ────────────────────────────────────────────────────────────
      // Count only:
      //   (a) api2/bframe  — the image-grid challenge popup
      //   (b) api2/anchor  — the v2 checkbox, but NOT when size=invisible
      // Never count v3 invisible badges.
      let rcReported = false;
      for (const iframe of document.querySelectorAll('iframe[src*="recaptcha/api2"]')) {
        if (isInvisibleRecaptcha(iframe)) continue;
        const src = iframe.src || '';
        const isBframe = src.includes('bframe');
        const isAnchor = src.includes('anchor') && !src.includes('size=invisible');
        if ((isBframe || isAnchor) && isVisible(iframe)) {
          reportCaptcha('recaptcha', isBframe ? 'iframe[src*="bframe"]' : 'iframe[src*="anchor"]', iframe);
          rcReported = true;
          break;
        }
      }
      // Fallback: visible .g-recaptcha container (no invisible ancestor)
      if (!rcReported) {
        const rcWidget = document.querySelector('.g-recaptcha');
        if (rcWidget && isVisible(rcWidget) && !rcWidget.closest('.grecaptcha-badge')) {
          reportCaptcha('recaptcha', '.g-recaptcha', rcWidget);
        }
      }

      // ── hCaptcha ─────────────────────────────────────────────────────────────
      for (const iframe of document.querySelectorAll('iframe[src*="hcaptcha"]')) {
        if (isVisible(iframe)) {
          reportCaptcha('hcaptcha', 'iframe[src*="hcaptcha"]', iframe);
          break;
        }
      }
      if (!found.some(f => f.provider === 'hcaptcha')) {
        const hcWidget = document.querySelector('.h-captcha');
        if (hcWidget && isVisible(hcWidget)) reportCaptcha('hcaptcha', '.h-captcha', hcWidget);
      }

      // ── Cloudflare Turnstile ──────────────────────────────────────────────────
      for (const iframe of document.querySelectorAll('iframe[src*="challenges.cloudflare"]')) {
        if (isVisible(iframe)) {
          reportCaptcha('turnstile', 'iframe[src*="challenges.cloudflare"]', iframe);
          break;
        }
      }
      if (!found.some(f => f.provider === 'turnstile')) {
        const cfWidget = document.querySelector('.cf-turnstile');
        if (cfWidget && isVisible(cfWidget)) reportCaptcha('turnstile', '.cf-turnstile', cfWidget);
      }

      // ── Generic fallback (visible, in-viewport) ───────────────────────────────
      if (!found.some(f => f.type === 'captcha')) {
        const captchaEls = document.querySelectorAll('[class*="captcha" i], [id*="captcha" i], [data-captcha]');
        for (const el of captchaEls) {
          // Skip elements that are part of the invisible reCAPTCHA badge
          if (el.closest('.grecaptcha-badge')) continue;
          if (isVisible(el)) {
            reportCaptcha('unknown', '[captcha]', el);
            break;
          }
        }
      }

      // ── Bot-block detection ───────────────────────────────────────────────────
      const bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
      const docTitle = document.title || '';
      const BOT_BLOCK_RE = /unusual traffic|access denied|verify you are human|just a moment|press & hold|are you a robot|sorry,? you have been blocked|attention required|cloudflare ray id|you are unable to access|enable cookies and reload|checking your browser|why have i been blocked|request blocked/i;
      if (BOT_BLOCK_RE.test(bodyText.substring(0, 3000))) {
        found.push({ type: 'bot_block', reason: 'visible block text detected' });
      }
      if (/attention required|just a moment/i.test(docTitle)) {
        if (!found.some(f => f.type === 'bot_block')) {
          found.push({ type: 'bot_block', reason: 'document title contains bot block indicator' });
        }
      }

      // ── OTP / 2FA inputs ──────────────────────────────────────────────────────
      const inputs = document.querySelectorAll('input');
      for (const inp of inputs) {
        const attrs = (inp.name + ' ' + inp.id + ' ' + inp.placeholder + ' ' + (inp.getAttribute('aria-label') || '')).toLowerCase();
        const isOtp = /\b(otp|2fa|mfa|totp|verification.?code|one.?time|auth.?code|6.?digit)\b/.test(attrs);
        const isShortNumeric = inp.maxLength > 0 && inp.maxLength <= 8 && (inp.inputMode === 'numeric' || inp.type === 'tel' || inp.pattern === '[0-9]*');
        if (isOtp || (isShortNumeric && /\b(code|verify|confirm)\b/.test(attrs))) {
          found.push({ type: 'otp', inputName: inp.name || inp.id || null });
          break;
        }
      }

      return found;
    });
  } catch { /* continue without challenge detection */ }

  // Push a bot_block challenge when document.title contains "Attention Required" or "Just a moment", or when main response status is 403 or 429
  const mainStatus = Number(options?.responseStatus) || 200;
  if (mainStatus === 403 || mainStatus === 429) {
    if (!challenges.some(c => c.type === 'bot_block')) {
      challenges.push({ type: 'bot_block', reason: `HTTP status ${mainStatus}` });
    }
  }
  if (/attention required|just a moment/i.test(pageTitle)) {
    if (!challenges.some(c => c.type === 'bot_block')) {
      challenges.push({ type: 'bot_block', reason: 'document title contains bot block indicator' });
    }
  }

  // Log each reported captcha challenge (one line per entry)
  for (const c of challenges) {
    if (c.type === 'captcha' && c._log) {
      const l = c._log;
      console.log(
        `[CAPTCHA] provider=${c.provider} selector="${l.matchedSelector}"` +
        ` src="${l.iframeSrc}"` +
        ` rect=${JSON.stringify(l.boundingRect)}` +
        ` intersection=${l.viewportIntersectionRatio}`
      );
    }
  }

  // Scroll position and page dimensions — lets the AI decide whether to scroll for more content
  let scrollInfo = { scrollY: 0, pageHeight: 0, viewportHeight: 0 };
  try {
    scrollInfo = await page.evaluate(() => ({
      scrollY: Math.round(window.scrollY),
      pageHeight: Math.round(document.documentElement.scrollHeight),
      viewportHeight: Math.round(window.innerHeight),
    }));
  } catch { /* continue with defaults */ }

  const pageState = classifyPage({
    url: currentUrl,
    title: pageTitle,
    page_text: pageTextResult,
    interactive_elements: interactiveElements,
    challenges: challenges.length > 0 ? challenges : null,
  }, options.responseStatus || 200);

  return {
    schema_version: SCHEMA_VERSION,
    observation_id: observationId,
    task_id: taskId,
    url: currentUrl,
    title: pageTitle,
    page_state: pageState,
    observed_at: observedAt,
    page_text: {
      text: pageTextResult.text,
      truncated: pageTextResult.truncated,
    },
    interactive_elements: interactiveElements,
    screenshot: screenshotData,
    challenges: challenges.length > 0 ? challenges : null,
    scroll: scrollInfo,
    metadata: {
      interactive_elements_count: interactiveElements.length,
      interactive_elements_truncated: elementsTruncated,
      page_text_truncated: pageTextResult.truncated,
      page_text_length: pageTextResult.text.length,
      page_state: pageState,
    },
  };
}

/**
 * Classify page state: NORMAL / LOGIN / CAPTCHA_VISIBLE / BOT_BLOCK
 * @param {object} observation
 * @param {number} [responseStatus=200]
 * @param {object} [responseHeaders={}]
 * @returns {'NORMAL' | 'LOGIN' | 'CAPTCHA_VISIBLE' | 'BOT_BLOCK'}
 */
function classifyPage(observation, responseStatus = 200, responseHeaders = {}) {
  const status = Number(responseStatus) || 200;
  const title = (observation?.title || '').toLowerCase();
  const text = (observation?.page_text?.text || '').toLowerCase();
  const challenges = observation?.challenges || [];

  // 1. Check BOT_BLOCK first (HTTP 403/429 or explicit bot-block markers)
  if (status === 403 || status === 429) {
    return 'BOT_BLOCK';
  }

  const BOT_BLOCK_TITLE_PATTERNS = [
    'attention required! | cloudflare',
    'attention required',
    'just a moment...',
    'just a moment',
    'access denied',
    '403 forbidden',
    'pardon our interruption',
    'security check',
  ];

  const BOT_BLOCK_TEXT_PATTERNS = [
    'attention required! | cloudflare',
    'just a moment...',
    'cf-browser-verification',
    'cloudflare ray id',
    'unusual traffic from your computer network',
    'unusual traffic',
    'access denied',
    'pardon our interruption',
    'why have i been blocked',
    'this website is using a security service to protect itself',
    'sorry, you have been blocked',
    'sorry you have been blocked',
    'you are unable to access',
    'enable cookies and reload',
    'checking your browser',
    'request blocked',
  ];

  for (const pat of BOT_BLOCK_TITLE_PATTERNS) {
    if (title.includes(pat)) return 'BOT_BLOCK';
  }

  for (const pat of BOT_BLOCK_TEXT_PATTERNS) {
    if (text.includes(pat)) return 'BOT_BLOCK';
  }

  if (challenges.some(c => c.type === 'bot_block')) {
    return 'BOT_BLOCK';
  }

  // 2. Check CAPTCHA_VISIBLE: active visible challenge requiring human
  const hasVisibleCaptcha = challenges.some(c => c.type === 'captcha');
  if (hasVisibleCaptcha) {
    return 'CAPTCHA_VISIBLE';
  }

  // 3. Check LOGIN: password field or authentication surface
  const elements = observation?.interactive_elements || [];
  const hasPasswordField = elements.some(el =>
    el.type === 'password' ||
    (el.tag === 'INPUT' && (el.name === 'password' || el.id === 'password'))
  );
  if (hasPasswordField) {
    return 'LOGIN';
  }

  const url = (observation?.url || '').toLowerCase();
  if (url.includes('/login') || url.includes('/signin') || url.includes('/auth')) {
    const hasIdentifierField = elements.some(el =>
      el.role === 'textbox' || el.tag === 'INPUT'
    );
    if (hasIdentifierField && elements.some(el => {
      const lbl = (el.text || el.label || '').toLowerCase();
      return lbl.includes('log in') || lbl.includes('sign in');
    })) {
      return 'LOGIN';
    }
  }

  // 4. Default: NORMAL
  return 'NORMAL';
}

/**
 * Resolve an element_index from an observation to a Playwright locator.
 * Uses multiple strategies for resilience.
 * @param {import('playwright').Page} page
 * @param {object} observation - The observation that contains the element
 * @param {number} elementIndex - The index into interactive_elements
 * @returns {Promise<import('playwright').Locator>}
 */
async function resolveElement(page, observation, elementIndex) {
  const elements = observation.interactive_elements;
  if (!elements || elementIndex < 0 || elementIndex >= elements.length) {
    throw new Error(`Element index ${elementIndex} out of range (0-${(elements?.length || 0) - 1})`);
  }

  const el = elements[elementIndex];
  const locator = el._locator || {};

  // Strategy 1: By ID (most stable)
  if (locator.id) {
    const byId = page.locator(`#${CSS.escape(locator.id)}`);
    if (await byId.count() > 0 && await byId.first().isVisible()) {
      return byId.first();
    }
  }

  // Strategy 2: By role + name (accessible)
  if (locator.role && locator.role !== 'generic') {
    const roleName = locator.label || locator.text;
    if (roleName) {
      try {
        const byRole = page.getByRole(locator.role, { name: roleName, exact: false });
        if (await byRole.count() > 0) {
          return byRole.first();
        }
      } catch { /* role may not be recognized */ }
    }
  }

  // Strategy 3: By label text (for form fields)
  if (locator.label && ['textbox', 'combobox'].includes(locator.role)) {
    try {
      const byLabel = page.getByLabel(locator.label, { exact: false });
      if (await byLabel.count() > 0) {
        return byLabel.first();
      }
    } catch { /* continue */ }
  }

  // Strategy 4: By name attribute
  if (locator.name) {
    const byName = page.locator(`[name="${locator.name}"]`);
    if (await byName.count() > 0 && await byName.first().isVisible()) {
      return byName.first();
    }
  }

  // Strategy: By placeholder
  if (locator.placeholder || el.placeholder) {
    try {
      const byPlaceholder = page.getByPlaceholder(locator.placeholder || el.placeholder, { exact: false });
      if (await byPlaceholder.count() > 0 && await byPlaceholder.first().isVisible()) {
        return byPlaceholder.first();
      }
    } catch { /* continue */ }
  }

  // Strategy 5: By bounding box click (fallback)
  if (el.bounding_box) {
    const bb = el.bounding_box;
    const x = bb.x + bb.width / 2;
    const y = bb.y + bb.height / 2;
    // Return a special locator-like object for coordinate-based actions
    return { _coordinate: { x, y }, _element: el };
  }

  throw new Error(`Could not resolve element at index ${elementIndex} (role=${el.role}, label="${el.label}")`);
}

/**
 * CSS.escape polyfill for Node.js (not available in JSDOM/Playwright evaluate context from Node)
 */
const CSS = {
  escape(str) {
    if (!str) return '';
    return str.replace(/([^\w-])/g, '\\$1');
  },
};

module.exports = {
  buildObservation,
  resolveElement,
  generateObservationId,
  classifyPage,
  SCHEMA_VERSION,
  MAX_PAGE_TEXT_LENGTH,
  MAX_INTERACTIVE_ELEMENTS,
};
