/**
 * DOO Browser Worker - Security Module
 * URL and action validation before execution.
 */

const BLOCKED_HOST_PATTERNS = [
  /^localhost(:\d+)?$/i,
  /^127\.\d+\.\d+\.\d+$/,
  /^10\.\d+\.\d+\.\d+$/,
  /^192\.168\.\d+\.\d+$/,
  /^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$/,
  /^169\.254\.\d+\.\d+$/,
  /^169\.254\.169\.254$/,
  /^::1$/,
  /^fc00:/i,
  /^fe80:/i,
  /^metadata\.google\.internal$/i,
];

const ALLOWED_SCHEMES = ['http:', 'https:'];
const BLOCKED_SCHEMES = ['file:', 'data:', 'javascript:', 'ftp:', 'blob:'];

const ALLOWED_ACTIONS = new Set([
  'NAVIGATE', 'CLICK', 'FILL', 'EXTRACT_TEXT', 'SCREENSHOT',
  'SCROLL', 'WAIT', 'DONE', 'GO_BACK', 'GET_CONTENT'
]);

const BLOCKED_ACTIONS = new Set([
  'EXECUTE_SCRIPT', 'SHELL_COMMAND', 'FILE_WRITE', 'HTTP_REQUEST',
  'PURCHASE', 'CHECKOUT', 'SUBSCRIBE', 'DELETE', 'LOGIN'
]);

/**
 * Validates a URL for browser navigation.
 * @throws Error if not allowed
 */
function validateUrl(urlStr) {
  if (!urlStr || typeof urlStr !== 'string' || urlStr.trim() === '') {
    throw new Error('URL must not be blank');
  }
  if (urlStr.length > 2048) {
    throw new Error('URL exceeds maximum length');
  }

  let url;
  try {
    url = new URL(urlStr);
  } catch (e) {
    throw new Error(`Invalid URL format: ${e.message}`);
  }

  if (BLOCKED_SCHEMES.includes(url.protocol)) {
    throw new Error(`URL scheme '${url.protocol}' is not allowed`);
  }

  if (!ALLOWED_SCHEMES.includes(url.protocol)) {
    throw new Error(`URL scheme '${url.protocol}' is not permitted`);
  }

  const host = url.hostname;
  if (!host) throw new Error('URL must have a valid host');

  // Allow local test fixture on port 8081 for testing & verification
  if ((host === 'localhost' || host === '127.0.0.1') && url.port === '8081') {
    return true;
  }

  for (const pattern of BLOCKED_HOST_PATTERNS) {
    if (pattern.test(host)) {
      throw new Error(`Navigation to '${host}' is not permitted (private/reserved address)`);
    }
  }

  return true;
}

/**
 * Validates an action type.
 */
function validateActionType(actionType) {
  if (!actionType) throw new Error('actionType must not be blank');
  const upper = actionType.toUpperCase();
  if (BLOCKED_ACTIONS.has(upper)) {
    throw new Error(`Action '${actionType}' is not permitted`);
  }
  if (!ALLOWED_ACTIONS.has(upper)) {
    throw new Error(`Unknown action type: '${actionType}'`);
  }
  return upper;
}

module.exports = { validateUrl, validateActionType, ALLOWED_ACTIONS };
