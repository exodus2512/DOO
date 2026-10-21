'use client';

import React, { useState } from 'react';
import { Camera, FileText, Image as ImageIcon, ExternalLink, Maximize2 } from 'lucide-react';
import { EvidenceArtifact } from '@/lib/api';

interface EvidenceGalleryProps {
  artifacts: EvidenceArtifact[];
}

export default function EvidenceGallery({ artifacts }: EvidenceGalleryProps) {
  const [selectedScreenshot, setSelectedScreenshot] = useState<string | null>(null);

  if (!artifacts || artifacts.length === 0) {
    return (
      <div className="p-6 text-center text-slate-500 border border-slate-800 rounded-xl bg-slate-900/30">
        <Camera className="w-8 h-8 mx-auto mb-2 opacity-40" />
        <p className="text-xs">No evidence artifacts recorded yet.</p>
      </div>
    );
  }

  const screenshots = artifacts.filter(
    (a) => a.artifactType === 'SCREENSHOT' || a.base64Data
  );
  const textArtifacts = artifacts.filter(
    (a) => a.artifactType === 'TEXT' || a.artifactType === 'PAGE_TEXT' || a.textContent
  );

  return (
    <div className="space-y-6">
      {/* Screenshots Section */}
      {screenshots.length > 0 && (
        <div className="space-y-3">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
            <ImageIcon className="w-3.5 h-3.5 text-sky-400" />
            Browser Snapshots ({screenshots.length})
          </h4>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {screenshots.map((item, idx) => (
              <div
                key={item.id || idx}
                className="group relative rounded-xl overflow-hidden border border-slate-800 bg-slate-950/80 hover:border-sky-500/50 transition-all cursor-pointer shadow-md"
                onClick={() => setSelectedScreenshot(item.base64Data || null)}
              >
                <div className="aspect-video w-full bg-slate-900 overflow-hidden flex items-center justify-center">
                  {item.base64Data ? (
                    <img
                      src={`data:image/jpeg;base64,${item.base64Data}`}
                      alt={item.description || `Step ${item.stepNumber || idx + 1}`}
                      className="w-full h-full object-cover object-top group-hover:scale-105 transition-transform duration-300"
                    />
                  ) : (
                    <div className="text-xs text-slate-500 font-mono">No preview</div>
                  )}
                </div>

                <div className="p-2.5 bg-slate-900/90 border-t border-slate-800/80 flex items-center justify-between">
                  <div className="truncate pr-2">
                    <span className="text-[11px] font-mono text-slate-300 block truncate">
                      {item.description || `Step ${item.stepNumber || idx + 1}`}
                    </span>
                    {item.sourceUrl && (
                      <span className="text-[10px] text-slate-500 block truncate font-mono">
                        {item.sourceUrl}
                      </span>
                    )}
                  </div>
                  <Maximize2 className="w-3.5 h-3.5 text-slate-400 group-hover:text-sky-400 shrink-0 transition-colors" />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Extracted Text Snippets */}
      {textArtifacts.length > 0 && (
        <div className="space-y-3">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
            <FileText className="w-3.5 h-3.5 text-amber-400" />
            Extracted Text & Page Evidence ({textArtifacts.length})
          </h4>
          <div className="space-y-2">
            {textArtifacts.map((item, idx) => (
              <div
                key={item.id || idx}
                className="rounded-xl border border-slate-800 bg-slate-950/60 p-3.5 space-y-1.5"
              >
                <div className="flex items-center justify-between text-xs">
                  <span className="font-mono text-slate-400 text-[11px]">
                    {item.description || `Extracted snippet #${idx + 1}`}
                  </span>
                  {item.sourceUrl && (
                    <a
                      href={item.sourceUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="text-sky-400 hover:text-sky-300 flex items-center gap-1 font-mono text-[10px]"
                    >
                      <ExternalLink className="w-2.5 h-2.5" /> Source
                    </a>
                  )}
                </div>
                <div className="text-xs text-slate-300 font-mono bg-slate-900/80 p-2.5 rounded border border-slate-800/80 max-h-32 overflow-y-auto custom-scrollbar whitespace-pre-wrap">
                  {item.textContent}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Modal Lightbox for Fullscreen Image */}
      {selectedScreenshot && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/90 backdrop-blur-md cursor-zoom-out animate-fade-in"
          onClick={() => setSelectedScreenshot(null)}
        >
          <div className="max-w-5xl max-h-[90vh] overflow-hidden rounded-2xl border border-slate-700 shadow-2xl">
            <img
              src={`data:image/jpeg;base64,${selectedScreenshot}`}
              alt="Expanded Evidence Screenshot"
              className="w-full h-auto max-h-[85vh] object-contain"
            />
          </div>
        </div>
      )}
    </div>
  );
}
