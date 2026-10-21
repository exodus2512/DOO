const API_BASE = process.env.NEXT_PUBLIC_API_URL || '';

export interface Task {
  id: string;
  userRequest: string;
  status: 'QUEUED' | 'RUNNING' | 'WAITING_FOR_USER' | 'VERIFYING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
  workflowType: 'WEB_RESEARCH' | 'PRODUCT_COMPARISON' | 'UI_INTERACTION' | 'UNKNOWN';
  plan: string | null;
  currentStep: number;
  successCriteria: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface EvidenceArtifact {
  id: string;
  taskId: string;
  artifactType: 'SCREENSHOT' | 'URL_VISITED' | 'TEXT_SNIPPET' | 'VERIFICATION_RESULT' | string;
  label?: string | null;
  content?: string | null;
  base64Data?: string | null;
  textContent?: string | null;
  sourceUrl?: string | null;
  description?: string | null;
  stepNumber?: number;
  createdAt: string;
}

export type Evidence = EvidenceArtifact;

export interface SourceRef {
  url: string;
  title: string;
}

export interface TaskResult {
  id: string;
  taskId: string;
  summary: string | null;
  verificationPassed: boolean | null;
  verificationNotes: string | null;
  sources: SourceRef[] | string;
  confidenceScore?: number | null;
  createdAt: string;
}

export interface TaskStep {
  id: string;
  taskId: string;
  stepIndex: number;
  stepNumber?: number;
  actionType: string;
  actionArgs?: Record<string, any>;
  target?: string | null;
  value?: string | null;
  reasoning?: string | null;
  result: string | null;
  status: string;
  error?: string | null;
  errorMessage?: string | null;
  durationMs?: number;
  createdAt: string;
  completedAt: string | null;
}

export interface ApprovalRequest {
  id: string;
  taskId: string;
  prompt: string;
  proposedAction: string | null;
  actionDescription?: string | null;
  reason?: string | null;
  parameters: Record<string, unknown>;
  status: string;
  userResponse: string | null;
  createdAt: string;
  expiresAt: string;
  resolvedAt: string | null;
}

export interface PagedResponse<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const url = `${API_BASE}${path}`;
  console.log(`[DOO Client] Fetching ${url}`);
  const res = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      ...(options?.headers || {}),
    },
  });
  if (!res.ok) {
    const errorText = await res.text();
    console.error(`[DOO Client] HTTP ${res.status} from ${url}:`, errorText);
    let parsed: any = null;
    try {
      parsed = JSON.parse(errorText);
    } catch {}
    const message = parsed?.message || parsed?.error || errorText || `HTTP ${res.status}`;
    throw new Error(message);
  }
  return res.json();
}

export const api = {
  createTask: (payload: string | { userRequest: string }, idempotencyKey?: string) => {
    const requestText = typeof payload === 'string' ? payload : payload.userRequest;
    return request<Task>('/api/tasks', {
      method: 'POST',
      body: JSON.stringify({ userRequest: requestText }),
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
    });
  },

  getTask: (id: string) => request<Task>(`/api/tasks/${id}`),

  listTasks: (page = 0, size = 20) =>
    request<PagedResponse<Task>>(`/api/tasks?page=${page}&size=${size}`),

  cancelTask: (id: string) =>
    request<Task>(`/api/tasks/${id}/cancel`, { method: 'POST' }),

  getEvidence: async (id: string): Promise<EvidenceArtifact[]> => {
    const list = await request<any[]>(`/api/tasks/${id}/evidence`);
    return list.map((e) => ({
      ...e,
      base64Data: e.artifactType === 'SCREENSHOT' ? e.content : null,
      textContent: e.artifactType !== 'SCREENSHOT' ? e.content : null,
      description: e.label || (e.artifactType === 'SCREENSHOT' ? 'Browser Screenshot' : 'Extracted Content'),
    }));
  },

  getResult: async (id: string): Promise<TaskResult | null> => {
    try {
      const res = await fetch(`${API_BASE}/api/tasks/${id}/result`, {
        headers: { Accept: 'application/json' },
      });
      if (res.status === 204 || res.status === 404) {
        return null;
      }
      if (!res.ok) {
        return null;
      }
      return await res.json();
    } catch {
      return null;
    }
  },

  getSteps: async (id: string): Promise<TaskStep[]> => {
    const list = await request<any[]>(`/api/tasks/${id}/steps`);
    return list.map((s) => ({
      ...s,
      stepNumber: s.stepIndex,
      target: s.actionArgs?.target || s.actionArgs?.url || s.actionArgs?.selector || null,
      value: s.actionArgs?.text || s.actionArgs?.value || null,
      reasoning: s.actionArgs?.reasoning || s.result || null,
      errorMessage: s.error,
    }));
  },

  getPendingApprovals: async (id: string): Promise<ApprovalRequest[]> => {
    const list = await request<any[]>(`/api/tasks/${id}/approvals/pending`);
    return list.map((a) => ({
      ...a,
      actionDescription: a.proposedAction || a.prompt,
      reason: a.prompt,
    }));
  },

  resolveApproval: (taskId: string, approvalId: string, approved: boolean, userResponse?: string) =>
    request<ApprovalRequest>(`/api/tasks/${taskId}/approvals/${approvalId}`, {
      method: 'POST',
      body: JSON.stringify({ approved, userResponse }),
    }),

  respondToApproval: (taskId: string, approvalId: string, approved: boolean, userResponse?: string) =>
    request<ApprovalRequest>(`/api/tasks/${taskId}/approvals/${approvalId}`, {
      method: 'POST',
      body: JSON.stringify({ approved, userResponse }),
    }),

  streamUrl: (id: string) => {
    return `${API_BASE}/api/tasks/${id}/stream`;
  },
};
