'use client';

import React, { useState } from 'react';
import { AlertTriangle, CheckCircle2, XCircle, ShieldAlert, Clock } from 'lucide-react';
import { ApprovalRequest } from '@/lib/api';

interface ApprovalModalProps {
  approval: ApprovalRequest | null;
  onApprove: (reason?: string) => Promise<void>;
  onReject: (reason?: string) => Promise<void>;
  isSubmitting?: boolean;
}

export default function ApprovalModal({
  approval,
  onApprove,
  onReject,
  isSubmitting = false,
}: ApprovalModalProps) {
  const [comment, setComment] = useState('');

  if (!approval || approval.status !== 'PENDING') {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className="w-full max-w-lg rounded-2xl bg-slate-900 border border-amber-500/40 shadow-2xl shadow-amber-500/10 overflow-hidden">
        {/* Header */}
        <div className="p-6 bg-gradient-to-r from-amber-950/60 via-slate-900 to-slate-900 border-b border-amber-500/20 flex items-start space-x-4">
          <div className="p-3 rounded-xl bg-amber-500/15 border border-amber-500/30 text-amber-400">
            <ShieldAlert className="w-6 h-6" />
          </div>
          <div className="flex-1">
            <div className="flex items-center space-x-2">
              <span className="text-xs uppercase font-bold tracking-wider px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">
                Action Approval Required
              </span>
            </div>
            <h3 className="text-lg font-bold text-white mt-1">
              DOO paused for user confirmation
            </h3>
            <p className="text-xs text-slate-400 mt-1">
              The agent is about to execute a protected or sensitive operation.
            </p>
          </div>
        </div>

        {/* Content */}
        <div className="p-6 space-y-4">
          <div className="rounded-xl bg-slate-950/80 p-4 border border-slate-800">
            <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1">
              Proposed Action / Reason
            </span>
            <p className="text-sm font-mono text-amber-200 leading-relaxed whitespace-pre-wrap">
              {approval.actionDescription || approval.reason || 'Approval requested before continuing step'}
            </p>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1.5">
              Optional Note / Decision Rationale
            </label>
            <input
              type="text"
              placeholder="e.g. Approved with condition, or reason for decline"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-amber-500/50"
            />
          </div>

          <div className="flex items-center space-x-2 text-xs text-slate-400 bg-slate-800/40 p-2.5 rounded-lg">
            <Clock className="w-4 h-4 text-slate-400 shrink-0" />
            <span>Task execution is suspended until you submit an action.</span>
          </div>
        </div>

        {/* Footer actions */}
        <div className="p-5 bg-slate-950/60 border-t border-slate-800/80 flex items-center justify-end space-x-3">
          <button
            type="button"
            onClick={() => onReject(comment)}
            disabled={isSubmitting}
            className="flex items-center space-x-2 px-4 py-2 rounded-xl text-sm font-semibold text-rose-300 bg-rose-950/50 hover:bg-rose-900/60 border border-rose-800/40 transition-colors disabled:opacity-50"
          >
            <XCircle className="w-4 h-4" />
            <span>Reject Action</span>
          </button>
          <button
            type="button"
            onClick={() => onApprove(comment)}
            disabled={isSubmitting}
            className="flex items-center space-x-2 px-5 py-2 rounded-xl text-sm font-semibold text-slate-950 bg-gradient-to-r from-amber-400 to-amber-500 hover:from-amber-300 hover:to-amber-400 shadow-md shadow-amber-500/20 transition-all disabled:opacity-50"
          >
            <CheckCircle2 className="w-4 h-4" />
            <span>Approve & Continue</span>
          </button>
        </div>
      </div>
    </div>
  );
}
