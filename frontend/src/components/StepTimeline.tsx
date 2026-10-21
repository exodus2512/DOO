'use client';

import React from 'react';
import {
  Navigation,
  MousePointer,
  Keyboard,
  Camera,
  FileText,
  Clock,
  CheckCircle,
  XCircle,
  Loader2,
  Scroll,
  ArrowLeft,
  Flag,
} from 'lucide-react';
import { TaskStep } from '@/lib/api';

interface StepTimelineProps {
  steps: TaskStep[];
  currentStepIndex?: number;
}

export default function StepTimeline({ steps, currentStepIndex }: StepTimelineProps) {
  const getActionIcon = (actionType: string) => {
    switch (actionType?.toUpperCase()) {
      case 'NAVIGATE':
        return <Navigation className="w-3.5 h-3.5 text-sky-400" />;
      case 'CLICK':
        return <MousePointer className="w-3.5 h-3.5 text-indigo-400" />;
      case 'FILL':
        return <Keyboard className="w-3.5 h-3.5 text-purple-400" />;
      case 'SCREENSHOT':
        return <Camera className="w-3.5 h-3.5 text-emerald-400" />;
      case 'EXTRACT_TEXT':
        return <FileText className="w-3.5 h-3.5 text-amber-400" />;
      case 'SCROLL':
        return <Scroll className="w-3.5 h-3.5 text-teal-400" />;
      case 'GO_BACK':
        return <ArrowLeft className="w-3.5 h-3.5 text-slate-400" />;
      case 'DONE':
        return <Flag className="w-3.5 h-3.5 text-green-400" />;
      default:
        return <Clock className="w-3.5 h-3.5 text-slate-400" />;
    }
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'SUCCESS':
        return (
          <span className="flex items-center text-[11px] font-medium text-emerald-400 bg-emerald-950/60 border border-emerald-800/40 px-2 py-0.5 rounded-full">
            <CheckCircle className="w-3 h-3 mr-1" /> Success
          </span>
        );
      case 'FAILED':
        return (
          <span className="flex items-center text-[11px] font-medium text-rose-400 bg-rose-950/60 border border-rose-800/40 px-2 py-0.5 rounded-full">
            <XCircle className="w-3 h-3 mr-1" /> Failed
          </span>
        );
      case 'RUNNING':
        return (
          <span className="flex items-center text-[11px] font-medium text-sky-400 bg-sky-950/60 border border-sky-800/40 px-2 py-0.5 rounded-full">
            <Loader2 className="w-3 h-3 mr-1 animate-spin" /> In Progress
          </span>
        );
      default:
        return (
          <span className="text-[11px] font-medium text-slate-400 bg-slate-800/60 px-2 py-0.5 rounded-full">
            {status}
          </span>
        );
    }
  };

  if (!steps || steps.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center p-8 text-center text-slate-500 border border-dashed border-slate-800 rounded-xl">
        <Clock className="w-8 h-8 mb-2 opacity-40 animate-pulse" />
        <p className="text-sm font-medium">Waiting for first execution step...</p>
        <p className="text-xs text-slate-600 mt-1">Actions taken by DOO will appear here in real-time</p>
      </div>
    );
  }

  return (
    <div className="relative pl-6 space-y-4 before:absolute before:left-2.5 before:top-3 before:bottom-3 before:w-0.5 before:bg-slate-800">
      {steps.map((step, idx) => {
        const isCurrent = currentStepIndex === step.stepNumber;
        return (
          <div
            key={step.id || idx}
            className={`relative group rounded-xl p-3.5 transition-all duration-200 ${
              isCurrent
                ? 'bg-sky-950/30 border border-sky-500/30 shadow-md shadow-sky-500/5'
                : 'bg-slate-900/60 hover:bg-slate-900 border border-slate-800/80'
            }`}
          >
            {/* Timeline bullet icon */}
            <div
              className={`absolute -left-[30px] top-4 w-5 h-5 rounded-full flex items-center justify-center border transition-all ${
                isCurrent
                  ? 'bg-sky-500 border-sky-300 shadow-md shadow-sky-500/50 scale-110'
                  : step.status === 'FAILED'
                  ? 'bg-rose-950 border-rose-500 text-rose-400'
                  : 'bg-slate-900 border-slate-700 text-slate-400'
              }`}
            >
              <span className="text-[10px] font-mono font-bold">{step.stepNumber}</span>
            </div>

            {/* Step header */}
            <div className="flex items-center justify-between gap-2 mb-1.5 flex-wrap">
              <div className="flex items-center space-x-2">
                <span className="p-1 rounded bg-slate-800/80 border border-slate-700/50">
                  {getActionIcon(step.actionType)}
                </span>
                <span className="font-mono text-xs font-bold text-slate-200 tracking-wider">
                  {step.actionType}
                </span>
              </div>
              <div className="flex items-center space-x-2">
                {step.durationMs ? (
                  <span className="text-[11px] font-mono text-slate-500">
                    {step.durationMs}ms
                  </span>
                ) : null}
                {getStatusBadge(step.status)}
              </div>
            </div>

            {/* Target element or URL */}
            {step.target ? (
              <div className="font-mono text-xs text-sky-300/90 break-all bg-slate-950/80 px-2.5 py-1.5 rounded border border-slate-800 mb-2">
                {step.target}
              </div>
            ) : null}

            {/* Value / Input payload if any */}
            {step.value ? (
              <div className="text-xs text-purple-300/80 bg-purple-950/20 px-2 py-1 rounded border border-purple-900/30 mb-2 font-mono">
                Input: &quot;{step.value}&quot;
              </div>
            ) : null}

            {/* AI Reasoning / Step note */}
            {step.reasoning ? (
              <p className="text-xs text-slate-400 leading-relaxed font-sans">
                {step.reasoning}
              </p>
            ) : null}

            {/* Error message */}
            {step.errorMessage ? (
              <div className="mt-2 text-xs font-mono text-rose-300 bg-rose-950/40 border border-rose-900/50 rounded p-2">
                Error: {step.errorMessage}
              </div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
