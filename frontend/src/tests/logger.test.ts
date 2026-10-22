import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { dooLogger } from '../lib/logger';

describe('dooLogger', () => {
  let logSpy: any;
  let groupSpy: any;
  let groupCollapsedSpy: any;
  let groupEndSpy: any;

  beforeEach(() => {
    logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    groupSpy = vi.spyOn(console, 'group').mockImplementation(() => {});
    groupCollapsedSpy = vi.spyOn(console, 'groupCollapsed').mockImplementation(() => {});
    groupEndSpy = vi.spyOn(console, 'groupEnd').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('logs each of the 5 execution stages with appropriate stage badges and descriptions', () => {
    dooLogger.stage(1, 'CLASSIFY', 'Analyzing query intent', { query: 'test' });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 1/5 · CLASSIFY]'),
      expect.stringContaining('background: #0284c7'),
      expect.any(String)
    );

    dooLogger.stage(2, 'PLAN', 'Generated 3 steps', { steps: ['step1', 'step2'] });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 2/5 · PLAN]'),
      expect.stringContaining('background: #7c3aed'),
      expect.any(String)
    );

    dooLogger.stage(3, 'SELECT_ACTION', 'Action: CLICK', { index: 12 });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 3/5 · SELECT_ACTION]'),
      expect.stringContaining('background: #059669'),
      expect.any(String)
    );

    dooLogger.stage(4, 'SYNTHESIZE', 'Extracted 4 snippets', { count: 4 });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 4/5 · SYNTHESIZE]'),
      expect.stringContaining('background: #d97706'),
      expect.any(String)
    );

    dooLogger.stage(5, 'VERIFY', 'Verification passed', { success: true });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 5/5 · VERIFY]'),
      expect.stringContaining('background: #4f46e5'),
      expect.any(String)
    );
  });

  it('automatically detects and routes backend debug stage messages', () => {
    dooLogger.handleBackendMessage('[STEP 1/5] CLASSIFY — starting', { keyPool: 'key-1' });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 1/5 · CLASSIFY]'),
      expect.any(String),
      expect.any(String)
    );

    dooLogger.handleBackendMessage('[STEP 2/5] PLAN — 4 steps', { plan: '...' });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 2/5 · PLAN]'),
      expect.any(String),
      expect.any(String)
    );

    dooLogger.handleBackendMessage('[STEP 3/5] SELECT_ACTION — chose: CLICK', { action: 'CLICK' });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 3/5 · SELECT_ACTION]'),
      expect.any(String),
      expect.any(String)
    );

    dooLogger.handleBackendMessage('[STEP 4/5] SYNTHESIZE — findings', {});
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 4/5 · SYNTHESIZE]'),
      expect.any(String),
      expect.any(String)
    );

    dooLogger.handleBackendMessage('[STEP 5/5] VERIFY — criteria matched', {});
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('[STAGE 5/5 · VERIFY]'),
      expect.any(String),
      expect.any(String)
    );
  });

  it('logs user events with process details, masked data payload, and next steps', () => {
    dooLogger.userEvent({
      event: 'Submit Credentials',
      process: 'User submitted credentials for login',
      data: {
        username: 'alice',
        password: 'supersecretpassword123',
        token: 'secret-token-xyz',
        nested: {
          clientPassword: 'nested-secret',
          publicField: 'safe',
        },
      },
      nextStep: 'Submitting response to backend',
    });

    expect(groupSpy).toHaveBeenCalledWith(
      expect.stringContaining('[USER EVENT]'),
      expect.stringContaining('background: #ea580c'),
      expect.any(String)
    );

    // Verify password and token are masked
    const loggedCalls = logSpy.mock.calls;
    const dataCall = loggedCalls.find((call: any[]) =>
      call[0] && typeof call[0] === 'string' && call[0].includes('Data Outgoing')
    );
    expect(dataCall).toBeDefined();
    const loggedData = dataCall[3];
    expect(loggedData.password).toBe('•••••••• (protected)');
    expect(loggedData.token).toBe('•••••••• (protected)');
    expect(loggedData.nested.clientPassword).toBe('•••••••• (protected)');
    expect(loggedData.nested.publicField).toBe('safe');
    expect(loggedData.username).toBe('alice');
  });

  it('logs API request and response events', () => {
    dooLogger.apiRequest('POST', '/api/tasks/123/approvals/abc', 'Resolving approval gate', { approved: true });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('API POST'),
      expect.any(String),
      expect.any(String)
    );

    dooLogger.apiResponse('POST', '/api/tasks/123/approvals/abc', 200, { status: 'RESOLVED' });
    expect(groupCollapsedSpy).toHaveBeenCalledWith(
      expect.stringContaining('HTTP 200'),
      expect.any(String),
      expect.any(String)
    );
  });
});
