'use client';

import React, { useState } from 'react';
import { CheckCircle2, XCircle, ShieldAlert, Clock, KeyRound, Eye, EyeOff, MessageSquareText } from 'lucide-react';
import { ApprovalRequest } from '@/lib/api';

interface ApprovalModalProps {
  approval: ApprovalRequest | null;
  onApprove: (reason?: string) => Promise<void>;
  onReject: (reason?: string) => Promise<void>;
  isSubmitting?: boolean;
}

interface AskField {
  name: string;
  label: string;
  type: string;
  placeholder: string;
  required: boolean;
  options?: string[];
}

function isCredentialRequest(approval: ApprovalRequest): boolean {
  return approval.parameters?.type === 'credential_request'
    || approval.proposedAction === 'CREDENTIAL_REQUEST';
}

function isFormInputRequest(approval: ApprovalRequest): boolean {
  return approval.parameters?.type === 'form_input_request'
    || approval.proposedAction === 'ASK_USER';
}

function parseFields(approval: ApprovalRequest): AskField[] {
  const raw = approval.parameters?.fields;
  if (!Array.isArray(raw)) return [];
  return raw.map((f: any) => ({
    name: typeof f === 'string' ? f : (f.name || f.field || 'field'),
    label: typeof f === 'string' ? f : (f.label || f.name || f.field || 'Field'),
    type: (typeof f === 'object' && f.type) || 'text',
    placeholder: (typeof f === 'object' && f.placeholder) || '',
    required: typeof f === 'object' ? f.required !== false : true,
    options: typeof f === 'object' && Array.isArray(f.options) ? f.options : undefined,
  }));
}

export default function ApprovalModal({
  approval,
  onApprove,
  onReject,
  isSubmitting = false,
}: ApprovalModalProps) {
  const [comment, setComment] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [saveToVault, setSaveToVault] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [otpCode, setOtpCode] = useState('');
  const [timeLeft, setTimeLeft] = useState<number | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string>>({});

  React.useEffect(() => {
    if (!approval || approval.status !== 'PENDING') { setTimeLeft(null); return; }
    const update = () => {
      const exp = approval.expiresAt ? new Date(approval.expiresAt).getTime() : Date.now() + 30 * 60 * 1000;
      const remaining = Math.max(0, Math.floor((exp - Date.now()) / 1000));
      setTimeLeft(remaining);
    };
    update();
    const id = setInterval(update, 1000);
    return () => clearInterval(id);
  }, [approval]);

  if (!approval || approval.status !== 'PENDING') {
    return null;
  }

  const isCred = isCredentialRequest(approval);
  const isOtp = approval.parameters?.type === 'otp_request';
  const isCaptcha = approval.parameters?.type === 'captcha_request';
  const isAskUser = !isCred && !isOtp && !isCaptcha && isFormInputRequest(approval);
  const askFields = isAskUser ? parseFields(approval) : [];
  const askReason = (approval.parameters?.reason as string) || approval.prompt || 'The agent needs information to continue';
  const domain = (approval.parameters?.domain as string) || 'the site';

  const allRequiredFilled = askFields
    .filter(f => f.required)
    .every(f => (formValues[f.name] || '').trim().length > 0);

  const handleApprove = () => {
    if (isCred) {
      const payload = JSON.stringify({ username, password, saveToVault });
      onApprove(payload);
    } else if (isOtp) {
      const payload = JSON.stringify({ otpCode });
      onApprove(payload);
    } else if (isCaptcha) {
      onApprove('captcha_solved');
    } else if (isAskUser) {
      const payload = JSON.stringify(formValues);
      onApprove(payload);
    } else {
      onApprove(comment);
    }
  };

  const updateFormValue = (name: string, value: string) => {
    setFormValues(prev => ({ ...prev, [name]: value }));
  };

  const headerIcon = isCred ? <KeyRound className="w-6 h-6" /> :
    isAskUser ? <MessageSquareText className="w-6 h-6" /> :
    <ShieldAlert className="w-6 h-6" />;

  const headerColorClass = isCred ? 'bg-blue-500/15 border-blue-500/30 text-blue-400' :
    isAskUser ? 'bg-violet-500/15 border-violet-500/30 text-violet-400' :
    'bg-amber-500/15 border-amber-500/30 text-amber-400';

  const badgeColorClass = isCred ? 'bg-blue-500/20 text-blue-300 border-blue-500/30' :
    isAskUser ? 'bg-violet-500/20 text-violet-300 border-violet-500/30' :
    'bg-amber-500/20 text-amber-300 border-amber-500/30';

  const badgeLabel = isCred ? 'Login Required' :
    isAskUser ? 'Input Needed' :
    isCaptcha ? 'Your Turn' :
    isOtp ? 'Verification Code' :
    'Action Approval Required';

  const headerTitle = isCred ? `Login required for ${domain}` :
    isAskUser ? 'DOO needs your input' :
    isCaptcha ? 'Please solve the CAPTCHA' :
    isOtp ? 'Enter the verification code' :
    'DOO paused for user confirmation';

  const headerDesc = isCred ? 'Provide credentials to continue. They will only be used for this task.' :
    isAskUser ? askReason :
    isCaptcha ? 'DOO detected a CAPTCHA it cannot solve. Please complete it in the browser view below.' :
    isOtp ? 'A verification code was sent to your device. Enter it below to continue.' :
    'The agent is about to execute a protected or sensitive operation.';

  const borderColor = isAskUser ? 'border-violet-500/40' : 'border-amber-500/40';
  const shadowColor = isAskUser ? 'shadow-violet-500/10' : 'shadow-amber-500/10';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className={`w-full max-w-lg rounded-2xl bg-slate-900 border ${borderColor} shadow-2xl ${shadowColor} overflow-hidden`}>
        {/* Header */}
        <div className="p-6 bg-gradient-to-r from-amber-950/60 via-slate-900 to-slate-900 border-b border-amber-500/20 flex items-start space-x-4">
          <div className={`p-3 rounded-xl border ${headerColorClass}`}>
            {headerIcon}
          </div>
          <div className="flex-1">
            <div className="flex items-center space-x-2">
              <span className={`text-xs uppercase font-bold tracking-wider px-2 py-0.5 rounded border ${badgeColorClass}`}>
                {badgeLabel}
              </span>
            </div>
            <h3 className="text-lg font-bold text-white mt-1">{headerTitle}</h3>
            <p className="text-xs text-slate-400 mt-1">{headerDesc}</p>
          </div>
        </div>

        {/* Content */}
        <div className="p-6 space-y-4">
          {isCred ? (
            <>
              <div>
                <label className="block text-xs font-medium text-slate-300 mb-1.5">Username / Email</label>
                <input
                  type="text"
                  autoComplete="username"
                  placeholder="Enter username or email"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-300 mb-1.5">Password</label>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    placeholder="Enter password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50 pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300"
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>
              <label className="flex items-center space-x-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={saveToVault}
                  onChange={(e) => setSaveToVault(e.target.checked)}
                  className="w-4 h-4 rounded border-slate-700 bg-slate-950 text-blue-500 focus:ring-blue-500/50"
                />
                <span className="text-sm text-slate-300">Save to vault for future tasks on {domain}</span>
              </label>
            </>
          ) : isOtp ? (
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1.5">Verification Code</label>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="Enter code"
                value={otpCode}
                onChange={(e) => setOtpCode(e.target.value)}
                className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-amber-500/50 text-center text-lg tracking-widest font-mono"
              />
            </div>
          ) : isAskUser && askFields.length > 0 ? (
            <div className="space-y-3">
              {askFields.map((field) => (
                <div key={field.name}>
                  <label className="block text-xs font-medium text-slate-300 mb-1.5">
                    {field.label}
                    {field.required && <span className="text-violet-400 ml-1">*</span>}
                  </label>
                  {field.type === 'select' && field.options ? (
                    <select
                      value={formValues[field.name] || ''}
                      onChange={(e) => updateFormValue(field.name, e.target.value)}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 focus:outline-none focus:ring-2 focus:ring-violet-500/50"
                    >
                      <option value="">Select…</option>
                      {field.options.map(opt => (
                        <option key={opt} value={opt}>{opt}</option>
                      ))}
                    </select>
                  ) : field.type === 'textarea' ? (
                    <textarea
                      rows={3}
                      placeholder={field.placeholder}
                      value={formValues[field.name] || ''}
                      onChange={(e) => updateFormValue(field.name, e.target.value)}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-violet-500/50 resize-none"
                    />
                  ) : (
                    <input
                      type={field.type === 'email' ? 'email' : field.type === 'phone' ? 'tel' : 'text'}
                      placeholder={field.placeholder}
                      value={formValues[field.name] || ''}
                      onChange={(e) => updateFormValue(field.name, e.target.value)}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-violet-500/50"
                    />
                  )}
                </div>
              ))}
            </div>
          ) : (
            <>
              <div className="rounded-xl bg-slate-950/80 p-4 border border-slate-800">
                <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1">
                  {isCaptcha ? 'What DOO needs' : 'Proposed Action / Reason'}
                </span>
                <p className="text-sm font-mono text-amber-200 leading-relaxed whitespace-pre-wrap">
                  {approval.actionDescription || approval.reason || approval.prompt || 'Approval requested before continuing step'}
                </p>
              </div>
              {!isCaptcha && (
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
              )}
            </>
          )}

          <div className="flex items-center space-x-2 text-xs text-slate-400 bg-slate-800/40 p-2.5 rounded-lg">
            <Clock className="w-4 h-4 text-slate-400 shrink-0" />
            <span>
              Task execution is suspended.
              {timeLeft != null && (
                <> Expires in <span className={`font-mono font-semibold ${timeLeft < 120 ? 'text-rose-400' : 'text-amber-300'}`}>
                  {Math.floor(timeLeft / 60)}:{String(timeLeft % 60).padStart(2, '0')}
                </span></>
              )}
            </span>
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
            <span>{isCred ? 'Skip Login' : isAskUser ? 'Skip' : 'Reject'}</span>
          </button>
          <button
            type="button"
            onClick={handleApprove}
            disabled={isSubmitting || (isCred && (!username || !password)) || (isOtp && !otpCode) || (isAskUser && !allRequiredFilled)}
            className="flex items-center space-x-2 px-5 py-2 rounded-xl text-sm font-semibold text-slate-950 bg-gradient-to-r from-amber-400 to-amber-500 hover:from-amber-300 hover:to-amber-400 shadow-md shadow-amber-500/20 transition-all disabled:opacity-50"
          >
            <CheckCircle2 className="w-4 h-4" />
            <span>{isCred ? 'Login & Continue' : isCaptcha ? 'Done — Continue' : isOtp ? 'Submit Code' : isAskUser ? 'Submit & Continue' : 'Approve & Continue'}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
