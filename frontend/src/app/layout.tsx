import type { Metadata } from 'next';
import { Suspense } from 'react';
import './globals.css';
import Navbar from '@/components/Navbar';

export const metadata: Metadata = {
  title: 'DOO — Autonomous Browser Agent',
  description: 'You ask. It does. GUI-first personal AI agent for multi-step browser tasks and verifiable outcomes.',
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark">
      <body className="bg-slate-950 text-slate-100 min-h-screen flex flex-col antialiased selection:bg-sky-500/30 selection:text-sky-200">
        <Suspense fallback={<div className="h-16 border-b border-slate-800/80 bg-slate-950/70" />}>
          <Navbar />
        </Suspense>
        <Suspense fallback={<div className="flex-1 flex items-center justify-center p-12 text-slate-500 text-xs font-mono">Loading DOO...</div>}>
          <main className="flex-1 flex flex-col">{children}</main>
        </Suspense>
        <footer className="border-t border-slate-900 bg-slate-950/80 py-6 text-center text-xs text-slate-500">
          <p>DOO Autonomous Agent · Phase 1 · Built with Spring Boot, Node.js Playwright & Next.js</p>
        </footer>
      </body>
    </html>
  );
}
