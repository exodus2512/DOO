'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  History,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Clock,
  Loader2,
  Search,
  ArrowRight,
  RefreshCw,
  ExternalLink,
  Bot,
} from 'lucide-react';
import { api, Task } from '@/lib/api';

export default function HistoryPage() {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  const fetchHistory = async () => {
    try {
      setLoading(true);
      const res = await api.listTasks(0, 50);
      setTasks(res.items || []);
    } catch (err) {
      console.error('Failed to load history:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHistory();
  }, []);

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'COMPLETED':
        return (
          <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
            <CheckCircle2 className="w-3 h-3" /> <span>COMPLETED</span>
          </span>
        );
      case 'RUNNING':
        return (
          <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-sky-500/15 text-sky-400 border border-sky-500/30">
            <Loader2 className="w-3 h-3 animate-spin" /> <span>RUNNING</span>
          </span>
        );
      case 'WAITING_FOR_USER':
        return (
          <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-amber-500/15 text-amber-300 border border-amber-500/30">
            <AlertTriangle className="w-3 h-3" /> <span>WAITING</span>
          </span>
        );
      case 'FAILED':
        return (
          <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-rose-500/15 text-rose-400 border border-rose-500/30">
            <XCircle className="w-3 h-3" /> <span>FAILED</span>
          </span>
        );
      case 'PARTIAL':
        return (
          <span className="inline-flex items-center space-x-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-amber-500/15 text-amber-300 border border-amber-500/30">
            <AlertTriangle className="w-3 h-3" /> <span>PARTIAL</span>
          </span>
        );
      default:
        return (
          <span className="px-2 py-0.5 rounded-full text-[11px] font-mono bg-slate-800 text-slate-400">
            {status}
          </span>
        );
    }
  };

  const filteredTasks = tasks.filter((task) => {
    const matchesSearch =
      task.userRequest.toLowerCase().includes(search.toLowerCase()) ||
      task.id.toLowerCase().includes(search.toLowerCase());
    const matchesStatus =
      statusFilter === 'ALL' || task.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  return (
    <div className="flex-1 max-w-6xl mx-auto px-4 sm:px-6 py-10 w-full space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center space-x-2 text-sky-400 text-xs font-semibold uppercase tracking-wider mb-1">
            <History className="w-4 h-4" />
            <span>Audit & Activity Trail</span>
          </div>
          <h1 className="text-2xl font-black text-white tracking-tight">Task History</h1>
          <p className="text-xs text-slate-400">Review previous autonomous sessions and outcomes</p>
        </div>

        <button
          onClick={fetchHistory}
          disabled={loading}
          className="flex items-center space-x-2 px-3.5 py-2 rounded-xl bg-slate-900 border border-slate-800 hover:border-slate-700 text-xs font-medium text-slate-300 hover:text-white transition-all self-start sm:self-auto"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin text-sky-400' : ''}`} />
          <span>Refresh</span>
        </button>
      </div>

      {/* Filters & Search */}
      <div className="glass-panel rounded-2xl p-4 flex flex-col md:flex-row items-center gap-3">
        <div className="relative flex-1 w-full">
          <Search className="w-4 h-4 text-slate-500 absolute left-3.5 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Search by task request or ID..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 rounded-xl bg-slate-950/80 border border-slate-800 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-sky-500/40"
          />
        </div>

        <div className="flex items-center space-x-2 w-full md:w-auto overflow-x-auto pb-1 md:pb-0">
          {['ALL', 'COMPLETED', 'RUNNING', 'WAITING_FOR_USER', 'PARTIAL', 'FAILED', 'CANCELLED'].map((st) => (
            <button
              key={st}
              onClick={() => setStatusFilter(st)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors shrink-0 ${
                statusFilter === st
                  ? 'bg-sky-500/20 text-sky-300 border border-sky-500/40'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/40'
              }`}
            >
              {st}
            </button>
          ))}
        </div>
      </div>

      {/* Task List */}
      {loading ? (
        <div className="py-20 text-center text-slate-500 space-y-3">
          <Loader2 className="w-8 h-8 animate-spin mx-auto text-sky-400" />
          <p className="text-xs">Loading task archives...</p>
        </div>
      ) : filteredTasks.length === 0 ? (
        <div className="glass-panel rounded-2xl p-12 text-center text-slate-500 space-y-3">
          <Bot className="w-10 h-10 mx-auto opacity-30" />
          <p className="text-sm font-medium text-slate-400">No tasks found</p>
          <p className="text-xs text-slate-600">
            Execute a new task from the composer to see records here.
          </p>
          <Link
            href="/"
            className="inline-flex items-center space-x-2 text-xs font-semibold text-sky-400 hover:text-sky-300 mt-2"
          >
            <span>Go to Composer</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </Link>
        </div>
      ) : (
        <div className="space-y-3">
          {filteredTasks.map((t) => (
            <Link
              key={t.id}
              href={`/tasks/${t.id}`}
              className="block glass-panel hover:glass-panel-glow rounded-2xl p-4 sm:p-5 transition-all group"
            >
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div className="space-y-1.5 flex-1 pr-2">
                  <div className="flex items-center space-x-2">
                    {getStatusBadge(t.status)}
                    <span className="font-mono text-[11px] text-slate-500">
                      {t.id.substring(0, 8)}...
                    </span>
                    {t.workflowType && (
                      <span className="text-[10px] font-mono uppercase px-1.5 py-0.5 rounded bg-slate-800 text-slate-400">
                        {t.workflowType}
                      </span>
                    )}
                  </div>
                  <h3 className="text-sm font-bold text-slate-200 group-hover:text-sky-300 transition-colors line-clamp-2">
                    {t.userRequest}
                  </h3>
                </div>

                <div className="flex items-center space-x-4 shrink-0 text-xs text-slate-500">
                  <div className="text-right">
                    <span className="block font-mono text-slate-400">
                      {t.currentStep || 0} steps
                    </span>
                    <span className="text-[11px] text-slate-600">
                      {new Date(t.createdAt).toLocaleDateString()}
                    </span>
                  </div>
                  <div className="p-2 rounded-xl bg-slate-900 group-hover:bg-sky-500/20 text-slate-400 group-hover:text-sky-300 transition-colors">
                    <ArrowRight className="w-4 h-4" />
                  </div>
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
