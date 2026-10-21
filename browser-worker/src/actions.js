/**
 * DOO Browser Worker - Playwright Action Executor
 * Executes individual browser actions within a session.
 * Actions reference elements by observation_id + element_index.
 */

const { validateUrl, validateActionType } = require('./security');
const { buildObservation, resolveElement } = require('./perception');

function formatValidIndices(elements) {
  if (!elements || elements.length === 0) return '[] (no interactive elements found)';
  const formatted = elements.map(el => {
    const label = el.label || el.placeholder || el.text || '';
    const labelPart = label ? ` "${label.substring(0, 30)}"` : '';
    const tag = el._locator?.tagName || el.type || 'el';
    return `[${el.index}] <${tag}> ${el.role}${labelPart}`;
  });
  return '[' + formatted.join(', ') + ']';
}

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
      const validSummary = formatValidIndices(freshObs.interactive_elements);
      console.warn(`[RESOLVE_FAILED] Task ${taskId} | observation ${args.observation_id} is stale (page is now ${currentUrl}). Valid indices: ${validSummary}`);
      return {
        success: false,
        error: 'STALE_OBSERVATION: observation ' + args.observation_id +
               ' is stale (page is now ' + currentUrl + '). Current valid indices: ' + validSummary,
        observation: freshObs,
      };
    }
  }

  // Inject the resolved observation into args so the action handlers can use it
  if (resolvedObservation && args.element_index !== undefined) {
    args = { ...args, _observation: resolvedObservation };
  }

  // If element_index was provided but no observation is available, fail with fresh observation
  if (args.element_index !== undefined && !resolvedObservation) {
    const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
    const validSummary = formatValidIndices(freshObs.interactive_elements);
    console.warn(`[RESOLVE_FAILED] Task ${taskId} | element_index=${args.element_index} specified without valid observation. Valid indices: ${validSummary}`);
    return {
      success: false,
      error: `RESOLVE_FAILED: element_index ${args.element_index} specified but no matching observation found. Current valid indices: ${validSummary}`,
      observation: freshObs,
    };
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
        const element_index = args.element_index;

        if (element_index !== undefined && args._observation) {
          let resolved;
          try {
            resolved = await resolveElement(page, args._observation, element_index);
          } catch (err) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | CLICK element_index=${element_index} could not be resolved: ${err.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} could not be resolved (${err.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }

          try {
            if (resolved._coordinate) {
              await page.mouse.click(resolved._coordinate.x, resolved._coordinate.y);
            } else {
              await resolved.click({ timeout: 5000 });
            }
            clicked = true;
          } catch (clickErr) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | CLICK element_index=${element_index} interaction failed: ${clickErr.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} interaction failed (${clickErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
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
          const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
          const validSummary = formatValidIndices(freshObs.interactive_elements);
          return {
            success: false,
            error: `RESOLVE_FAILED: Could not find clickable element. Current valid indices: ${validSummary}`,
            observation: freshObs,
          };
        }

        try {
          await page.waitForLoadState('domcontentloaded', { timeout: 3000 });
        } catch { /* if no navigation occurred */ }
        await page.waitForTimeout(500);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        return { success: true, result: `Clicked element`, observation };
      }

      case 'FILL': {
        const value = args.value !== undefined ? args.value : args.text;
        if (value === undefined) {
          return { success: false, error: 'FILL requires value' };
        }

        let filled = false;
        const element_index = args.element_index;

        if (element_index !== undefined && args._observation) {
          let resolved;
          try {
            resolved = await resolveElement(page, args._observation, element_index);
          } catch (err) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | FILL element_index=${element_index} could not be resolved: ${err.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} could not be resolved (${err.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }

          if (resolved._coordinate) {
            return { success: false, error: 'Cannot fill a coordinate-based element' };
          }

          try {
            await resolved.fill(String(value), { timeout: 5000 });
            filled = true;
          } catch (fillErr) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | FILL element_index=${element_index} interaction failed: ${fillErr.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} interaction failed (${fillErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
        } else {
          // Legacy selector-based fill
          const selector = args.selector;
          if (!selector) return { success: false, error: 'FILL requires element_index or selector' };

          try {
            await page.fill(selector, String(value), { timeout: 5000 });
            filled = true;
          } catch (e) {
            try {
              const label = args.description || args.label || selector;
              await page.getByLabel(label, { exact: false }).fill(String(value), { timeout: 5000 });
              filled = true;
            } catch (e2) {
              const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
              const validSummary = formatValidIndices(freshObs.interactive_elements);
              return {
                success: false,
                error: `RESOLVE_FAILED: Could not fill field '${selector}': ${e.message}. Current valid indices: ${validSummary}`,
                observation: freshObs,
              };
            }
          }
        }

        await page.waitForTimeout(300);
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
          let resolved;
          try {
            resolved = await resolveElement(page, args._observation, element_index);
          } catch (err) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | SELECT_OPTION element_index=${element_index} could not be resolved: ${err.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} could not be resolved (${err.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }

          if (resolved._coordinate) {
            return { success: false, error: 'Cannot select option on coordinate-based element' };
          }

          try {
            await resolved.selectOption(value, { timeout: 5000 });
          } catch (selErr) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | SELECT_OPTION element_index=${element_index} interaction failed: ${selErr.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} interaction failed (${selErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
        } else {
          const selector = args.selector;
          if (!selector) return { success: false, error: 'SELECT_OPTION requires element_index or selector' };
          await page.selectOption(selector, value, { timeout: 5000 });
        }

        try {
          await page.waitForLoadState('domcontentloaded', { timeout: 3000 });
        } catch { /* ignore */ }
        await page.waitForTimeout(500);
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
          let resolved;
          try {
            resolved = await resolveElement(page, args._observation, args.element_index);
          } catch (err) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | PRESS_KEY element_index=${args.element_index} could not be resolved: ${err.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${args.element_index} could not be resolved (${err.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }

          try {
            if (!resolved._coordinate) {
              await resolved.press(key, { timeout: 5000 });
            } else {
              await page.keyboard.press(key);
            }
          } catch (pressErr) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            console.warn(`[RESOLVE_FAILED] Task ${taskId} | PRESS_KEY element_index=${args.element_index} interaction failed: ${pressErr.message}. Valid indices: ${validSummary}`);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${args.element_index} interaction failed (${pressErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
        } else {
          await page.keyboard.press(key);
        }

        if (key === 'Enter') {
          try {
            await page.waitForLoadState('domcontentloaded', { timeout: 3000 });
          } catch { /* if no navigation happened */ }
        }
        await page.waitForTimeout(500);
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

    const validSummary = observation ? formatValidIndices(observation.interactive_elements) : 'none';
    const isResolveFailure = error.message && (
      error.message.includes('resolve') ||
      error.message.includes('out of range') ||
      error.message.includes('Target closed') ||
      error.message.includes('not attached')
    );
    const errorMsg = isResolveFailure
      ? `RESOLVE_FAILED: ${error.message || String(error)}. Current valid indices: ${validSummary}`
      : `${error.message || String(error)}`;

    if (isResolveFailure) {
      console.warn(`[RESOLVE_FAILED] Task ${taskId} | Uncaught error: ${error.message}. Valid indices: ${validSummary}`);
    }

    return {
      success: false,
      error: errorMsg,
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
