'use client';

import React, { useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  Sparkles,
  ArrowRight,
  Globe,
  SlidersHorizontal,
  CheckCircle2,
  Terminal,
  Send,
  Loader2,
  Bot,
  ExternalLink,
} from 'lucide-react';
import { api } from '@/lib/api';
import { dooLogger } from '@/lib/logger';

export default function HomePage() {
  const router = useRouter();
  const [prompt, setPrompt] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const samplePrompts = [
    {
      badge: 'Workflow A · Web Research',
      title: 'Trending AI Agent Frameworks',
      query:
        'Research the top 3 trending open source AI agent frameworks on GitHub. Extract their repository names, key features, and summarize their main strengths.',
      icon: Globe,
      color: 'from-sky-500/20 to-blue-600/10 border-sky-500/30 text-sky-400',
    },
    {
      badge: 'Workflow B · Product Comparison',
      title: 'Linear vs Jira Evaluation',
      query:
        'Compare Linear and Jira for a 10-person software engineering team. Compare pricing tiers, key workflow differences, and deliver a clear recommendation.',
      icon: SlidersHorizontal,
      color: 'from-indigo-500/20 to-purple-600/10 border-indigo-500/30 text-indigo-400',
    },
    {
      badge: 'Workflow C · UI Interaction & Form',
      title: 'Automated 3-Step Registration',
      query:
        'Open the test fixture at http://localhost:8081/index.html, fill out personal info, select developer preferences, submit the form, and verify the confirmation code.',
      icon: CheckCircle2,
      color: 'from-emerald-500/20 to-teal-600/10 border-emerald-500/30 text-emerald-400',
    },
  ];

  const handleSubmit = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const cleanPrompt = prompt.trim();
    if (!cleanPrompt || isSubmitting) return;

    dooLogger.userEvent({
      event: 'Submit Task Button Clicked',
      process: 'User submitted task prompt from composer; initiating task creation API call',
      data: { prompt: cleanPrompt },
      nextStep: 'Request dispatched to POST /api/tasks; will redirect to /tasks/:id',
    });

    setIsSubmitting(true);
    setError(null);

    try {
      const task = await api.createTask({
        userRequest: cleanPrompt,
      });
      dooLogger.userEvent({
        event: 'Task Created Successfully',
        process: `Task ${task.id} initialized with status ${task.status}; transitioning browser to detail view`,
        data: { taskId: task.id, status: task.status, workflowType: task.workflowType },
        nextStep: `Opening /tasks/${task.id} to stream real-time execution stages`,
      });
      router.push(`/tasks/${task.id}`);
    } catch (err: any) {
      console.error('Failed to create task:', err);
      setError(err?.message || 'Failed to dispatch task. Ensure backend is running.');
      setIsSubmitting(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  return (
    <div className="flex-1 flex flex-col justify-center max-w-5xl mx-auto px-4 sm:px-6 py-12 w-full">
      {/* Hero Header */}
      <div className="text-center space-y-4 mb-10">
        <div className="inline-flex items-center space-x-2 px-3 py-1.5 rounded-full bg-sky-500/10 border border-sky-500/20 text-sky-400 text-xs font-semibold">
          <Sparkles className="w-3.5 h-3.5" />
          <span>DOO Autonomous Browser Agent</span>
        </div>
        <h1 className="text-4xl sm:text-6xl font-black tracking-tight text-white">
          You ask. <span className="bg-gradient-to-r from-sky-400 via-cyan-300 to-indigo-400 bg-clip-text text-transparent">It does.</span>
        </h1>
        <p className="text-slate-400 text-sm sm:text-base max-w-2xl mx-auto leading-relaxed">
          A GUI-first personal AI agent that operates real web browsers to execute complex, multi-step workflows with photographic proof and verifiable outcomes.
        </p>
      </div>

      {/* Main Composer Card */}
      <div className="glass-panel-glow rounded-3xl p-4 sm:p-6 mb-10 shadow-2xl relative">
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="relative">
            <textarea
              rows={4}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={isSubmitting}
              placeholder="What do you want DOO to accomplish? (e.g. 'Search for recent NASA Artemis updates and summarize mission status')"
              className="w-full rounded-2xl bg-slate-950/90 border border-slate-800/90 p-4 sm:p-5 text-sm sm:text-base text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-sky-500/50 resize-none transition-all leading-relaxed"
            />
          </div>

          {error && (
            <div className="p-3 rounded-xl bg-rose-950/50 border border-rose-800/50 text-rose-300 text-xs font-mono">
              {error}
            </div>
          )}

          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-2">
            <div className="flex items-center space-x-2 text-xs text-slate-500 font-mono">
              <Terminal className="w-3.5 h-3.5 text-slate-400" />
              <span>Press <kbd className="px-1.5 py-0.5 bg-slate-800 rounded border border-slate-700 text-slate-300">Enter</kbd> to execute</span>
            </div>

            <button
              type="submit"
              disabled={!prompt.trim() || isSubmitting}
              className="inline-flex items-center justify-center space-x-2 px-6 py-3 rounded-xl font-bold text-sm text-slate-950 bg-gradient-to-r from-sky-400 via-cyan-400 to-sky-500 hover:from-sky-300 hover:to-cyan-300 disabled:opacity-40 disabled:cursor-not-allowed shadow-lg shadow-sky-500/25 transition-all transform active:scale-95"
            >
              {isSubmitting ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin" />
                  <span>Dispatching Agent...</span>
                </>
              ) : (
                <>
                  <span>Execute Task</span>
                  <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </div>
        </form>
      </div>

      {/* Suggested Workflows */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-xs font-bold uppercase tracking-wider text-slate-400">
            Phase 1 Benchmark Workflows
          </h2>
          <span className="text-xs text-slate-500">Click any card to populate</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {samplePrompts.map((item, idx) => {
            const Icon = item.icon;
            return (
              <div
                key={idx}
                onClick={() => {
                  dooLogger.userEvent({
                    event: 'Benchmark Workflow Selected',
                    process: `User populated task composer with "${item.title}"`,
                    data: { badge: item.badge, title: item.title, prompt: item.query },
                    nextStep: 'Ready for user edit or direct submission',
                  });
                  setPrompt(item.query);
                }}
                className={`p-4 rounded-2xl border bg-gradient-to-br ${item.color} hover:border-sky-400/50 transition-all cursor-pointer group flex flex-col justify-between space-y-3 shadow-md hover:shadow-sky-500/10`}
              >
                <div>
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-[11px] font-bold uppercase tracking-wider font-mono opacity-80">
                      {item.badge}
                    </span>
                    <Icon className="w-4 h-4 opacity-70 group-hover:opacity-100 transition-opacity" />
                  </div>
                  <h3 className="font-bold text-sm text-slate-100 group-hover:text-white mb-1">
                    {item.title}
                  </h3>
                  <p className="text-xs text-slate-400 line-clamp-3 leading-relaxed">
                    {item.query}
                  </p>
                </div>
                <div className="text-[11px] text-sky-400 font-medium flex items-center gap-1 group-hover:translate-x-1 transition-transform">
                  <span>Use Workflow</span>
                  <ArrowRight className="w-3 h-3" />
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
