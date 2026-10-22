'use client';

import { useEffect, useRef, useCallback } from 'react';
import { api } from '@/lib/api';
import { dooLogger } from '@/lib/logger';

export interface TaskStreamEvent {
  taskId: string;
  eventType: string;
  status: string | null;
  message: string;
  data: Record<string, unknown>;
  timestamp: string;
}

const TERMINAL_STATUSES = ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'];
const MAX_RECONNECTS = 8;
// Exponential backoff: 2s, 4s, 8s, 16s, capped at 30s
const backoffMs = (attempt: number) => Math.min(2000 * Math.pow(2, attempt - 1), 30000);

export function useTaskStream(
  taskId: string | null,
  onEvent: (event: TaskStreamEvent) => void,
  onReconnect?: () => void,
) {
  const esRef = useRef<EventSource | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const onReconnectRef = useRef(onReconnect);
  onReconnectRef.current = onReconnect;
  const reconnectCount = useRef(0);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const terminated = useRef(false);

  const cleanup = useCallback(() => {
    if (reconnectTimer.current) {
      clearTimeout(reconnectTimer.current);
      reconnectTimer.current = null;
    }
    if (esRef.current) {
      esRef.current.close();
      esRef.current = null;
    }
  }, []);

  const connect = useCallback(() => {
    if (!taskId || terminated.current) return;
    cleanup();

    const es = new EventSource(api.streamUrl(taskId));
    esRef.current = es;

    const handleEvent = (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data) as TaskStreamEvent;
        onEventRef.current(data);

        reconnectCount.current = 0;

        if (data.eventType === 'connected') {
          dooLogger.streamEvent('connected', `Real-time stream connected for task ${taskId}`, data);
        }

        if (
          data.eventType === 'completed' ||
          data.eventType === 'error' ||
          TERMINAL_STATUSES.includes(data.status || '')
        ) {
          terminated.current = true;
          cleanup();
        }
      } catch {
        // ignore malformed events
      }
    };

    const eventTypes = [
      'connected', 'status_change', 'step_update', 'task_updated',
      'screenshot', 'approval_required', 'waiting', 'error', 'completed', 'debug'
    ];
    eventTypes.forEach(type => es.addEventListener(type, handleEvent));

    es.onerror = () => {
      es.close();
      esRef.current = null;

      if (terminated.current) return;

      if (reconnectCount.current < MAX_RECONNECTS) {
        reconnectCount.current++;
        const delay = backoffMs(reconnectCount.current);
        dooLogger.streamEvent('reconnect', `SSE connection dropped (attempt ${reconnectCount.current}/${MAX_RECONNECTS}), reconnecting in ${delay}ms...`);
        reconnectTimer.current = setTimeout(() => {
          // Re-fetch latest state before reconnecting so the UI isn't stale
          onReconnectRef.current?.();
          connect();
        }, delay);
      } else {
        console.warn('[DOO] SSE max reconnects reached — stopping auto-reconnect');
      }
    };
  }, [taskId, cleanup]);

  useEffect(() => {
    terminated.current = false;
    reconnectCount.current = 0;
    connect();
    return cleanup;
  }, [connect, cleanup]);
}
