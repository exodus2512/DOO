'use client';

import { useEffect, useRef, useCallback } from 'react';
import { api } from '@/lib/api';

export interface TaskStreamEvent {
  taskId: string;
  eventType: string;
  status: string | null;
  message: string;
  data: Record<string, unknown>;
  timestamp: string;
}

const TERMINAL_STATUSES = ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'];
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECTS = 5;

export function useTaskStream(
  taskId: string | null,
  onEvent: (event: TaskStreamEvent) => void
) {
  const esRef = useRef<EventSource | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
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
      'connected', 'status_change', 'step_update',
      'screenshot', 'approval_required', 'error', 'completed', 'debug'
    ];
    eventTypes.forEach(type => es.addEventListener(type, handleEvent));

    es.onerror = () => {
      es.close();
      esRef.current = null;

      if (terminated.current) return;

      if (reconnectCount.current < MAX_RECONNECTS) {
        reconnectCount.current++;
        console.log(`[DOO] SSE disconnected, reconnecting (${reconnectCount.current}/${MAX_RECONNECTS})...`);
        reconnectTimer.current = setTimeout(connect, RECONNECT_DELAY_MS);
      } else {
        console.warn('[DOO] SSE max reconnects reached, falling back to polling');
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
