/**
 * DOO Browser Worker - Playwright Action Executor
 * Executes individual browser actions within a session.
 * Actions reference elements by observation_id + element_index.
 */

const { validateUrl, validateActionType } = require('./security');
const { buildObservation, resolveElement } = require('./perception');

/**
 * Randomised human-like pause to avoid bot-detection rate limits.
 * Replaces all fixed waitForTimeout values.
 * @param {number} min  minimum wait in ms (default 300)
 * @param {number} max  maximum wait in ms (default 1200)
 */
async function humanPause(min = 300, max = 1200) {
  const ms = Math.floor(min + Math.random() * (max - min));
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Back off on 429 / rate-limit responses.
 * @param {import('playwright').Response|null} response
 * @returns {Promise<void>}
 */
async function maybeBackoff(response) {
  if (!response) return;
  if (response.status() === 429) {
    const retryAfter = parseInt(response.headers()['retry-after'] || '5', 10);
    const waitMs = Math.min(retryAfter * 1000, 30000); // cap at 30 s
    console.warn(`[429] Rate-limited — waiting ${waitMs}ms before proceeding`);
    await humanPause(waitMs, waitMs + 500);
  }
}

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

function actionLog(taskId, actionType, data) {
  const ts = new Date().toISOString();
  const tag = data.success ? 'ACTION_OK' : 'ACTION_FAIL';
  const parts = [`[${tag}] Task ${taskId} | ${actionType}`];
  if (data.element_index !== undefined) parts.push(`element_index=${data.element_index}`);
  if (data.observation_id) parts.push(`obs_id=${data.observation_id}`);
  if (data.session_obs_id) parts.push(`session_obs_id=${data.session_obs_id}`);
  if (data.obs_match !== undefined) parts.push(`obs_match=${data.obs_match}`);
  if (data.resolve_strategy) parts.push(`resolved_via=${data.resolve_strategy}`);
  if (data.error) parts.push(`error=${data.error}`);
  if (data.result_obs_id) parts.push(`new_obs_id=${data.result_obs_id}`);
  console.log(parts.join(' | '));
}

/**
 * Resolve the observation to use for element-index actions.
 * Prefers the session's cached observation (has _locator data), but falls back
 * to building a fresh observation from the live page if the cached one is stale
 * or missing. This eliminates the hard STALE_OBSERVATION failure that previously
 * caused retry loops.
 */
async function resolveObservationForAction(page, args, session, taskId) {
  const requestedObsId = args.observation_id || null;
  const sessionObs = session?.lastObservation || null;
  const sessionObsId = sessionObs?.observation_id || null;

  // Case 1: observation_ids match — use the cached observation (has full _locator data)
  if (requestedObsId && sessionObs && sessionObsId === requestedObsId) {
    return { observation: sessionObs, match: 'exact', stale: false };
  }

  // Case 2: mismatch or missing — build a fresh observation from the live page
  // This is the key fix: instead of returning a hard STALE_OBSERVATION error,
  // we build a fresh observation that reflects the CURRENT page state.
  const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });

  if (requestedObsId && sessionObsId && sessionObsId !== requestedObsId) {
    console.warn(`[OBS_STALE] Task ${taskId} | requested=${requestedObsId} session=${sessionObsId} — using fresh obs ${freshObs.observation_id}`);
  } else if (!sessionObs) {
    console.warn(`[OBS_MISS] Task ${taskId} | no cached observation — using fresh obs ${freshObs.observation_id}`);
  }

  // Update session with the fresh observation so subsequent actions stay in sync
  if (session) {
    session.lastObservation = freshObs;
  }

  return { observation: freshObs, match: requestedObsId ? 'fresh_rebuild' : 'none', stale: !!requestedObsId };
}

/**
 * Execute a single action in the given Playwright page.
 * Returns { success, result, error, observation, screenshot }
 */
async function executeAction(page, actionType, args = {}, taskId = null, session = null) {
  const validatedAction = validateActionType(actionType);
  const logData = {
    element_index: args.element_index,
    observation_id: args.observation_id || null,
    session_obs_id: session?.lastObservation?.observation_id || null,
  };

  // Resolve observation for element-index-based actions
  let resolvedObservation = null;
  if (args.element_index !== undefined) {
    const obsResult = await resolveObservationForAction(page, args, session, taskId);
    resolvedObservation = obsResult.observation;
    logData.obs_match = obsResult.match;

    if (!resolvedObservation) {
      const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
      const validSummary = formatValidIndices(freshObs.interactive_elements);
      logData.success = false;
      logData.error = 'NO_OBSERVATION';
      actionLog(taskId, validatedAction, logData);
      return {
        success: false,
        error: `NO_OBSERVATION: could not obtain observation for element resolution. Valid indices: ${validSummary}`,
        observation: freshObs,
      };
    }

    args = { ...args, _observation: resolvedObservation };
  } else if (args._observation) {
    // Non-element-index action that still carries an observation (passthrough)
    resolvedObservation = args._observation;
  }

  try {
    switch (validatedAction) {
      case 'NAVIGATE': {
        const url = args.url;
        if (!url || !url.trim()) {
          logData.success = false;
          logData.error = 'NAVIGATE requires a non-empty url';
          actionLog(taskId, validatedAction, logData);
          return { success: false, error: 'NAVIGATE requires a non-empty url' };
        }
        validateUrl(url);
        const gotoResponse = await page.goto(url, {
          waitUntil: 'domcontentloaded',
          timeout: 30000
        });
        await maybeBackoff(gotoResponse);
        await humanPause(700, 1400);
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: `Navigated to ${url}`, observation };
      }

      case 'CLICK': {
        let clicked = false;
        const element_index = args.element_index;

        if (element_index !== undefined && args._observation) {
          let resolved;
          try {
            resolved = await resolveElement(page, args._observation, element_index);
          } catch (err) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            logData.success = false;
            logData.error = `RESOLVE_FAILED: ${err.message}`;
            actionLog(taskId, validatedAction, logData);
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
            logData.success = false;
            logData.error = `CLICK_FAILED: ${clickErr.message}`;
            actionLog(taskId, validatedAction, logData);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} interaction failed (${clickErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
        } else {
          const selector = args.selector || args.text;
          if (!selector) {
            logData.success = false;
            logData.error = 'CLICK requires element_index or selector';
            actionLog(taskId, validatedAction, logData);
            return { success: false, error: 'CLICK requires element_index or selector' };
          }

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
          logData.success = false;
          logData.error = 'Could not find clickable element';
          actionLog(taskId, validatedAction, logData);
          return {
            success: false,
            error: `RESOLVE_FAILED: Could not find clickable element. Current valid indices: ${validSummary}`,
            observation: freshObs,
          };
        }

        try {
          await page.waitForLoadState('domcontentloaded', { timeout: 3000 });
        } catch { /* if no navigation occurred */ }
        await humanPause(300, 800);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: `Clicked element`, observation };
      }

      case 'FILL': {
        const value = args.value !== undefined ? args.value : args.text;
        if (value === undefined) {
          logData.success = false;
          logData.error = 'FILL requires value';
          actionLog(taskId, validatedAction, logData);
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
            logData.success = false;
            logData.error = `RESOLVE_FAILED: ${err.message}`;
            actionLog(taskId, validatedAction, logData);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} could not be resolved (${err.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }

          if (resolved._coordinate) {
            logData.success = false;
            logData.error = 'Cannot fill coordinate-based element';
            actionLog(taskId, validatedAction, logData);
            return { success: false, error: 'Cannot fill a coordinate-based element' };
          }

          try {
            await resolved.fill(String(value), { timeout: 5000 });
            filled = true;
          } catch (fillErr) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            logData.success = false;
            logData.error = `FILL_FAILED: ${fillErr.message}`;
            actionLog(taskId, validatedAction, logData);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} interaction failed (${fillErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
        } else {
          const selector = args.selector;
          if (!selector) {
            logData.success = false;
            logData.error = 'FILL requires element_index or selector';
            actionLog(taskId, validatedAction, logData);
            return { success: false, error: 'FILL requires element_index or selector' };
          }

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
              logData.success = false;
              logData.error = `FILL_FAILED: ${e.message}`;
              actionLog(taskId, validatedAction, logData);
              return {
                success: false,
                error: `RESOLVE_FAILED: Could not fill field '${selector}': ${e.message}. Current valid indices: ${validSummary}`,
                observation: freshObs,
              };
            }
          }
        }

        await humanPause(200, 600);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: `Filled field with value`, observation };
      }

      case 'SELECT_OPTION': {
        const value = args.value;
        if (value === undefined) {
          logData.success = false;
          logData.error = 'SELECT_OPTION requires value';
          actionLog(taskId, validatedAction, logData);
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
            logData.success = false;
            logData.error = `RESOLVE_FAILED: ${err.message}`;
            actionLog(taskId, validatedAction, logData);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} could not be resolved (${err.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }

          if (resolved._coordinate) {
            logData.success = false;
            logData.error = 'Cannot select on coordinate-based element';
            actionLog(taskId, validatedAction, logData);
            return { success: false, error: 'Cannot select option on coordinate-based element' };
          }

          try {
            await resolved.selectOption(value, { timeout: 5000 });
          } catch (selErr) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            logData.success = false;
            logData.error = `SELECT_FAILED: ${selErr.message}`;
            actionLog(taskId, validatedAction, logData);
            return {
              success: false,
              error: `RESOLVE_FAILED: element_index ${element_index} interaction failed (${selErr.message}). Current valid indices: ${validSummary}`,
              observation: freshObs,
            };
          }
        } else {
          const selector = args.selector;
          if (!selector) {
            logData.success = false;
            logData.error = 'SELECT_OPTION requires element_index or selector';
            actionLog(taskId, validatedAction, logData);
            return { success: false, error: 'SELECT_OPTION requires element_index or selector' };
          }
          await page.selectOption(selector, value, { timeout: 5000 });
        }

        try {
          await page.waitForLoadState('domcontentloaded', { timeout: 3000 });
        } catch { /* ignore */ }
        await humanPause(300, 800);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: `Selected option: ${value}`, observation };
      }

      case 'PRESS_KEY': {
        const key = args.key;
        if (!key) {
          logData.success = false;
          logData.error = 'PRESS_KEY requires key';
          actionLog(taskId, validatedAction, logData);
          return { success: false, error: 'PRESS_KEY requires key' };
        }

        const SAFE_KEYS = [
          'Enter', 'Tab', 'Escape', 'Backspace', 'Delete',
          'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight',
          'Home', 'End', 'PageUp', 'PageDown', 'Space',
        ];
        if (!SAFE_KEYS.includes(key)) {
          logData.success = false;
          logData.error = `Key '${key}' not in safe list`;
          actionLog(taskId, validatedAction, logData);
          return { success: false, error: `Key '${key}' is not in the safe key list` };
        }

        if (args.element_index !== undefined && args._observation) {
          let resolved;
          try {
            resolved = await resolveElement(page, args._observation, args.element_index);
          } catch (err) {
            const freshObs = await buildObservation(page, taskId, { includeScreenshot: false });
            const validSummary = formatValidIndices(freshObs.interactive_elements);
            logData.success = false;
            logData.error = `RESOLVE_FAILED: ${err.message}`;
            actionLog(taskId, validatedAction, logData);
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
            logData.success = false;
            logData.error = `PRESS_FAILED: ${pressErr.message}`;
            actionLog(taskId, validatedAction, logData);
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
        await humanPause(300, 800);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
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
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: text, observation };
      }

      case 'SCREENSHOT': {
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
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
        await humanPause(200, 600);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: `Scrolled ${direction} by ${amount}px`, observation };
      }

      case 'WAIT': {
        const ms = Math.min(Number(args.milliseconds) || 1000, 5000);
        await humanPause(ms, ms + 100);
        const observation = await buildObservation(page, taskId, { includeScreenshot: false });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: `Waited ${ms}ms`, observation };
      }

      case 'GO_BACK': {
        await page.goBack({ timeout: 10000 });
        await humanPause(300, 800);
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: 'Navigated back', observation };
      }

      case 'GET_CONTENT': {
        const observation = await buildObservation(page, taskId, { includeScreenshot: true });
        logData.success = true;
        logData.result_obs_id = observation.observation_id;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: 'Page content retrieved', observation };
      }

      case 'DONE': {
        logData.success = true;
        actionLog(taskId, validatedAction, logData);
        return { success: true, result: args.reason || 'Task execution completed', done: true };
      }

      default:
        logData.success = false;
        logData.error = `Unknown action: ${validatedAction}`;
        actionLog(taskId, validatedAction, logData);
        return { success: false, error: `Unknown action: ${validatedAction}` };
    }
  } catch (error) {
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

    logData.success = false;
    logData.error = errorMsg;
    actionLog(taskId, validatedAction, logData);

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

  return {
    currentUrl: observation.url,
    pageTitle: observation.title,
    pageText: observation.page_text.text,
    accessibilityTree: JSON.stringify(observation.interactive_elements.slice(0, 30), null, 2),
    observation,
  };
}

module.exports = { executeAction, getPageState };
