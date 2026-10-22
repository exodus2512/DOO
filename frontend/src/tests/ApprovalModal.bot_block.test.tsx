/**
 * Component tests for ApprovalModal bot_block branch.
 *
 * Tests that a bot_block approval renders Badge "Site Blocked", title "This site blocked automation",
 * description from parameters.reason + parameters.url, renders all three option buttons,
 * and clicking each submits the corresponding choice token without a comment box.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ApprovalModal from '../components/ApprovalModal';
import { ApprovalRequest } from '../lib/api';

function makeBotBlockApproval(overrides?: Partial<ApprovalRequest>): ApprovalRequest {
  return {
    id: 'approval-bb-1',
    taskId: 'task-bb-1',
    prompt: 'Bot block detected at https://example.com/protected',
    proposedAction: 'BOT_BLOCK',
    parameters: {
      type: 'bot_block',
      url: 'https://example.com/protected',
      reason: 'The site returned an access-denial page.',
      options: [
        'open_live_view: Open the live browser view',
        'skip: Skip this source and try another',
        'stop: Stop the task',
      ],
    },
    status: 'PENDING',
    userResponse: null,
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
    resolvedAt: null,
    ...overrides,
  };
}

describe('ApprovalModal — bot_block branch', () => {
  it('renders the bot_block card with "Site Blocked" badge, title, and reason + url description', () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);

    render(
      <ApprovalModal
        approval={makeBotBlockApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    expect(screen.getByText('Site Blocked')).toBeInTheDocument();
    expect(screen.getByText('This site blocked automation')).toBeInTheDocument();
    expect(screen.getByText('The site returned an access-denial page. https://example.com/protected')).toBeInTheDocument();
    expect(screen.getAllByText(/https:\/\/example\.com\/protected/)).toHaveLength(2);
  });

  it('renders all three action buttons parsed from options and NO comment box', () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);

    render(
      <ApprovalModal
        approval={makeBotBlockApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    expect(screen.getByRole('button', { name: /Open the live browser view/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Skip this source and try another/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Stop the task/i })).toBeInTheDocument();

    // Verify there is no comment box or input field
    expect(screen.queryByPlaceholderText(/reason for decline|optional note/i)).not.toBeInTheDocument();
  });

  it('submits "open_live_view" token when the live view button is clicked', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);

    render(
      <ApprovalModal
        approval={makeBotBlockApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    const btn = screen.getByRole('button', { name: /Open the live browser view/i });
    fireEvent.click(btn);

    expect(onApprove).toHaveBeenCalledTimes(1);
    expect(onApprove).toHaveBeenCalledWith('open_live_view');
  });

  it('submits "skip" token when the skip button is clicked', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);

    render(
      <ApprovalModal
        approval={makeBotBlockApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    const btn = screen.getByRole('button', { name: /Skip this source and try another/i });
    fireEvent.click(btn);

    expect(onApprove).toHaveBeenCalledTimes(1);
    expect(onApprove).toHaveBeenCalledWith('skip');
  });

  it('submits "stop" token when the stop button is clicked', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);

    render(
      <ApprovalModal
        approval={makeBotBlockApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    const btn = screen.getByRole('button', { name: /Stop the task/i });
    fireEvent.click(btn);

    expect(onApprove).toHaveBeenCalledTimes(1);
    expect(onApprove).toHaveBeenCalledWith('stop');
  });

  it('detects bot_block when proposedAction is BOT_BLOCK even without parameters.type', () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);

    const approval = makeBotBlockApproval({
      parameters: {
        url: 'https://cf-blocked.io',
        reason: 'Cloudflare turnstile / challenge',
      },
    });

    render(
      <ApprovalModal
        approval={approval}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    expect(screen.getByText('Site Blocked')).toBeInTheDocument();
    expect(screen.getByText('This site blocked automation')).toBeInTheDocument();
    // Default fallback options should be rendered
    expect(screen.getByRole('button', { name: /Open the live browser view/i })).toBeInTheDocument();
  });
});
