'use client';

import React, { Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Bot, History, Shield, Zap, FlaskConical } from 'lucide-react';

function NavLinks() {
  const pathname = usePathname();

  const links = [
    { href: '/', label: 'Composer', icon: Zap },
    { href: '/history', label: 'Task History', icon: History },
  ];

  return (
    <nav className="flex items-center space-x-1 sm:space-x-3">
      {links.map((link) => {
        const Icon = link.icon;
        const isActive = pathname === link.href;
        return (
          <Link
            key={link.href}
            href={link.href}
            className={`flex items-center space-x-2 px-3.5 py-2 rounded-lg text-sm font-medium transition-all ${
              isActive
                ? 'bg-sky-500/15 text-sky-300 border border-sky-500/30 shadow-sm'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <Icon className="w-4 h-4" />
            <span>{link.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}

function AiModeIndicator() {
  const [aiMode, setAiMode] = useState<string | null>(null);

  useEffect(() => {
    fetch(`/api/tasks/health`)
      .then(res => res.json())
      .then(data => setAiMode(data.aiMode || null))
      .catch(() => setAiMode(null));
  }, []);

  if (!aiMode) return null;

  const isDemo = aiMode === 'demo';
  return (
    <span className={`flex items-center text-[11px] font-mono ${isDemo ? 'text-amber-400' : 'text-emerald-400'}`}>
      {isDemo && <FlaskConical className="w-3 h-3 mr-1" />}
      {isDemo ? 'DEMO MODE' : 'LIVE AI'}
    </span>
  );
}

export default function Navbar() {
  return (
    <header className="sticky top-0 z-40 border-b border-slate-800/80 bg-slate-950/70 backdrop-blur-xl">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        {/* Brand */}
        <Link href="/" className="flex items-center space-x-3 group">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-cyan-500 via-sky-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-sky-500/25 group-hover:shadow-sky-500/40 transition-all duration-300">
            <Bot className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <span className="font-extrabold text-xl tracking-tight text-white group-hover:text-sky-300 transition-colors">
                DOO
              </span>
              <span className="text-[10px] uppercase tracking-widest font-bold px-1.5 py-0.5 rounded bg-sky-500/10 text-sky-400 border border-sky-500/20">
                Phase 1
              </span>
            </div>
            <p className="text-xs text-slate-400">Autonomous Browser Agent</p>
          </div>
        </Link>

        {/* Navigation wrapped in Suspense */}
        <Suspense fallback={<div className="w-48 h-8 rounded-lg bg-slate-900/50 animate-pulse" />}>
          <NavLinks />
        </Suspense>

        {/* Right Status Indicator */}
        <div className="hidden md:flex items-center space-x-3 text-xs text-slate-400 border border-slate-800 bg-slate-900/60 rounded-full px-3 py-1.5">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
          <span className="font-mono text-slate-300">Worker Active</span>
          <span className="text-slate-600">|</span>
          <span className="flex items-center text-slate-400">
            <Shield className="w-3.5 h-3.5 mr-1 text-sky-400" /> Sandboxed
          </span>
          <span className="text-slate-600">|</span>
          <Suspense fallback={null}>
            <AiModeIndicator />
          </Suspense>
        </div>
      </div>
    </header>
  );
}
