/**
 * DOO Browser Worker - Playwright Action Executor
 * Executes individual browser actions within a session.
 */

const { validateUrl, validateActionType } = require('./security');

/**
 * Execute a single action in the given Playwright page.
 * Returns { success, result, error, screenshot, currentUrl, pageTitle, pageText, accessibilityTree }
 */
async function executeAction(page, actionType, args = {}) {
  const validatedAction = validateActionType(actionType);
  
  try {
    switch (validatedAction) {
      case 'NAVIGATE': {
        const url = args.url;
        validateUrl(url);
        await page.goto(url, { 
          waitUntil: 'domcontentloaded', 
          timeout: 30000 
        });
        // Wait a bit for dynamic content
        await page.waitForTimeout(1000);
        const state = await getPageState(page, true);
        return { success: true, result: `Navigated to ${url}`, ...state };
      }

      case 'CLICK': {
        const selector = args.selector || args.text;
        if (!selector) return { success: false, error: 'CLICK requires selector or text' };
        
        // Try multiple selector strategies
        let clicked = false;
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
        
        if (!clicked) {
          return { success: false, error: `Could not find clickable element: ${selector}` };
        }
        
        await page.waitForTimeout(500);
        const state = await getPageState(page, false);
        return { success: true, result: `Clicked: ${selector}`, ...state };
      }

      case 'FILL': {
        const selector = args.selector;
        const value = args.value;
        if (!selector || value === undefined) {
          return { success: false, error: 'FILL requires selector and value' };
        }
        
        // Never fill password fields
        if (selector.toLowerCase().includes('password') && value.length > 0) {
          return { success: false, error: 'Filling password fields is not permitted' };
        }
        // Also check if the target element is type=password in the DOM
        try {
          const isPasswordType = await page.evaluate((sel) => {
            const el = document.querySelector(sel);
            return el && el.type === 'password';
          }, selector).catch(() => false);
          if (isPasswordType) {
            return { success: false, error: 'Filling password fields is not permitted' };
          }
        } catch (e) { /* selector may not be valid for querySelector, continue */ }
        
        try {
          await page.fill(selector, String(value), { timeout: 5000 });
        } catch (e) {
          // Try locating by label or placeholder
          try {
            const label = args.description || args.label || selector;
            await page.getByLabel(label, { exact: false }).fill(String(value), { timeout: 5000 });
          } catch (e2) {
            return { success: false, error: `Could not fill field '${selector}': ${e.message}` };
          }
        }
        
        const state = await getPageState(page, false);
        return { success: true, result: `Filled '${selector}' with value`, ...state };
      }

      case 'EXTRACT_TEXT': {
        const selector = args.selector || 'body';
        const label = args.label || 'Page content';
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
        
        // Truncate to reasonable size
        if (text && text.length > 10000) text = text.substring(0, 10000) + '...';
        
        const state = await getPageState(page, false);
        return { success: true, result: text, ...state };
      }

      case 'SCREENSHOT': {
        const label = args.label || 'Screenshot';
        const screenshot = await page.screenshot({ 
          type: 'jpeg', 
          quality: 60,
          fullPage: false 
        });
        const base64 = screenshot.toString('base64');
        const state = await getPageState(page, false);
        return { 
          success: true, 
          result: `Screenshot captured: ${label}`,
          screenshot: base64,
          ...state
        };
      }

      case 'SCROLL': {
        const direction = args.direction || 'down';
        const amount = Math.min(Number(args.amount) || 500, 3000);
        const delta = direction === 'up' ? -amount : amount;
        await page.evaluate((dy) => window.scrollBy(0, dy), delta);
        await page.waitForTimeout(300);
        const state = await getPageState(page, false);
        return { success: true, result: `Scrolled ${direction} by ${amount}px`, ...state };
      }

      case 'WAIT': {
        const ms = Math.min(Number(args.milliseconds) || 1000, 5000);
        await page.waitForTimeout(ms);
        const state = await getPageState(page, false);
        return { success: true, result: `Waited ${ms}ms`, ...state };
      }

      case 'GO_BACK': {
        await page.goBack({ timeout: 10000 });
        await page.waitForTimeout(500);
        const state = await getPageState(page, true);
        return { success: true, result: 'Navigated back', ...state };
      }

      case 'GET_CONTENT': {
        const state = await getPageState(page, true);
        return { success: true, result: 'Page content retrieved', ...state };
      }

      case 'DONE': {
        return { success: true, result: args.reason || 'Task execution completed', done: true };
      }

      default:
        return { success: false, error: `Unknown action: ${validatedAction}` };
    }
  } catch (error) {
    return { 
      success: false, 
      error: error.message || String(error),
      currentUrl: page.url(),
    };
  }
}

/**
 * Get current page state (URL, title, text content, accessibility).
 */
async function getPageState(page, includeAccessibility = false) {
  try {
    const currentUrl = page.url();
    const pageTitle = await page.title().catch(() => '');
    
    // Get visible text (truncated)
    let pageText = '';
    try {
      pageText = await page.evaluate(() => {
        // Remove scripts and styles
        const clone = document.cloneNode(true);
        const toRemove = clone.querySelectorAll('script, style, nav, footer, aside');
        toRemove.forEach(el => el.remove());
        return (clone.body || clone).innerText?.substring(0, 8000) || '';
      });
    } catch (e) {
      pageText = '';
    }

    let accessibilityTree = '';
    if (includeAccessibility) {
      try {
        // Get simplified accessibility snapshot
        const snapshot = await page.accessibility.snapshot({ interestingOnly: true });
        accessibilityTree = JSON.stringify(snapshot, null, 2).substring(0, 3000);
      } catch (e) {
        accessibilityTree = '';
      }
    }

    return { currentUrl, pageTitle, pageText, accessibilityTree };
  } catch (e) {
    return { currentUrl: page.url(), pageTitle: '', pageText: '', accessibilityTree: '' };
  }
}

module.exports = { executeAction, getPageState };
