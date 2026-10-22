'use client';

import React, { useState } from 'react';
import { CheckCircle2, XCircle, ShieldAlert, Clock, KeyRound, Eye, EyeOff, MessageSquareText, LogIn } from 'lucide-react';
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

interface AuthOption {
  value: string;
  label: string;
  description?: string;
}

function isCredentialRequest(approval: ApprovalRequest): boolean {
  return approval.parameters?.type === 'credential_request'
    || approval.proposedAction === 'CREDENTIAL_REQUEST';
}

function isFormInputRequest(approval: ApprovalRequest): boolean {
  return approval.parameters?.type === 'form_input_request'
    || approval.proposedAction === 'ASK_USER';
}

function isAuthGateRequest(approval: ApprovalRequest): boolean {
  return approval.parameters?.type === 'auth_gate'
    || approval.proposedAction === 'AUTH_GATE';
}

function isBotBlockRequest(approval: ApprovalRequest): boolean {
  return approval.parameters?.type === 'bot_block'
    || approval.proposedAction === 'BOT_BLOCK';
}

interface BotBlockOption {
  value: string;
  label: string;
}

function parseBotBlockOptions(approval: ApprovalRequest): BotBlockOption[] {
  const raw = approval.parameters?.options;
  if (!Array.isArray(raw) || raw.length === 0) {
    return [
      { value: 'open_live_view', label: 'Open the live browser view' },
      { value: 'skip', label: 'Skip this source and try another' },
      { value: 'stop', label: 'Stop the task' },
    ];
  }
  return raw.map((item: any) => {
    if (typeof item === 'string') {
      const idx = item.indexOf(':');
      if (idx !== -1) {
        return {
          value: item.slice(0, idx).trim(),
          label: item.slice(idx + 1).trim(),
        };
      }
      return { value: item.trim(), label: item.trim() };
    }
    if (typeof item === 'object' && item !== null) {
      return {
        value: String(item.value || item.id || ''),
        label: String(item.label || item.value || ''),
      };
    }
    return { value: String(item), label: String(item) };
  });
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

function parseAuthOptions(approval: ApprovalRequest): AuthOption[] {
  const raw = approval.parameters?.options;
  if (!Array.isArray(raw)) return [{ value: 'handoff', label: 'Sign in in the browser window' }];
  return raw.map((o: any) => ({
    value: typeof o === 'string' ? o : (o.value || o),
    label: typeof o === 'object' ? (o.label || o.value || o) : o,
    description: typeof o === 'object' ? o.description : undefined,
  }));
}

const HANDOFF_CHOICES = new Set(['handoff', 'oauth_note']);

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

  // Auth gate state
  const [authChoice, setAuthChoice] = useState<string>('');
  const [authEmail, setAuthEmail] = useState('');
  const [authPhone, setAuthPhone] = useState('');
  const [authUsername, setAuthUsername] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authShowPassword, setAuthShowPassword] = useState(false);
  const [authSaveToVault, setAuthSaveToVault] = useState(false);

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

  // Reset auth choice when a new approval arrives
  React.useEffect(() => {
    setAuthChoice('');
    setAuthEmail('');
    setAuthPhone('');
    setAuthUsername('');
    setAuthPassword('');
    setAuthSaveToVault(false);
  }, [approval?.id]);

  if (!approval || approval.status !== 'PENDING') {
    return null;
  }

  const isCred = isCredentialRequest(approval);
  const isOtp = approval.parameters?.type === 'otp_request';
  const isCaptcha = approval.parameters?.type === 'captcha_request';
  const isAuthGate = !isCred && !isOtp && !isCaptcha && isAuthGateRequest(approval);
  const isBotBlock = !isCred && !isOtp && !isCaptcha && !isAuthGate && isBotBlockRequest(approval);
  const isAskUser = !isCred && !isOtp && !isCaptcha && !isAuthGate && !isBotBlock && isFormInputRequest(approval);
  const askFields = isAskUser ? parseFields(approval) : [];
  const authOptions = isAuthGate ? parseAuthOptions(approval) : [];
  const botBlockOptions = isBotBlock ? parseBotBlockOptions(approval) : [];
  const askReason = (approval.parameters?.reason as string) || approval.prompt || 'The agent needs information to continue';
  const domain = (approval.parameters?.domain as string) || 'the site';

  // Auth gate selected option
  const selectedAuthOption = authOptions.find(o => o.value === authChoice);
  const isHandoffChoice = !authChoice || HANDOFF_CHOICES.has(authChoice);

  const allRequiredFilled = askFields
    .filter(f => f.required)
    .every(f => (formValues[f.name] || '').trim().length > 0);

  // Validate auth gate submit readiness
  const authGateSubmitReady = (() => {
    if (!authChoice) return false;
    if (isHandoffChoice) return true;
    if (authChoice === 'password') return authUsername.trim().length > 0 && authPassword.length > 0;
    if (authChoice === 'email_link') return authEmail.trim().length > 0;
    if (authChoice === 'phone') return authPhone.trim().length > 0;
    return true;
  })();

  const handleApprove = () => {
    if (isAuthGate) {
      const payload: Record<string, unknown> = { choice: authChoice || 'handoff' };
      if (authChoice === 'password') {
        payload.username = authUsername;
        payload.password = authPassword;
        payload.saveToVault = authSaveToVault;
      } else if (authChoice === 'email_link') {
        payload.email = authEmail;
      } else if (authChoice === 'phone') {
        payload.phone = authPhone;
      }
      onApprove(JSON.stringify(payload));
    } else if (isCred) {
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

  const headerIcon = isBotBlock ? <ShieldAlert className="w-6 h-6" /> :
    isAuthGate ? <LogIn className="w-6 h-6" /> :
    isCred ? <KeyRound className="w-6 h-6" /> :
    isAskUser ? <MessageSquareText className="w-6 h-6" /> :
    <ShieldAlert className="w-6 h-6" />;

  const headerColorClass = isBotBlock ? 'bg-rose-500/15 border-rose-500/30 text-rose-400' :
    isAuthGate ? 'bg-blue-500/15 border-blue-500/30 text-blue-400' :
    isCred ? 'bg-blue-500/15 border-blue-500/30 text-blue-400' :
    isAskUser ? 'bg-violet-500/15 border-violet-500/30 text-violet-400' :
    'bg-amber-500/15 border-amber-500/30 text-amber-400';

  const badgeColorClass = isBotBlock ? 'bg-rose-500/20 text-rose-300 border-rose-500/30' :
    isAuthGate ? 'bg-blue-500/20 text-blue-300 border-blue-500/30' :
    isCred ? 'bg-blue-500/20 text-blue-300 border-blue-500/30' :
    isAskUser ? 'bg-violet-500/20 text-violet-300 border-violet-500/30' :
    'bg-amber-500/20 text-amber-300 border-amber-500/30';

  const badgeLabel = isBotBlock ? 'Site Blocked' :
    isAuthGate ? 'Login Required' :
    isCred ? 'Login Required' :
    isAskUser ? 'Input Needed' :
    isCaptcha ? 'Your Turn' :
    isOtp ? 'Verification Code' :
    'Action Approval Required';

  const headerTitle = isBotBlock ? 'This site blocked automation' :
    isAuthGate ? `Login required for ${domain}` :
    isCred ? `Login required for ${domain}` :
    isAskUser ? 'DOO needs your input' :
    isCaptcha ? 'Please solve the CAPTCHA' :
    isOtp ? 'Enter the verification code' :
    'DOO paused for user confirmation';

  const headerDesc = isBotBlock
    ? ([approval.parameters?.reason, approval.parameters?.url].filter(Boolean).join(' ') || 'The site returned an access-denial page.')
    : isAuthGate
    ? ((approval.parameters?.authMethods as string) || 'Choose how to sign in to continue.')
    : isCred ? 'Provide credentials to continue. They will only be used for this task.'
    : isAskUser ? askReason
    : isCaptcha ? 'DOO detected a CAPTCHA it cannot solve. Please complete it in the browser view below.'
    : isOtp ? 'A verification code was sent to your device. Enter it below to continue.'
    : 'The agent is about to execute a protected or sensitive operation.';

  const borderColor = isBotBlock ? 'border-rose-500/40' :
    isAuthGate || isCred ? 'border-blue-500/40' :
    isAskUser ? 'border-violet-500/40' : 'border-amber-500/40';
  const shadowColor = isBotBlock ? 'shadow-rose-500/10' :
    isAuthGate || isCred ? 'shadow-blue-500/10' :
    isAskUser ? 'shadow-violet-500/10' : 'shadow-amber-500/10';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className={`w-full max-w-lg rounded-2xl bg-slate-900 border ${borderColor} shadow-2xl ${shadowColor} overflow-hidden`}>
        {/* Header */}
        <div className="p-6 bg-gradient-to-r from-blue-950/40 via-slate-900 to-slate-900 border-b border-blue-500/20 flex items-start space-x-4">
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
          {isBotBlock ? (
            <div className="space-y-4">
              {approval.parameters?.url && (
                <div className="rounded-xl bg-slate-950/80 p-4 border border-slate-800">
                  <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1">
                    Blocked URL
                  </span>
                  <p className="text-sm font-mono text-amber-200 break-all">
                    {String(approval.parameters.url)}
                  </p>
                </div>
              )}
              <div className="flex flex-col sm:flex-row gap-3 pt-2">
                {botBlockOptions.map((opt) => {
                  const isPrimary = opt.value === 'open_live_view';
                  const isDanger = opt.value === 'stop';
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      data-testid={`bot-block-btn-${opt.value}`}
                      onClick={() => onApprove(opt.value)}
                      disabled={isSubmitting}
                      className={
                        isPrimary
                          ? 'flex-1 flex items-center justify-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-slate-950 bg-gradient-to-r from-blue-400 to-blue-500 hover:from-blue-300 hover:to-blue-400 shadow-md shadow-blue-500/20 transition-all disabled:opacity-50'
                          : isDanger
                          ? 'flex-1 flex items-center justify-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-rose-300 bg-rose-950/50 hover:bg-rose-900/60 border border-rose-800/40 transition-colors disabled:opacity-50'
                          : 'flex-1 flex items-center justify-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-slate-300 bg-slate-800 hover:bg-slate-700 border border-slate-700 transition-colors disabled:opacity-50'
                      }
                    >
                      <span>{opt.label}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : isAuthGate ? (
            <>
              {/* Sign-in method radio list */}
              <div className="space-y-2">
                <label className="block text-xs font-medium text-slate-300 mb-2">Choose how to sign in</label>
                {authOptions.map((opt) => (
                  <label
                    key={opt.value}
                    className={`flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition-colors ${
                      authChoice === opt.value
                        ? 'border-blue-500/60 bg-blue-950/40'
                        : 'border-slate-700/60 bg-slate-800/30 hover:border-slate-600'
                    }`}
                  >
                    <input
                      type="radio"
                      name="auth_method"
                      value={opt.value}
                      checked={authChoice === opt.value}
                      onChange={() => setAuthChoice(opt.value)}
                      className="mt-0.5 w-4 h-4 text-blue-500 border-slate-600 bg-slate-900 focus:ring-blue-500/50"
                    />
                    <div>
                      <div className="text-sm font-medium text-slate-200">{opt.label}</div>
                      {opt.description && (
                        <div className="text-xs text-slate-400 mt-0.5">{opt.description}</div>
                      )}
                    </div>
                  </label>
                ))}
              </div>

              {/* Dynamic inputs per choice */}
              {authChoice === 'password' && (
                <div className="space-y-3 pt-1">
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5">Username / Email</label>
                    <input
                      type="text"
                      autoComplete="username"
                      placeholder="Enter username or email"
                      value={authUsername}
                      onChange={(e) => setAuthUsername(e.target.value)}
                      className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-slate-300 mb-1.5">Password</label>
                    <div className="relative">
                      <input
                        type={authShowPassword ? 'text' : 'password'}
                        autoComplete="current-password"
                        placeholder="Enter password"
                        value={authPassword}
                        onChange={(e) => setAuthPassword(e.target.value)}
                        className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50 pr-10"
                      />
                      <button
                        type="button"
                        onClick={() => setAuthShowPassword(!authShowPassword)}
                        className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300"
                      >
                        {authShowPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>
                  <label className="flex items-center space-x-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={authSaveToVault}
                      onChange={(e) => setAuthSaveToVault(e.target.checked)}
                      className="w-4 h-4 rounded border-slate-700 bg-slate-950 text-blue-500 focus:ring-blue-500/50"
                    />
                    <span className="text-sm text-slate-300">Save to vault for future tasks on {domain}</span>
                  </label>
                </div>
              )}

              {authChoice === 'email_link' && (
                <div className="pt-1">
                  <label className="block text-xs font-medium text-slate-300 mb-1.5">Email address</label>
                  <input
                    type="email"
                    autoComplete="email"
                    placeholder="Enter your email address"
                    value={authEmail}
                    onChange={(e) => setAuthEmail(e.target.value)}
                    className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50"
                  />
                </div>
              )}

              {authChoice === 'phone' && (
                <div className="pt-1">
                  <label className="block text-xs font-medium text-slate-300 mb-1.5">Phone number</label>
                  <input
                    type="tel"
                    autoComplete="tel"
                    placeholder="Enter your phone number"
                    value={authPhone}
                    onChange={(e) => setAuthPhone(e.target.value)}
                    className="w-full px-3.5 py-2.5 rounded-lg bg-slate-950 border border-slate-800 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/50"
                  />
                </div>
              )}

              {isHandoffChoice && authChoice && (
                <div className="rounded-xl bg-blue-950/30 border border-blue-500/20 p-3">
                  <p className="text-sm text-blue-200">
                    Complete sign-in in the browser window, then press <span className="font-semibold">Continue</span>.
                  </p>
                </div>
              )}
            </>
          ) : isCred ? (
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
        {!isBotBlock && (
          <div className="p-5 bg-slate-950/60 border-t border-slate-800/80 flex items-center justify-end space-x-3">
            <button
              type="button"
              onClick={() => onReject(comment)}
              disabled={isSubmitting}
              className="flex items-center space-x-2 px-4 py-2 rounded-xl text-sm font-semibold text-rose-300 bg-rose-950/50 hover:bg-rose-900/60 border border-rose-800/40 transition-colors disabled:opacity-50"
            >
              <XCircle className="w-4 h-4" />
              <span>{isAuthGate ? 'Skip Login' : isCred ? 'Skip Login' : isAskUser ? 'Skip' : 'Reject'}</span>
            </button>
            <button
              type="button"
              onClick={handleApprove}
              disabled={
                isSubmitting
                || (isAuthGate && !authGateSubmitReady)
                || (isCred && (!username || !password))
                || (isOtp && !otpCode)
                || (isAskUser && !allRequiredFilled)
              }
              className="flex items-center space-x-2 px-5 py-2 rounded-xl text-sm font-semibold text-slate-950 bg-gradient-to-r from-blue-400 to-blue-500 hover:from-blue-300 hover:to-blue-400 shadow-md shadow-blue-500/20 transition-all disabled:opacity-50"
            >
              <CheckCircle2 className="w-4 h-4" />
              <span>{isAuthGate ? 'Continue' : isCred ? 'Login & Continue' : isCaptcha ? 'Done — Continue' : isOtp ? 'Submit Code' : isAskUser ? 'Submit & Continue' : 'Approve & Continue'}</span>
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
