'use client';

import React, { useEffect, useRef, useState, useCallback } from 'react';
import {
  Monitor,
  CheckCircle2,
  XCircle,
  Loader2,
  Send,
  AlertCircle,
  Wifi,
  WifiOff,
  ExternalLink,
} from 'lucide-react';

export interface LiveViewPanelProps {
  wsUrl?: string;
  token?: string;
  taskId?: string;
  targetUrl?: string;
  onResume: () => void;
  onStop?: () => void;
  isSubmitting?: boolean;
}

type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

export default function LiveViewPanel({
  wsUrl,
  token,
  taskId,
  targetUrl,
  onResume,
  onStop,
  isSubmitting = false,
}: LiveViewPanelProps) {
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  const [lastFrame, setLastFrame] = useState<string | null>(null);
  const [frameDimensions, setFrameDimensions] = useState<{ width: number; height: number }>({ width: 1280, height: 800 });
  const [frameCount, setFrameCount] = useState<number>(0);
  const [textInput, setTextInput] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const imgRef = useRef<HTMLImageElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const lastMouseMoveRef = useRef<number>(0);

  // Compute effective WebSocket URL
  const effectiveWsUrl = React.useMemo(() => {
    let url = wsUrl;
    if (!url && taskId) {
      const base = process.env.NEXT_PUBLIC_WORKER_WS_URL || 'ws://localhost:3001';
      url = `${base}/session/${taskId}/live`;
    }
    if (url && token && !url.includes('token=')) {
      url += (url.includes('?') ? '&' : '?') + `token=${encodeURIComponent(token)}`;
    }
    return url || '';
  }, [wsUrl, token, taskId]);

  // Connect WebSocket
  useEffect(() => {
    if (!effectiveWsUrl) {
      setStatus('error');
      setErrorMessage('Missing live view URL or session token.');
      return;
    }

    setStatus('connecting');
    setErrorMessage(null);

    let socket: WebSocket;
    try {
      socket = new WebSocket(effectiveWsUrl);
    } catch (err: any) {
      setStatus('error');
      setErrorMessage(`Failed to initialize WebSocket: ${err?.message || err}`);
      return;
    }
    wsRef.current = socket;

    socket.onopen = () => {
      setStatus('connected');
    };

    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'frame' && msg.data) {
          setLastFrame(`data:image/jpeg;base64,${msg.data}`);
          if (msg.w && msg.h) {
            setFrameDimensions({ width: msg.w, height: msg.h });
          }
          setFrameCount((c) => c + 1);
          setStatus('connected');
        }
      } catch (err) {
        // Ignore JSON parse errors for non-frame messages
      }
    };

    socket.onerror = (err) => {
      console.warn('[LiveView] WebSocket error:', err);
      setStatus('error');
      setErrorMessage('WebSocket connection error');
    };

    socket.onclose = () => {
      setStatus('disconnected');
    };

    return () => {
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
      wsRef.current = null;
    };
  }, [effectiveWsUrl]);

  // Helper to safely send JSON to worker
  const sendWs = useCallback((payload: Record<string, unknown>) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      try {
        wsRef.current.send(JSON.stringify(payload));
      } catch (err) {
        console.debug('[LiveView] send error:', err);
      }
    }
  }, []);

  // Map mouse coordinate from element space to natural frame dimension
  const getMappedCoordinates = (e: React.MouseEvent<HTMLElement>) => {
    const el = imgRef.current || containerRef.current;
    if (!el) return { x: 0, y: 0 };
    const rect = el.getBoundingClientRect();
    const clientX = e.clientX - rect.left;
    const clientY = e.clientY - rect.top;

    const naturalW = frameDimensions.width || 1280;
    const naturalH = frameDimensions.height || 800;

    const scaleX = rect.width > 0 ? naturalW / rect.width : 1;
    const scaleY = rect.height > 0 ? naturalH / rect.height : 1;

    const x = Math.max(0, Math.min(8192, Math.round(clientX * scaleX)));
    const y = Math.max(0, Math.min(8192, Math.round(clientY * scaleY)));
    return { x, y };
  };

  const getModifiers = (e: React.MouseEvent | React.KeyboardEvent): number => {
    return (e.altKey ? 1 : 0) | (e.ctrlKey ? 2 : 0) | (e.metaKey ? 4 : 0) | (e.shiftKey ? 8 : 0);
  };

  // Mouse handlers
  const handleMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    // Focus the container for keyboard events
    containerRef.current?.focus();

    const { x, y } = getMappedCoordinates(e);
    const buttonMap: Record<number, 'left' | 'middle' | 'right'> = { 0: 'left', 1: 'middle', 2: 'right' };
    const button = buttonMap[e.button] || 'left';

    sendWs({
      type: 'mouse',
      action: 'mousePressed',
      button,
      x,
      y,
      modifiers: getModifiers(e),
    });
  };

  const handleMouseUp = (e: React.MouseEvent<HTMLDivElement>) => {
    const { x, y } = getMappedCoordinates(e);
    const buttonMap: Record<number, 'left' | 'middle' | 'right'> = { 0: 'left', 1: 'middle', 2: 'right' };
    const button = buttonMap[e.button] || 'left';

    sendWs({
      type: 'mouse',
      action: 'mouseReleased',
      button,
      x,
      y,
      modifiers: getModifiers(e),
    });
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const now = Date.now();
    if (now - lastMouseMoveRef.current < 50) return; // throttle 50ms
    lastMouseMoveRef.current = now;

    const { x, y } = getMappedCoordinates(e);
    sendWs({
      type: 'mouse',
      action: 'mouseMoved',
      button: 'none',
      x,
      y,
      modifiers: getModifiers(e),
    });
  };

  // Wheel / scroll handler
  const handleWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    e.preventDefault();
    const { x, y } = getMappedCoordinates(e);
    sendWs({
      type: 'wheel',
      x,
      y,
      deltaX: Math.round(e.deltaX),
      deltaY: Math.round(e.deltaY),
    });
  };

  // Key event handlers
  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    // Avoid interrupting browser refresh/dev keys
    if (e.key === 'F5' || e.key === 'F12') return;

    if (['Tab', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Space'].includes(e.key)) {
      e.preventDefault();
    }

    sendWs({
      type: 'key',
      action: 'keyDown',
      code: e.code,
      key: e.key,
      modifiers: getModifiers(e),
    });
  };

  const handleKeyUp = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'F5' || e.key === 'F12') return;

    sendWs({
      type: 'key',
      action: 'keyUp',
      code: e.code,
      key: e.key,
      modifiers: getModifiers(e),
    });
  };

  // Direct text submission
  const handleSendText = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!textInput.trim()) return;

    sendWs({
      type: 'text',
      text: textInput,
    });
    setTextInput('');
  };

  return (
    <div
      data-testid="live-view-panel"
      className="flex flex-col bg-slate-900/95 border border-slate-700/80 rounded-2xl shadow-2xl overflow-hidden w-full"
    >
      {/* Header bar */}
      <div className="flex items-center justify-between px-5 py-3.5 bg-slate-950/70 border-b border-slate-800">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <Monitor className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h3 className="text-sm font-bold text-white tracking-wide">Interactive Browser Session</h3>
              <span
                data-testid="live-view-status-badge"
                className={`inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                  status === 'connected'
                    ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                    : status === 'connecting'
                    ? 'bg-amber-500/10 text-amber-400 border border-amber-500/20'
                    : 'bg-rose-500/10 text-rose-400 border border-rose-500/20'
                }`}
              >
                {status === 'connected' ? (
                  <>
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
                    <span>Live ({frameCount} fps)</span>
                  </>
                ) : status === 'connecting' ? (
                  <>
                    <Loader2 className="w-3 h-3 animate-spin text-amber-400" />
                    <span>Connecting</span>
                  </>
                ) : (
                  <>
                    <WifiOff className="w-3 h-3 text-rose-400" />
                    <span>Disconnected</span>
                  </>
                )}
              </span>
            </div>
            {targetUrl && (
              <p className="text-xs text-slate-400 font-mono truncate max-w-lg mt-0.5">
                {targetUrl}
              </p>
            )}
          </div>
        </div>

        <div className="text-xs text-slate-400 text-right hidden sm:block">
          <div>Handoff mode active</div>
          <div className="text-slate-500">Solve block &amp; press Resume</div>
        </div>
      </div>

      {/* Main Screencast Area */}
      <div
        ref={containerRef}
        tabIndex={0}
        onKeyDown={handleKeyDown}
        onKeyUp={handleKeyUp}
        className="relative bg-slate-950 flex items-center justify-center min-h-[380px] max-h-[650px] overflow-auto select-none focus:outline-none focus:ring-1 focus:ring-blue-500/40"
        style={{ cursor: 'crosshair' }}
      >
        {lastFrame ? (
          <img
            ref={imgRef}
            src={lastFrame}
            alt="Live screencast frame"
            data-testid="live-screencast-img"
            onMouseDown={handleMouseDown}
            onMouseUp={handleMouseUp}
            onMouseMove={handleMouseMove}
            onWheel={handleWheel}
            className="w-full h-auto object-contain max-h-[620px] pointer-events-auto"
            draggable={false}
          />
        ) : (
          <div className="flex flex-col items-center justify-center p-12 text-slate-400 space-y-3">
            {status === 'connecting' ? (
              <>
                <Loader2 className="w-8 h-8 animate-spin text-blue-400" />
                <p className="text-sm font-medium">Connecting to browser screencast stream…</p>
                <p className="text-xs text-slate-500 font-mono">{effectiveWsUrl}</p>
              </>
            ) : status === 'error' ? (
              <>
                <AlertCircle className="w-8 h-8 text-rose-400" />
                <p className="text-sm font-medium text-rose-300">Unable to establish live screencast</p>
                <p className="text-xs text-slate-500">{errorMessage || 'Check worker connection'}</p>
              </>
            ) : (
              <>
                <Loader2 className="w-8 h-8 animate-spin text-slate-500" />
                <p className="text-sm">Waiting for first frame from browser…</p>
              </>
            )}
          </div>
        )}
      </div>

      {/* Direct text input relay */}
      <div className="p-3 bg-slate-950/80 border-t border-slate-800 flex items-center gap-2">
        <form onSubmit={handleSendText} className="flex-1 flex items-center gap-2">
          <input
            type="text"
            data-testid="live-view-text-input"
            value={textInput}
            onChange={(e) => setTextInput(e.target.value)}
            placeholder="Type or paste text to send to browser (press Enter or Send)..."
            className="flex-1 px-3 py-2 bg-slate-900 border border-slate-800 rounded-lg text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50"
          />
          <button
            type="submit"
            data-testid="live-view-send-btn"
            disabled={!textInput.trim() || status !== 'connected'}
            className="px-3 py-2 bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-slate-200 rounded-lg border border-slate-700 flex items-center space-x-1.5 transition-colors disabled:opacity-40"
          >
            <Send className="w-3.5 h-3.5" />
            <span>Send</span>
          </button>
        </form>
      </div>

      {/* Action Footer */}
      <div className="p-4 bg-slate-950/90 border-t border-slate-800/80 flex flex-col sm:flex-row items-center justify-between gap-3">
        <div className="text-xs text-slate-400 text-center sm:text-left">
          Click or scroll in the window above to interact directly with the page.
        </div>
        <div className="flex items-center space-x-3 w-full sm:w-auto justify-end">
          {onStop && (
            <button
              type="button"
              data-testid="live-view-stop-btn"
              onClick={onStop}
              disabled={isSubmitting}
              className="px-4 py-2 rounded-xl text-xs font-semibold text-rose-300 bg-rose-950/40 hover:bg-rose-900/50 border border-rose-800/40 transition-colors disabled:opacity-50"
            >
              Stop Task
            </button>
          )}
          <button
            type="button"
            data-testid="live-view-resume-btn"
            onClick={onResume}
            disabled={isSubmitting}
            className="flex items-center justify-center space-x-2 px-5 py-2.5 rounded-xl text-sm font-semibold text-slate-950 bg-gradient-to-r from-emerald-400 to-teal-400 hover:from-emerald-300 hover:to-teal-300 shadow-md shadow-emerald-500/20 transition-all disabled:opacity-50 font-bold"
          >
            <CheckCircle2 className="w-4 h-4" />
            <span>Resume DOO</span>
          </button>
        </div>
      </div>
    </div>
  );
}
