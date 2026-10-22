/**
 * Tests for LiveViewPanel and Live View Approval Integration.
 */
import React from 'react';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import LiveViewPanel from '../components/LiveViewPanel';
import ApprovalModal from '../components/ApprovalModal';
import { ApprovalRequest } from '../lib/api';

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  url: string;
  readyState: number = 0; // CONNECTING
  static OPEN = 1;
  static CONNECTING = 0;
  static CLOSING = 2;
  static CLOSED = 3;

  onopen: ((e: any) => void) | null = null;
  onmessage: ((e: any) => void) | null = null;
  onerror: ((e: any) => void) | null = null;
  onclose: ((e: any) => void) | null = null;
  sentMessages: string[] = [];

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
    // Open immediately on next tick
    setTimeout(() => {
      this.readyState = MockWebSocket.OPEN;
      this.onopen?.({ type: 'open' });
    }, 0);
  }

  send(data: string) {
    this.sentMessages.push(data);
  }

  close() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.({ type: 'close' });
  }

  receiveMessage(msg: Record<string, any>) {
    this.onmessage?.({ data: JSON.stringify(msg) });
  }
}

const originalWebSocket = global.WebSocket;

beforeEach(() => {
  MockWebSocket.instances = [];
  (global as any).WebSocket = MockWebSocket;
});

afterEach(() => {
  (global as any).WebSocket = originalWebSocket;
});

describe('LiveViewPanel Component', () => {
  it('connects to the WebSocket and updates status on connection', async () => {
    const onResume = vi.fn();
    render(
      <LiveViewPanel
        wsUrl="ws://localhost:3001/session/task-1/live"
        token="tok123"
        targetUrl="https://protected.example.com"
        onResume={onResume}
      />
    );

    expect(screen.getByTestId('live-view-panel')).toBeInTheDocument();
    expect(screen.getByText('https://protected.example.com')).toBeInTheDocument();

    // Verify WebSocket created with token
    expect(MockWebSocket.instances.length).toBe(1);
    expect(MockWebSocket.instances[0].url).toContain('ws://localhost:3001/session/task-1/live?token=tok123');

    // Wait for connection to open
    await waitFor(() => {
      expect(screen.getByTestId('live-view-status-badge')).toHaveTextContent(/Live/i);
    });
  });

  it('renders screencast frames and relays mouse clicks, wheel, keys, and text', async () => {
    const onResume = vi.fn();
    const onStop = vi.fn();

    render(
      <LiveViewPanel
        wsUrl="ws://localhost:3001/session/task-1/live"
        token="tok123"
        onResume={onResume}
        onStop={onStop}
      />
    );

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBe(1);
      expect(MockWebSocket.instances[0].readyState).toBe(MockWebSocket.OPEN);
    });
    const ws = MockWebSocket.instances[0];

    // Simulate incoming frame from CDP
    act(() => {
      ws.receiveMessage({
        type: 'frame',
        data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
        w: 1280,
        h: 800,
        ts: Date.now(),
      });
    });

    const img = screen.getByTestId('live-screencast-img');
    expect(img).toBeInTheDocument();

    // Mock bounding rect for coordinate calculation
    vi.spyOn(img, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      right: 640,
      bottom: 400,
      width: 640,
      height: 400,
      x: 0,
      y: 0,
      toJSON: () => {},
    });

    // 1. Mouse press & release (simulating click at 320, 200 which scales 2x to 640, 400)
    fireEvent.mouseDown(img, { clientX: 320, clientY: 200, button: 0 });
    expect(ws.sentMessages.length).toBeGreaterThan(0);
    const pressMsg = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]);
    expect(pressMsg.type).toBe('mouse');
    expect(pressMsg.action).toBe('mousePressed');
    expect(pressMsg.button).toBe('left');
    expect(pressMsg.x).toBe(640);
    expect(pressMsg.y).toBe(400);

    fireEvent.mouseUp(img, { clientX: 320, clientY: 200, button: 0 });
    const releaseMsg = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]);
    expect(releaseMsg.type).toBe('mouse');
    expect(releaseMsg.action).toBe('mouseReleased');
    expect(releaseMsg.x).toBe(640);
    expect(releaseMsg.y).toBe(400);

    // 2. Mouse Wheel
    fireEvent.wheel(img, { clientX: 320, clientY: 200, deltaX: 0, deltaY: 100 });
    const wheelMsg = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]);
    expect(wheelMsg.type).toBe('wheel');
    expect(wheelMsg.deltaY).toBe(100);

    // 3. Keyboard input
    const container = screen.getByTestId('live-view-panel').querySelector('[tabindex="0"]')!;
    fireEvent.keyDown(container, { key: 'a', code: 'KeyA' });
    const keyMsg = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]);
    expect(keyMsg.type).toBe('key');
    expect(keyMsg.action).toBe('keyDown');
    expect(keyMsg.code).toBe('KeyA');

    // 4. Direct text input relay
    const textInput = screen.getByTestId('live-view-text-input');
    const sendBtn = screen.getByTestId('live-view-send-btn');
    fireEvent.change(textInput, { target: { value: 'Verification input text' } });
    fireEvent.click(sendBtn);

    const textMsg = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]);
    expect(textMsg.type).toBe('text');
    expect(textMsg.text).toBe('Verification input text');

    // 5. Resume DOO button
    const resumeBtn = screen.getByTestId('live-view-resume-btn');
    fireEvent.click(resumeBtn);
    expect(onResume).toHaveBeenCalledTimes(1);

    // 6. Stop button
    const stopBtn = screen.getByTestId('live-view-stop-btn');
    fireEvent.click(stopBtn);
    expect(onStop).toHaveBeenCalledTimes(1);
  });
});

describe('ApprovalModal — Live View integration', () => {
  it('renders LiveViewPanel when approval parameter type is live_view', async () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();

    const approval: ApprovalRequest = {
      id: 'app-live-1',
      taskId: 'task-live-1',
      prompt: 'Live view open for user handoff',
      proposedAction: 'LIVE_VIEW',
      parameters: {
        type: 'live_view',
        wsUrl: 'ws://localhost:3001/session/task-live-1/live',
        token: 'test-token-live',
        url: 'https://cloudflare-blocked-site.com',
      },
      status: 'PENDING',
      userResponse: null,
      createdAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 60000).toISOString(),
      resolvedAt: null,
    };

    render(
      <ApprovalModal
        approval={approval}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    // Live view panel should be displayed
    expect(screen.getByTestId('live-view-panel')).toBeInTheDocument();
    expect(screen.getByText('https://cloudflare-blocked-site.com')).toBeInTheDocument();

    // Clicking Resume DOO calls onApprove('resumed')
    const resumeBtn = screen.getByTestId('live-view-resume-btn');
    fireEvent.click(resumeBtn);
    expect(onApprove).toHaveBeenCalledWith('resumed');

    // Clicking Stop Task calls onReject('stop')
    const stopBtn = screen.getByTestId('live-view-stop-btn');
    fireEvent.click(stopBtn);
    expect(onReject).toHaveBeenCalledWith('stop');
  });
});
