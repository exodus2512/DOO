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
  let pageTextResult;
  try {
    pageTextResult = await page.evaluate((maxLen) => {
      const clone = document.cloneNode(true);
      const toRemove = clone.querySelectorAll('script, style, noscript');
      toRemove.forEach(el => el.remove());
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

          const role = el.getAttribute('role') ||
            (el.tagName === 'A' ? 'link' :
             el.tagName === 'BUTTON' ? 'button' :
             el.tagName === 'INPUT' ? (el.type === 'checkbox' ? 'checkbox' :
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
            '';

          const isPassword = el.type === 'password';
          const text = el.innerText?.substring(0, 200)?.trim() || '';
          const value = isPassword ? '***' : (el.value?.substring(0, 200) || '');

          results.push({
            role,
            label: label.substring(0, 200),
            text: text.substring(0, 200),
            value: isPassword ? '***' : value,
            placeholder: el.getAttribute('placeholder') || null,
            type: el.type || el.tagName.toLowerCase(),
            enabled: !el.disabled,
            visible: true,
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
        role: el.role,
        label: el.label,
        text: el.text,
        placeholder: el.placeholder,
        type: el.type,
        enabled: el.enabled,
        visible: el.visible,
        href: el.href,
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
      };
      return mapped;
    });
  } catch (e) {
    // If element extraction fails, continue with empty list
  }

  // Screenshot if requested
  let screenshotData = null;
  if (includeScreenshot) {
    try {
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
    } catch {
      screenshotData = { available: false };
    }
  }

  return {
    schema_version: SCHEMA_VERSION,
    observation_id: observationId,
    task_id: taskId,
    url: currentUrl,
    title: pageTitle,
    observed_at: observedAt,
    page_text: {
      text: pageTextResult.text,
      truncated: pageTextResult.truncated,
    },
    interactive_elements: interactiveElements,
    screenshot: screenshotData,
    metadata: {
      interactive_elements_count: interactiveElements.length,
      interactive_elements_truncated: elementsTruncated,
      page_text_truncated: pageTextResult.truncated,
      page_text_length: pageTextResult.text.length,
    },
  };
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
  SCHEMA_VERSION,
  MAX_PAGE_TEXT_LENGTH,
  MAX_INTERACTIVE_ELEMENTS,
};
