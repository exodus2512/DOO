'use client';

import React from 'react';
import {
  CheckCircle2,
  AlertTriangle,
  XCircle,
  ExternalLink,
  ShieldCheck,
  Sparkles,
  Layers,
} from 'lucide-react';
import { TaskResult, SourceRef } from '@/lib/api';

interface ResultCardProps {
  result: TaskResult | null;
  taskStatus?: string;
}

export default function ResultCard({ result, taskStatus }: ResultCardProps) {
  if (!result) return null;

  const isVerified = result.verificationPassed === true;
  const isFailed = result.verificationPassed === false || taskStatus === 'FAILED';
  const isPartial = !isVerified && !isFailed && taskStatus === 'PARTIAL';

  let sourcesList: SourceRef[] = [];
  if (Array.isArray(result.sources)) {
    sourcesList = result.sources;
  } else if (typeof result.sources === 'string') {
    try {
      sourcesList = JSON.parse(result.sources);
    } catch {
      sourcesList = [{ url: result.sources, title: result.sources }];
    }
  }

  return (
    <div className="glass-panel-glow rounded-2xl p-6 sm:p-8 space-y-6">
      {/* Verification Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800/80 pb-5">
        <div className="flex items-center space-x-3">
          <div className="p-2.5 rounded-xl bg-gradient-to-tr from-sky-500 to-indigo-500 text-white shadow-md shadow-sky-500/20">
            <Sparkles className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-xl font-extrabold text-white tracking-tight">
              Verified Task Outcome
            </h2>
            <p className="text-xs text-slate-400">Synthesized result with proof of completion</p>
          </div>
        </div>

        {/* Verification Status Badge */}
        <div className="flex items-center space-x-2">
          {isVerified && (
            <div className="flex items-center space-x-1.5 px-3 py-1.5 rounded-full bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-xs font-bold tracking-wide">
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
              <span>OUTCOME VERIFIED</span>
            </div>
          )}
          {isPartial && (
            <div className="flex items-center space-x-1.5 px-3 py-1.5 rounded-full bg-amber-500/15 border border-amber-500/30 text-amber-300 text-xs font-bold tracking-wide">
              <AlertTriangle className="w-4 h-4 text-amber-400" />
              <span>PARTIAL OUTCOME</span>
            </div>
          )}
          {isFailed && (
            <div className="flex items-center space-x-1.5 px-3 py-1.5 rounded-full bg-rose-500/15 border border-rose-500/30 text-rose-300 text-xs font-bold tracking-wide">
              <XCircle className="w-4 h-4 text-rose-400" />
              <span>VERIFICATION FAILED</span>
            </div>
          )}
          {result.confidenceScore != null && (
            <div className="px-2.5 py-1.5 rounded-lg bg-slate-900 border border-slate-800 text-xs font-mono text-slate-300">
              Score: <span className="text-sky-400 font-bold">{Math.round(result.confidenceScore * 100)}%</span>
            </div>
          )}
          {result.deliverableType && (
            <div className="px-2.5 py-1.5 rounded-lg bg-slate-900 border border-indigo-800/40 text-xs font-mono text-indigo-300">
              {result.deliverableType.replace(/_/g, ' ')}
            </div>
          )}
        </div>
      </div>

      {/* Main Synthesized Answer */}
      <div className="space-y-2">
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400">
          Synthesized Summary
        </h3>
        <div className="prose prose-invert max-w-none text-slate-200 text-sm leading-relaxed bg-slate-950/60 p-5 rounded-xl border border-slate-800/80 whitespace-pre-wrap">
          {result.summary || 'Task completed successfully.'}
        </div>
      </div>

      {/* Verification Notes */}
      {result.verificationNotes && (
        <div className="p-4 rounded-xl bg-slate-900/60 border border-slate-800 space-y-1.5">
          <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider flex items-center gap-1.5">
            <CheckCircle2 className="w-3.5 h-3.5 text-sky-400" /> Verification Details
          </span>
          <p className="text-xs text-slate-300 font-mono leading-normal">
            {result.verificationNotes}
          </p>
        </div>
      )}

      {/* Sources & Citations */}
      {sourcesList.length > 0 && (
        <div className="space-y-2">
          <span className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
            <Layers className="w-3.5 h-3.5 text-indigo-400" /> Source Evidence Links
          </span>
          <div className="flex flex-wrap gap-2">
            {sourcesList.map((src, i) => (
              <a
                key={i}
                href={src.url}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center space-x-1.5 text-xs text-sky-400 hover:text-sky-300 bg-sky-950/40 hover:bg-sky-900/50 border border-sky-800/40 px-3 py-1.5 rounded-lg transition-colors font-mono max-w-md truncate"
              >
                <ExternalLink className="w-3 h-3 shrink-0" />
                <span className="truncate">{src.title || src.url}</span>
              </a>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
