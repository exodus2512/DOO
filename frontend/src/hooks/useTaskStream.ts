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

export function useTaskStream(
  taskId: string | null,
  onEvent: (event: TaskStreamEvent) => void
) {
  const esRef = useRef<EventSource | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  const connect = useCallback(() => {
    if (!taskId) return;
    if (esRef.current) {
      esRef.current.close();
    }

    const es = new EventSource(api.streamUrl(taskId));
    esRef.current = es;

    const handleEvent = (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data) as TaskStreamEvent;
        onEventRef.current(data);
        if (
          data.eventType === 'completed' ||
          data.eventType === 'error' ||
          ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(data.status || '')
        ) {
          es.close();
          esRef.current = null;
        }
      } catch {
        // ignore malformed events
      }
    };

    // Listen for all named events
    const eventTypes = [
      'connected', 'status_change', 'step_update',
      'screenshot', 'approval_required', 'error', 'completed'
    ];
    eventTypes.forEach(type => es.addEventListener(type, handleEvent));

    es.onerror = () => {
      // Clean up on error or server completion
      es.close();
      esRef.current = null;
    };

    return () => {
      es.close();
      esRef.current = null;
    };
  }, [taskId]);

  useEffect(() => {
    const cleanup = connect();
    return () => {
      cleanup?.();
      esRef.current?.close();
    };
  }, [connect]);
}
