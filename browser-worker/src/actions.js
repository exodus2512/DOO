/**
 * DOO Browser Worker - Playwright Action Executor
 * Executes individual browser actions within a session.
 * Actions reference elements by observation_id + element_index.
 */

const { validateUrl, validateActionType } = require('./security');
const { buildObservation, resolveElement } = require('./perception');

/**
 * Execute a single action in the given Playwright page.
 * Returns { success, result, error, observation, screenshot }
 */
async function executeAction(page, actionType, args = {}, taskId = null, session = null) {
  const validatedAction = validateActionType(actionType);

  // Resolve element_index against the session's stored observation.
  // The backend sends observation_id + element_index; we look up the full
  // cached observation (with _locator data) by matching the id.
  let resolvedObservation = args._observation || null;
  if (args.observation_id && session && session.lastObservation) {
    const cached = session.lastObservation;
    if (cached.observation_id === args.observation_id) {
      resolvedObservation = cached;
    } else {
      // observation_id doesn't match — page changed since the AI saw it
      const currentUrl = page.url();
      const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
      return {
        success: false,
        error: 'STALE_OBSERVATION: observation ' + args.observation_id +
               ' is stale (page is now ' + currentUrl + ')',
        observation: freshObs,
      };
    }
  }

  // Inject the resolved observation into args so the action handlers can use it
  if (resolvedObservation && args.element_index !== undefined) {
    args = { ...args, _observation: resolvedObservation };
  }

  // Legacy stale check using URL comparison when no cached observation
  if (args.observation_id && args.element_index !== undefined && !resolvedObservation) {
    const refObs = args._observation;
    if (refObs) {
      const currentUrl = page.url();
      const currentTitle = await page.title().catch(() => '');
      if (refObs.url !== currentUrl || refObs.title !== currentTitle) {
        const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
        return {
          success: false,
          error: 'STALE_OBSERVATION: page changed (was ' + refObs.url + ', now ' + currentUrl + ')',
          observation: freshObs,
        };
      }
    }
  }

  try {
    switch (validatedAction) {
      case 'NAVIGATE': {
        const url = args.url;
        if (!url || !url.trim()) {
          return { success: false, error: 'NAVIGATE requires a non-empty url' };
        }
        validateUrl(url);
        await page.goto(url, {
          waitUntil: 'domcontentloaded',
          timeout: 30000
        });
        await page.waitForTimeout(1000);
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        return { success: true, result: `Navigated to ${url}`, observation };
      }

      case 'CLICK': {
        // Support both element_index (new) and selector (legacy) approaches
        let clicked = false;
        const observation_ref = args.observation_id;
        const element_index = args.element_index;

        if (element_index !== undefined && args._observation) {
          // New element-index-based click
          const resolved = await resolveElement(page, args._observation, element_index);
          if (resolved._coordinate) {
            await page.mouse.click(resolved._coordinate.x, resolved._coordinate.y);
          } else {
            await resolved.click({ timeout: 5000 });
          }
          clicked = true;
        } else {
          // Legacy selector-based click
          const selector = args.selector || args.text;
          if (!selector) return { success: false, error: 'CLICK requires element_index or selector' };

          const strategies = [
            async () => await page.click(selector, { timeout: 5000 }),
            async () => await page.getByText(selector, { exact: false }).first().click({ timeout: 5000 }),
            async () => await page.getByRole('button', { name: selector }).first().click({ timeout: 5000 }),
            async () => await page.getByRole('link', { name: selector }).first().click({ timeout: 5000 }),
          ];

          for (const strategy of strategies) {
            try {
              await strategy();
              clicked = true;
              break;
            } catch (e) {
              // try next strategy
            }
          }
        }

        if (!clicked) {
          return { success: false, error: `Could not find clickable element` };
        }

        await page.waitForTimeout(500);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Clicked element`, observation };
      }

      case 'FILL': {
        const value = args.value || args.text;
        if (value === undefined) {
          return { success: false, error: 'FILL requires value' };
        }

        let filled = false;
        const element_index = args.element_index;

        if (element_index !== undefined && args._observation) {
          const resolved = await resolveElement(page, args._observation, element_index);
          if (resolved._coordinate) {
            return { success: false, error: 'Cannot fill a coordinate-based element' };
          }
          // Check for password field
          const el = args._observation.interactive_elements[element_index];
          if (el && el.type === 'password') {
            return { success: false, error: 'Filling password fields is not permitted' };
          }
          await resolved.fill(String(value), { timeout: 5000 });
          filled = true;
        } else {
          // Legacy selector-based fill
          const selector = args.selector;
          if (!selector) return { success: false, error: 'FILL requires element_index or selector' };

          if (selector.toLowerCase().includes('password') && String(value).length > 0) {
            return { success: false, error: 'Filling password fields is not permitted' };
          }
          try {
            const isPasswordType = await page.evaluate((sel) => {
              const el = document.querySelector(sel);
              return el && el.type === 'password';
            }, selector).catch(() => false);
            if (isPasswordType) {
              return { success: false, error: 'Filling password fields is not permitted' };
            }
          } catch (e) { /* selector may not be valid */ }

          try {
            await page.fill(selector, String(value), { timeout: 5000 });
            filled = true;
          } catch (e) {
            try {
              const label = args.description || args.label || selector;
              await page.getByLabel(label, { exact: false }).fill(String(value), { timeout: 5000 });
              filled = true;
            } catch (e2) {
              return { success: false, error: `Could not fill field '${selector}': ${e.message}` };
            }
          }
        }

        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Filled field with value`, observation };
      }

      case 'SELECT_OPTION': {
        const value = args.value;
        if (value === undefined) {
          return { success: false, error: 'SELECT_OPTION requires value' };
        }

        const element_index = args.element_index;
        if (element_index !== undefined && args._observation) {
          const resolved = await resolveElement(page, args._observation, element_index);
          if (resolved._coordinate) {
            return { success: false, error: 'Cannot select option on coordinate-based element' };
          }
          await resolved.selectOption(value, { timeout: 5000 });
        } else {
          const selector = args.selector;
          if (!selector) return { success: false, error: 'SELECT_OPTION requires element_index or selector' };
          await page.selectOption(selector, value, { timeout: 5000 });
        }

        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Selected option: ${value}`, observation };
      }

      case 'PRESS_KEY': {
        const key = args.key;
        if (!key) return { success: false, error: 'PRESS_KEY requires key' };

        const SAFE_KEYS = [
          'Enter', 'Tab', 'Escape', 'Backspace', 'Delete',
          'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight',
          'Home', 'End', 'PageUp', 'PageDown', 'Space',
        ];
        if (!SAFE_KEYS.includes(key)) {
          return { success: false, error: `Key '${key}' is not in the safe key list` };
        }

        if (args.element_index !== undefined && args._observation) {
          const resolved = await resolveElement(page, args._observation, args.element_index);
          if (!resolved._coordinate) {
            await resolved.press(key, { timeout: 5000 });
          } else {
            await page.keyboard.press(key);
          }
        } else {
          await page.keyboard.press(key);
        }

        await page.waitForTimeout(300);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Pressed key: ${key}`, observation };
      }

      case 'EXTRACT_TEXT': {
        const selector = args.selector || 'body';
        let text;

        try {
          if (selector === 'body' || selector === 'main' || selector === 'article') {
            text = await page.locator(selector).innerText({ timeout: 5000 });
          } else {
            text = await page.locator(selector).first().innerText({ timeout: 5000 });
          }
        } catch (e) {
          text = await page.evaluate(() => document.body.innerText);
        }

        if (text && text.length > 10000) text = text.substring(0, 10000) + '...';

        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: text, observation };
      }

      case 'SCREENSHOT': {
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        return {
          success: true,
          result: `Screenshot captured: ${args.label || 'page state'}`,
          screenshot: observation.screenshot?.base64 || null,
          observation,
        };
      }

      case 'SCROLL': {
        const direction = args.direction || 'down';
        const amount = Math.min(Number(args.amount) || 500, 3000);
        const delta = direction === 'up' ? -amount : amount;
        await page.evaluate((dy) => window.scrollBy(0, dy), delta);
        await page.waitForTimeout(300);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Scrolled ${direction} by ${amount}px`, observation };
      }

      case 'WAIT': {
        const ms = Math.min(Number(args.milliseconds) || 1000, 5000);
        await page.waitForTimeout(ms);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Waited ${ms}ms`, observation };
      }

      case 'GO_BACK': {
        await page.goBack({ timeout: 10000 });
        await page.waitForTimeout(500);
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        return { success: true, result: 'Navigated back', observation };
      }

      case 'GET_CONTENT': {
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        return { success: true, result: 'Page content retrieved', observation };
      }

      case 'DONE': {
        return { success: true, result: args.reason || 'Task execution completed', done: true };
      }

      default:
        return { success: false, error: `Unknown action: ${validatedAction}` };
    }
  } catch (error) {
    // On error, still try to get an observation for recovery
    let observation = null;
    try {
      observation = await buildObservation(page, taskId, { includeScreenshot: false });
    } catch { /* best effort */ }

    return {
      success: false,
      error: error.message || String(error),
      currentUrl: page.url(),
      observation,
    };
  }
}

/**
 * Get current page state as a structured observation.
 * Backward-compatible: also returns flat fields for legacy callers.
 */
async function getPageState(page, includeAccessibility = false, taskId = null) {
  const observation = await buildObservation(page, taskId, { includeScreenshot: false });

  // Backward-compatible flat fields
  return {
    currentUrl: observation.url,
    pageTitle: observation.title,
    pageText: observation.page_text.text,
    accessibilityTree: JSON.stringify(observation.interactive_elements.slice(0, 30), null, 2),
    observation,
  };
}

module.exports = { executeAction, getPageState };
