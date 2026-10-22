/**
 * DOO Browser Console Logger
 * 
 * Provides rich, styled logging for:
 * 1. The 5 Core Execution Stages:
 *    - Stage 1/5: CLASSIFY (Analyzing user intent & workflow routing)
 *    - Stage 2/5: PLAN (Decomposing request into step-by-step browser plan)
 *    - Stage 3/5: SELECT_ACTION (Selecting & executing browser actions via Playwright)
 *    - Stage 4/5: SYNTHESIZE (Synthesizing extracted evidence & outcome)
 *    - Stage 5/5: VERIFY (Validating completion against success criteria)
 * 2. User Events:
 *    - What user event occurred
 *    - What data is being sent (outgoing payload, safely masked)
 *    - What process is happening in response
 * 3. Network & Stream Events (API requests & SSE updates)
 */

export type StageIndex = 1 | 2 | 3 | 4 | 5;

const STAGE_CONFIGS: Record<StageIndex, { name: string; bg: string; color: string; desc: string }> = {
  1: {
    name: 'CLASSIFY',
    bg: '#0284c7', // Sky-600
    color: '#ffffff',
    desc: 'Stage 1/5: Analyzing user intent and selecting workflow route (Web Research, Comparison, or UI Interaction)',
  },
  2: {
    name: 'PLAN',
    bg: '#7c3aed', // Violet-600
    color: '#ffffff',
    desc: 'Stage 2/5: Decomposing user request into step-by-step browser navigation plan and success criteria',
  },
  3: {
    name: 'SELECT_ACTION',
    bg: '#059669', // Emerald-600
    color: '#ffffff',
    desc: 'Stage 3/5: Observing page state, selecting next browser action, and executing via Playwright',
  },
  4: {
    name: 'SYNTHESIZE',
    bg: '#d97706', // Amber-600
    color: '#ffffff',
    desc: 'Stage 4/5: Extracting and synthesizing visual & textual evidence into structured findings',
  },
  5: {
    name: 'VERIFY',
    bg: '#4f46e5', // Indigo-600
    color: '#ffffff',
    desc: 'Stage 5/5: Validating final task outcome against defined success criteria',
  },
};

/**
 * Recursively clone and mask sensitive fields (passwords, tokens) before logging to console.
 */
function sanitizeData(data: unknown): unknown {
  if (data === null || data === undefined) return data;
  if (typeof data !== 'object') return data;

  if (Array.isArray(data)) {
    return data.map(sanitizeData);
  }

  const sanitized: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(data as Record<string, unknown>)) {
    const lowerKey = key.toLowerCase();
    if (lowerKey.includes('password') || lowerKey.includes('secret') || lowerKey.includes('token')) {
      sanitized[key] = typeof val === 'string' && val.length > 0 ? '•••••••• (protected)' : val;
    } else if (typeof val === 'object' && val !== null) {
      sanitized[key] = sanitizeData(val);
    } else {
      sanitized[key] = val;
    }
  }
  return sanitized;
}

export const dooLogger = {
  /**
   * Log an execution stage occurrence.
   */
  stage(stageIndex: StageIndex, stageName: string, processDetail: string, data?: unknown) {
    const config = STAGE_CONFIGS[stageIndex] || {
      name: stageName,
      bg: '#3b82f6',
      color: '#ffffff',
      desc: `Stage ${stageIndex}/5: ${stageName}`,
    };

    const badgeStyle = `background: ${config.bg}; color: ${config.color}; font-weight: bold; padding: 2px 8px; border-radius: 4px; font-size: 11px;`;
    const labelStyle = `color: #38bdf8; font-weight: bold;`;
    const textStyle = `color: #e2e8f0; font-size: 12px;`;

    console.groupCollapsed(
      `%c[STAGE ${stageIndex}/5 · ${config.name}]%c ${processDetail}`,
      badgeStyle,
      labelStyle
    );
    console.log(`%cDescription:%c ${config.desc}`, 'color: #94a3b8; font-weight: bold;', textStyle);
    console.log(`%cProcess:%c ${processDetail}`, 'color: #94a3b8; font-weight: bold;', textStyle);
    if (data !== undefined) {
      console.log('%cStage Data / Context:', 'color: #34d399; font-weight: bold;', sanitizeData(data));
    }
    console.groupEnd();
  },

  /**
   * Automatically detect stage from backend debug messages.
   */
  handleBackendMessage(message: string, data?: unknown) {
    if (!message) return;

    if (message.includes('[STEP 1/5]') || message.includes('CLASSIFY')) {
      this.stage(1, 'CLASSIFY', message, data);
    } else if (message.includes('[STEP 2/5]') || message.includes('PLAN')) {
      this.stage(2, 'PLAN', message, data);
    } else if (message.includes('[STEP 3/5]') || message.includes('SELECT_ACTION')) {
      this.stage(3, 'SELECT_ACTION', message, data);
    } else if (message.includes('[STEP 4/5]') || message.includes('SYNTHESIZE')) {
      this.stage(4, 'SYNTHESIZE', message, data);
    } else if (message.includes('[STEP 5/5]') || message.includes('VERIFY')) {
      this.stage(5, 'VERIFY', message, data);
    } else {
      console.log(`%c[DOO ENGINE]%c ${message}`, 'background: #0f172a; color: #38bdf8; padding: 2px 6px; border-radius: 3px; font-weight: bold;', 'color: #94a3b8;', data ? sanitizeData(data) : '');
    }
  },

  /**
   * Log user-initiated events with process explanation and outgoing data payload.
   */
  userEvent(options: {
    event: string;
    process: string;
    data?: unknown;
    nextStep?: string;
  }) {
    const badgeStyle = 'background: #ea580c; color: #ffffff; font-weight: bold; padding: 2px 8px; border-radius: 4px; font-size: 11px;';
    const titleStyle = 'color: #fb923c; font-weight: bold; font-size: 12px;';
    const labelStyle = 'color: #94a3b8; font-weight: bold;';
    const bodyStyle = 'color: #e2e8f0; font-size: 12px;';

    console.group(
      `%c[USER EVENT]%c ${options.event}`,
      badgeStyle,
      titleStyle
    );
    console.log(`%cProcess Happening:%c ${options.process}`, labelStyle, bodyStyle);
    if (options.data !== undefined) {
      console.log('%cData Outgoing (Payload):%c', labelStyle, bodyStyle, sanitizeData(options.data));
    }
    if (options.nextStep) {
      console.log(`%cNext Workflow Step:%c ${options.nextStep}`, labelStyle, bodyStyle);
    }
    console.groupEnd();
  },

  /**
   * Log outgoing HTTP API calls with data and process description.
   */
  apiRequest(method: string, endpoint: string, process: string, payload?: unknown) {
    const badgeStyle = 'background: #1e293b; color: #38bdf8; border: 1px solid #38bdf8; font-weight: bold; padding: 1px 6px; border-radius: 3px; font-size: 10px;';
    console.groupCollapsed(`%cAPI ${method}%c ${endpoint} — ${process}`, badgeStyle, 'color: #94a3b8;');
    console.log('%cProcess:%c ' + process, 'color: #94a3b8; font-weight: bold;', 'color: #e2e8f0;');
    if (payload !== undefined) {
      console.log('%cPayload Sent:%c', 'color: #94a3b8; font-weight: bold;', '', sanitizeData(payload));
    }
    console.groupEnd();
  },

  /**
   * Log incoming HTTP API responses.
   */
  apiResponse(method: string, endpoint: string, status: number, data?: unknown) {
    const isOk = status >= 200 && status < 300;
    const badgeStyle = isOk
      ? 'background: #064e3b; color: #34d399; font-weight: bold; padding: 1px 6px; border-radius: 3px; font-size: 10px;'
      : 'background: #7f1d1d; color: #f87171; font-weight: bold; padding: 1px 6px; border-radius: 3px; font-size: 10px;';
    console.groupCollapsed(`%cHTTP ${status}%c ${method} ${endpoint}`, badgeStyle, 'color: #94a3b8;');
    if (data !== undefined) {
      console.log('%cResponse Received:%c', 'color: #94a3b8; font-weight: bold;', '', sanitizeData(data));
    }
    console.groupEnd();
  },

  /**
   * Log SSE stream updates.
   */
  streamEvent(eventType: string, process: string, data?: unknown) {
    const badgeStyle = 'background: #1e1b4b; color: #818cf8; border: 1px solid #6366f1; font-weight: bold; padding: 1px 6px; border-radius: 3px; font-size: 10px;';
    console.groupCollapsed(`%cSSE ${eventType.toUpperCase()}%c ${process}`, badgeStyle, 'color: #c7d2fe;');
    if (data !== undefined) {
      console.log('%cEvent Data:%c', 'color: #94a3b8; font-weight: bold;', '', sanitizeData(data));
    }
    console.groupEnd();
  },
};
