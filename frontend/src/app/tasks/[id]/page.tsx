'use client';

import React, { useState, useEffect } from 'react';
import { useRouter, useParams } from 'next/navigation';
import {
  Loader2,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Ban,
  ArrowLeft,
  Globe,
  Camera,
  Layers,
  Sparkles,
  Terminal,
  Shield,
  Clock,
  RefreshCw,
} from 'lucide-react';
import {
  api,
  Task,
  TaskStep,
  EvidenceArtifact,
  TaskResult,
  ApprovalRequest,
} from '@/lib/api';
import { useTaskStream, TaskStreamEvent } from '@/hooks/useTaskStream';
import StepTimeline from '@/components/StepTimeline';
import ApprovalModal from '@/components/ApprovalModal';
import ResultCard from '@/components/ResultCard';
import EvidenceGallery from '@/components/EvidenceGallery';

export default function TaskDetailPage() {
  const router = useRouter();
  const routeParams = useParams();
  const taskId = (routeParams?.id as string) || '';

  const [task, setTask] = useState<Task | null>(null);
  const [steps, setSteps] = useState<TaskStep[]>([]);
  const [artifacts, setArtifacts] = useState<EvidenceArtifact[]>([]);
  const [result, setResult] = useState<TaskResult | null>(null);
  const [pendingApproval, setPendingApproval] = useState<ApprovalRequest | null>(null);
  const [liveScreenshot, setLiveScreenshot] = useState<string | null>(null);
  const [activeUrl, setActiveUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionInProgress, setActionInProgress] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Initial load
  const loadTaskData = async () => {
    try {
      setLoading(true);
      const [taskData, stepsData, artifactsData, pendingApprovals] = await Promise.all([
        api.getTask(taskId),
        api.getSteps(taskId).catch(() => []),
        api.getEvidence(taskId).catch(() => []),
        api.getPendingApprovals(taskId).catch(() => []),
      ]);

      setTask(taskData);
      setSteps(stepsData);
      setArtifacts(artifactsData);

      if (pendingApprovals.length > 0) {
        setPendingApproval(pendingApprovals[0]);
      }

      // Latest screenshot from artifacts if available
      const lastScreenshot = [...artifactsData]
        .reverse()
        .find((a) => a.artifactType === 'SCREENSHOT' && (a.base64Data || a.content));
      if (lastScreenshot) {
        setLiveScreenshot(lastScreenshot.base64Data || lastScreenshot.content || null);
        if (lastScreenshot.sourceUrl) {
          setActiveUrl(lastScreenshot.sourceUrl);
        }
      }

      // If task is in terminal status, fetch result
      if (['COMPLETED', 'PARTIAL', 'FAILED'].includes(taskData.status)) {
        try {
          const res = await api.getResult(taskId);
          setResult(res);
        } catch (e) {
          // Result may not exist if failed early
        }
      }
    } catch (err: any) {
      console.error('Failed to load task:', err);
      setError(err?.message || 'Failed to load task details');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTaskData();
  }, [taskId]);

  // Poll for pending approvals while WAITING_FOR_USER so any tab (even opened late) picks up the prompt
  useEffect(() => {
    if (task?.status !== 'WAITING_FOR_USER' || pendingApproval) return;
    const interval = setInterval(async () => {
      try {
        const approvals = await api.getPendingApprovals(taskId);
        if (approvals.length > 0) {
          setPendingApproval(approvals[0]);
        }
      } catch { /* ignore */ }
    }, 3000);
    return () => clearInterval(interval);
  }, [task?.status, pendingApproval, taskId]);

  // Reactive SSE listener
  useTaskStream(taskId, (event: TaskStreamEvent) => {
    if (event.eventType === 'debug') {
      const data = event.data as any;
      const keyPool = data?.keyPool || '';
      console.log(`%c[DOO] ${event.message}`, 'color: #38bdf8; font-weight: bold', keyPool ? `| keys: ${keyPool}` : '');
      return;
    }

    // Workflow type update (published right after classification)
    if (event.eventType === 'task_updated' && event.data?.workflowType) {
      setTask((prev) => prev ? { ...prev, workflowType: event.data.workflowType as any } : null);
      return;
    }

    if (event.status) {
      setTask((prev) => (prev ? { ...prev, status: event.status as any } : null));
    }

    if (event.eventType === 'step_update' && event.data) {
      const rawData = event.data as any;
      const newStep: TaskStep = {
        id: rawData.id || String(Date.now()),
        taskId: taskId,
        stepIndex: rawData.stepIndex ?? rawData.stepNumber ?? 0,
        stepNumber: rawData.stepNumber ?? rawData.stepIndex ?? 0,
        actionType: rawData.actionType || 'UNKNOWN',
        target: rawData.target || rawData.actionArgs?.target || rawData.actionArgs?.url || null,
        value: rawData.value || rawData.actionArgs?.text || null,
        reasoning: rawData.reasoning || rawData.result || null,
        result: rawData.result || null,
        status: rawData.status || 'RUNNING',
        errorMessage: rawData.errorMessage || rawData.error || null,
        durationMs: rawData.durationMs,
        createdAt: rawData.createdAt || new Date().toISOString(),
        completedAt: rawData.completedAt || null,
      };

      setSteps((prev) => {
        const index = prev.findIndex(
          (s) => (s.stepNumber ?? s.stepIndex) === (newStep.stepNumber ?? newStep.stepIndex)
        );
        if (index >= 0) {
          const copy = [...prev];
          copy[index] = newStep;
          return copy;
        }
        return [...prev, newStep];
      });

      if (newStep.target && typeof newStep.target === 'string' && newStep.target.startsWith('http')) {
        setActiveUrl(newStep.target);
      }
    }

    if (event.eventType === 'screenshot' && event.data) {
      const data = event.data as any;
      if (typeof data.base64 === 'string') {
        setLiveScreenshot(data.base64);
      } else if (typeof data.content === 'string') {
        setLiveScreenshot(data.content);
      }
      if (typeof data.url === 'string') {
        setActiveUrl(data.url);
      }
      api.getEvidence(taskId).then(setArtifacts).catch(console.error);
    }

    if (event.eventType === 'live_view' && event.data) {
      const d = event.data as any;
      const normalized: ApprovalRequest = {
        id: d.id || `live-${taskId}`,
        taskId: taskId,
        prompt: event.message || 'Live view open for user handoff',
        proposedAction: 'LIVE_VIEW',
        actionDescription: event.message || 'Live view session active',
        reason: event.message || null,
        parameters: {
          type: 'live_view',
          ...d,
        },
        status: 'PENDING',
        userResponse: null,
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
        resolvedAt: null,
      };
      setPendingApproval(normalized);
    }

    if (event.eventType === 'approval_required' && event.data) {
      const d = event.data as any;
      const normalized: ApprovalRequest = {
        id: d.id || d.approvalId || '',
        taskId: taskId,
        prompt: d.prompt || event.message || '',
        proposedAction: d.proposedAction || null,
        actionDescription: d.proposedAction || d.prompt || event.message || null,
        reason: d.prompt || event.message || null,
        parameters: d.parameters || {},
        status: d.status || 'PENDING',
        userResponse: null,
        createdAt: d.createdAt || new Date().toISOString(),
        expiresAt: d.expiresAt || new Date(Date.now() + 30 * 60 * 1000).toISOString(),
        resolvedAt: null,
      };
      console.log('[GATE] login detected — waiting for credentials', normalized);
      setPendingApproval(normalized);
    }

    if (
      event.eventType === 'completed' ||
      event.status === 'COMPLETED' ||
      event.status === 'PARTIAL' ||
      event.status === 'FAILED'
    ) {
      api.getResult(taskId).then(setResult).catch(console.error);
      api.getEvidence(taskId).then(setArtifacts).catch(console.error);
      setPendingApproval(null);
    }
  }, loadTaskData);

  const handleCancel = async () => {
    if (!task || actionInProgress) return;
    try {
      setActionInProgress(true);
      await api.cancelTask(taskId);
      setTask((prev) => (prev ? { ...prev, status: 'CANCELLED' } : null));
    } catch (err: any) {
      alert(`Failed to cancel: ${err?.message}`);
    } finally {
      setActionInProgress(false);
    }
  };

  const handleApprove = async (reason?: string) => {
    if (!pendingApproval) return;
    try {
      setActionInProgress(true);
      await api.resolveApproval(taskId, pendingApproval.id, true, reason);
      setPendingApproval(null);
      setTask((prev) => (prev ? { ...prev, status: 'RUNNING' } : null));
    } catch (err: any) {
      alert(`Approval failed: ${err?.message}`);
    } finally {
      setActionInProgress(false);
    }
  };

  const handleReject = async (reason?: string) => {
    if (!pendingApproval) return;
    try {
      setActionInProgress(true);
      await api.resolveApproval(taskId, pendingApproval.id, false, reason);
      setPendingApproval(null);
    } catch (err: any) {
      alert(`Reject failed: ${err?.message}`);
    } finally {
      setActionInProgress(false);
    }
  };

  const getStatusBadge = (status?: string) => {
    switch (status) {
      case 'QUEUED':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-slate-800 text-slate-300 border border-slate-700">
            <Clock className="w-3.5 h-3.5" /> <span>QUEUED</span>
          </span>
        );
      case 'RUNNING':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-sky-500/20 text-sky-400 border border-sky-500/30 animate-pulse">
            <Loader2 className="w-3.5 h-3.5 animate-spin" /> <span>RUNNING</span>
          </span>
        );
      case 'WAITING_FOR_USER':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-amber-500/20 text-amber-300 border border-amber-500/30 animate-bounce">
            <AlertTriangle className="w-3.5 h-3.5" /> <span>WAITING FOR APPROVAL</span>
          </span>
        );
      case 'VERIFYING':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-purple-500/20 text-purple-300 border border-purple-500/30 animate-pulse">
            <Shield className="w-3.5 h-3.5" /> <span>VERIFYING OUTCOME</span>
          </span>
        );
      case 'COMPLETED':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
            <CheckCircle2 className="w-3.5 h-3.5" /> <span>COMPLETED</span>
          </span>
        );
      case 'PARTIAL':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-amber-500/20 text-amber-300 border border-amber-500/30">
            <AlertTriangle className="w-3.5 h-3.5" /> <span>PARTIAL</span>
          </span>
        );
      case 'FAILED':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-rose-500/20 text-rose-400 border border-rose-500/30">
            <XCircle className="w-3.5 h-3.5" /> <span>FAILED</span>
          </span>
        );
      case 'CANCELLED':
      case 'CANCELED':
        return (
          <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-bold bg-slate-800 text-slate-400 border border-slate-700">
            <Ban className="w-3.5 h-3.5" /> <span>CANCELLED</span>
          </span>
        );
      default:
        return (
          <span className="px-3 py-1 rounded-full text-xs font-mono bg-slate-800 text-slate-400">
            {status || 'UNKNOWN'}
          </span>
        );
    }
  };

  const isTerminal = ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'CANCELED'].includes(
    task?.status || ''
  );

  if (loading && !task) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-12 text-slate-400 space-y-3">
        <Loader2 className="w-8 h-8 animate-spin text-sky-400" />
        <p className="text-sm">Connecting to task runtime stream...</p>
      </div>
    );
  }

  if (error && !task) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-12 text-center max-w-md mx-auto space-y-4">
        <div className="p-3 rounded-full bg-rose-950/50 border border-rose-800/50 text-rose-400">
          <AlertTriangle className="w-8 h-8" />
        </div>
        <h2 className="text-lg font-bold text-white">Task Not Found</h2>
        <p className="text-xs text-slate-400">{error}</p>
        <button
          onClick={() => router.push('/')}
          className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-xs font-semibold rounded-xl text-slate-200 transition-colors"
        >
          Return to Composer
        </button>
      </div>
    );
  }

  return (
    <div className="flex-1 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full space-y-8">
      {/* Header bar */}
      <div className="glass-panel rounded-2xl p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div className="space-y-2">
          <div className="flex items-center space-x-3">
            <button
              onClick={() => router.push('/')}
              className="p-1.5 rounded-lg bg-slate-800/60 hover:bg-slate-800 text-slate-400 hover:text-slate-200 transition-colors"
            >
              <ArrowLeft className="w-4 h-4" />
            </button>
            <span className="font-mono text-xs text-slate-500">ID: {taskId}</span>
            {getStatusBadge(task?.status)}
            {task?.workflowType && (
              <span className="text-[11px] font-mono uppercase px-2 py-0.5 rounded bg-slate-800/60 text-slate-400 border border-slate-700/50">
                {task.workflowType}
              </span>
            )}
          </div>
          <h1 className="text-lg sm:text-xl font-bold text-white leading-snug">
            {task?.userRequest}
          </h1>
        </div>

        {/* Action Controls */}
        <div className="flex items-center space-x-3 shrink-0">
          <button
            onClick={loadTaskData}
            title="Refresh state"
            className="p-2.5 rounded-xl bg-slate-900 border border-slate-800 text-slate-400 hover:text-slate-200 hover:border-slate-700 transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
          </button>

          {!isTerminal && (
            <button
              onClick={handleCancel}
              disabled={actionInProgress}
              className="flex items-center space-x-2 px-4 py-2.5 rounded-xl text-xs font-bold text-rose-300 bg-rose-950/40 hover:bg-rose-900/50 border border-rose-800/40 transition-colors disabled:opacity-50"
            >
              <Ban className="w-3.5 h-3.5" />
              <span>Cancel Task</span>
            </button>
          )}
        </div>
      </div>

      {/* Main Two-Column View */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: Step Timeline (5 cols) */}
        <div className="lg:col-span-5 glass-panel rounded-2xl p-5 space-y-4">
          <div className="flex items-center justify-between border-b border-slate-800/80 pb-3">
            <div className="flex items-center space-x-2">
              <Terminal className="w-4 h-4 text-sky-400" />
              <h2 className="text-sm font-bold text-white">Execution Stream</h2>
            </div>
            <span className="text-xs font-mono text-slate-500">
              {steps.length} {steps.length === 1 ? 'step' : 'steps'}
            </span>
          </div>

          <div className="max-h-[600px] overflow-y-auto custom-scrollbar pr-1">
            <StepTimeline steps={steps} currentStepIndex={task?.currentStep} />
          </div>
        </div>

        {/* Right Column: Live Browser View (7 cols) */}
        <div className="lg:col-span-7 space-y-4">
          <div className="glass-panel rounded-2xl overflow-hidden border border-slate-800/90 shadow-xl">
            {/* Fake Browser Chrome */}
            <div className="bg-slate-900/90 px-4 py-3 border-b border-slate-800/80 flex items-center space-x-3">
              <div className="flex space-x-1.5">
                <span className="w-3 h-3 rounded-full bg-rose-500/80 inline-block" />
                <span className="w-3 h-3 rounded-full bg-amber-500/80 inline-block" />
                <span className="w-3 h-3 rounded-full bg-emerald-500/80 inline-block" />
              </div>

              {/* Address bar */}
              <div className="flex-1 flex items-center space-x-2 px-3 py-1 rounded-lg bg-slate-950/80 border border-slate-800 text-xs font-mono text-slate-300 truncate">
                <Globe className="w-3.5 h-3.5 text-slate-500 shrink-0" />
                <span className="truncate">{activeUrl || 'about:blank'}</span>
              </div>

              {/* Live indicator */}
              <div className="flex items-center space-x-1.5 text-[11px] font-mono text-slate-400">
                {!isTerminal ? (
                  <>
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                    <span className="text-emerald-400 font-semibold">LIVE</span>
                  </>
                ) : (
                  <span className="text-slate-500">OFFLINE</span>
                )}
              </div>
            </div>

            {/* Viewport display */}
            <div className="aspect-[16/10] bg-slate-950 flex items-center justify-center relative overflow-hidden">
              {liveScreenshot ? (
                <img
                  src={
                    liveScreenshot.startsWith('data:')
                      ? liveScreenshot
                      : `data:image/jpeg;base64,${liveScreenshot}`
                  }
                  alt="Live Browser Session"
                  className="w-full h-full object-cover object-top"
                />
              ) : (
                <div className="flex flex-col items-center justify-center p-12 text-slate-600 text-center space-y-2">
                  <Camera className="w-10 h-10 opacity-30 animate-pulse" />
                  <p className="text-xs font-mono">No live frame available</p>
                  <p className="text-[11px] text-slate-700">
                    Snapshots will stream as the agent drives the browser
                  </p>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Verified Result Section */}
      {result && (
        <div className="space-y-4 animate-fade-in">
          <ResultCard result={result} taskStatus={task?.status} />
        </div>
      )}

      {/* Failure reason when no result card */}
      {!result && task?.status === 'FAILED' && task.failureReason && (
        <div className="glass-panel rounded-2xl p-6 space-y-3 border border-rose-800/30">
          <div className="flex items-center space-x-2">
            <XCircle className="w-5 h-5 text-rose-400" />
            <h3 className="text-sm font-bold text-rose-300">Task Failed</h3>
          </div>
          <p className="text-xs text-slate-300 font-mono bg-slate-950/60 p-4 rounded-xl border border-slate-800/80 leading-relaxed">
            {task.failureReason}
          </p>
        </div>
      )}

      {/* Cancelled notice */}
      {task?.status === 'CANCELLED' && !result && (
        <div className="glass-panel rounded-2xl p-6 space-y-3 border border-slate-700/30">
          <div className="flex items-center space-x-2">
            <Ban className="w-5 h-5 text-slate-400" />
            <h3 className="text-sm font-bold text-slate-300">Task Cancelled</h3>
          </div>
          <p className="text-xs text-slate-400">
            This task was cancelled before completion. Any evidence collected up to that point is shown below.
          </p>
        </div>
      )}

      {/* Captured Evidence Section */}
      {artifacts.length > 0 && (
        <div className="glass-panel rounded-2xl p-6 space-y-4">
          <div className="flex items-center space-x-2 border-b border-slate-800/80 pb-3">
            <Layers className="w-4 h-4 text-indigo-400" />
            <h3 className="text-sm font-bold text-white">Full Evidence Artifacts</h3>
          </div>
          <EvidenceGallery artifacts={artifacts} />
        </div>
      )}

      {/* Interactive Approval Modal */}
      <ApprovalModal
        approval={pendingApproval}
        onApprove={handleApprove}
        onReject={handleReject}
        isSubmitting={actionInProgress}
      />
    </div>
  );
}
