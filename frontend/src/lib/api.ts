import { dooLogger } from './logger';

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
  deliverableType?: string | null;
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
  const method = options?.method || 'GET';
  let bodyData: unknown = undefined;
  if (options?.body && typeof options.body === 'string') {
    try {
      bodyData = JSON.parse(options.body);
    } catch {
      bodyData = options.body;
    }
  }

  dooLogger.apiRequest(method, path, `Sending HTTP ${method} request to backend`, bodyData);

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
    dooLogger.apiResponse(method, path, res.status, errorText);
    console.error(`[DOO Client] HTTP ${res.status} from ${url}:`, errorText);
    let parsed: any = null;
    try {
      parsed = JSON.parse(errorText);
    } catch {}
    const message = parsed?.message || parsed?.error || errorText || `HTTP ${res.status}`;
    throw new Error(message);
  }
  const json = await res.json();
  dooLogger.apiResponse(method, path, res.status, json);
  return json;
}

export const api = {
  createTask: (payload: string | { userRequest: string }, idempotencyKey?: string) => {
    const requestText = typeof payload === 'string' ? payload : payload.userRequest;
    dooLogger.userEvent({
      event: 'Submit Task Request',
      process: 'Submitting user prompt to initialize autonomous browser agent execution',
      data: { userRequest: requestText, idempotencyKey: idempotencyKey || null },
      nextStep: 'Backend will classify workflow (Stage 1/5) and generate step-by-step browser plan (Stage 2/5)',
    });
    return request<Task>('/api/tasks', {
      method: 'POST',
      body: JSON.stringify({ userRequest: requestText }),
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
    });
  },

  getTask: (id: string) => request<Task>(`/api/tasks/${id}`),

  listTasks: (page = 0, size = 20) =>
    request<PagedResponse<Task>>(`/api/tasks?page=${page}&size=${size}`),

  cancelTask: (id: string) => {
    dooLogger.userEvent({
      event: 'Cancel Task Execution',
      process: 'Terminating remote browser session and marking task as CANCELLED',
      data: { taskId: id },
      nextStep: 'Task execution loop stops immediately, releasing browser resources',
    });
    return request<Task>(`/api/tasks/${id}/cancel`, { method: 'POST' });
  },

  getEvidence: async (id: string): Promise<EvidenceArtifact[]> => {
    const list = await request<any[]>(`/api/tasks/${id}/evidence`);
    const isScreenshot = (t: string) => t === 'SCREENSHOT' || t === 'STEP_SCREENSHOT';
    return list.map((e) => ({
      ...e,
      base64Data: isScreenshot(e.artifactType) ? e.content : null,
      textContent: !isScreenshot(e.artifactType) ? e.content : null,
      description: e.label || (isScreenshot(e.artifactType) ? 'Browser Screenshot' : 'Extracted Content'),
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

  resolveApproval: (taskId: string, approvalId: string, approved: boolean, userResponse?: string) => {
    let parsedResponse: unknown = userResponse;
    if (userResponse) {
      try { parsedResponse = JSON.parse(userResponse); } catch {}
    }
    dooLogger.userEvent({
      event: approved ? 'Approve Gate / Provide Input' : 'Reject Gate / Cancel Approval',
      process: approved
        ? 'Submitting user authorization or credentials to unpause agent execution loop'
        : 'User declined approval request; notifying backend execution engine',
      data: {
        taskId,
        approvalId,
        approved,
        userResponse: parsedResponse,
      },
      nextStep: approved
        ? 'Execution loop resumes (Stage 3/5: Action Selection & Execution)'
        : 'Backend aborts operation or handles rejection',
    });
    return request<ApprovalRequest>(`/api/tasks/${taskId}/approvals/${approvalId}`, {
      method: 'POST',
      body: JSON.stringify({ approved, userResponse }),
    });
  },

  respondToApproval: (taskId: string, approvalId: string, approved: boolean, userResponse?: string) =>
    api.resolveApproval(taskId, approvalId, approved, userResponse),

  streamUrl: (id: string) => {
    return `${API_BASE}/api/tasks/${id}/stream`;
  },

  // ─── Credential Vault (passwords never returned) ──────────────────────────
  vault: {
    listDomains: () =>
      request<{ domain: string; username: string; createdAt: string }[]>('/api/vault'),

    upsert: (domain: string, username: string, password: string) => {
      dooLogger.userEvent({
        event: 'Vault: Save Credentials',
        process: `Saving credentials for domain ${domain} into encrypted vault`,
        data: { domain, username, password: '•••••••• (protected)' },
        nextStep: 'Credentials will be automatically available for future tasks on this domain',
      });
      return request<{ status: string; domain: string }>('/api/vault', {
        method: 'POST',
        body: JSON.stringify({ domain, username, password }),
      });
    },

    delete: (domain: string) => {
      dooLogger.userEvent({
        event: 'Vault: Delete Credentials',
        process: `Deleting credentials for domain ${domain} from vault`,
        data: { domain },
        nextStep: 'Credentials removed from vault store',
      });
      return request<{ deleted: boolean; domain: string }>(`/api/vault/${encodeURIComponent(domain)}`, {
        method: 'DELETE',
      });
    },
  },
};
