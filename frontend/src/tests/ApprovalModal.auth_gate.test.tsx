/**
 * Component tests for ApprovalModal auth_gate branch.
 *
 * Tests that an auth_gate approval with options [handoff, password, email_link, oauth_note]
 * renders all four options and submits the correct JSON payload for each choice.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ApprovalModal from '../components/ApprovalModal';
import { ApprovalRequest } from '../lib/api';

// Minimal auth_gate approval fixture
function makeAuthGateApproval(overrides?: Partial<ApprovalRequest>): ApprovalRequest {
  return {
    id: 'approval-1',
    taskId: 'task-1',
    prompt: 'Login required for example.com',
    proposedAction: 'AUTH_GATE',
    parameters: {
      type: 'auth_gate',
      domain: 'example.com',
      authMethods: 'Google, GitHub, Email+Password, Email link',
      options: [
        {
          value: 'handoff',
          label: 'Sign in in the browser window',
          description: "I'll complete the login myself in the browser.",
        },
        {
          value: 'password',
          label: 'Enter username + password',
          description: 'Provide credentials and the agent will fill them.',
        },
        {
          value: 'email_link',
          label: 'Enter email address',
          description: 'Receive a magic link or verification code.',
        },
        {
          value: 'oauth_note',
          label: 'OAuth: Google, GitHub',
          description: "Choose 'Sign in in the browser' to use a social login.",
        },
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

describe('ApprovalModal — auth_gate branch', () => {
  it('renders the auth_gate card with "Login Required" badge and domain in title', () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );
    expect(screen.getByText('Login Required')).toBeInTheDocument();
    expect(screen.getByText('Login required for example.com')).toBeInTheDocument();
    expect(screen.getByText('Google, GitHub, Email+Password, Email link')).toBeInTheDocument();
  });

  it('renders all four sign-in options as radio buttons', () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );
    expect(screen.getByText('Sign in in the browser window')).toBeInTheDocument();
    expect(screen.getByText('Enter username + password')).toBeInTheDocument();
    expect(screen.getByText('Enter email address')).toBeInTheDocument();
    expect(screen.getByText('OAuth: Google, GitHub')).toBeInTheDocument();

    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(4);
  });

  it('Continue button is disabled until a method is selected', () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );
    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn).toBeDisabled();
  });

  it('submits {choice: "handoff"} when handoff is selected and Continue clicked', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    // Select handoff
    await user.click(screen.getByDisplayValue('handoff'));
    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn).not.toBeDisabled();
    await user.click(continueBtn);

    expect(onApprove).toHaveBeenCalledOnce();
    const payload = JSON.parse(onApprove.mock.calls[0][0]);
    expect(payload).toEqual({ choice: 'handoff' });
  });

  it('shows username/password/saveToVault inputs when "password" is selected', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByDisplayValue('password'));
    expect(screen.getByPlaceholderText('Enter username or email')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Enter password')).toBeInTheDocument();
    expect(screen.getByText(/Save to vault/i)).toBeInTheDocument();
  });

  it('submits password payload with username, password, and saveToVault', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByDisplayValue('password'));
    await user.type(screen.getByPlaceholderText('Enter username or email'), 'alice@example.com');
    await user.type(screen.getByPlaceholderText('Enter password'), 'hunter2');
    const checkbox = screen.getByRole('checkbox');
    await user.click(checkbox);

    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn).not.toBeDisabled();
    await user.click(continueBtn);

    expect(onApprove).toHaveBeenCalledOnce();
    const payload = JSON.parse(onApprove.mock.calls[0][0]);
    expect(payload).toEqual({
      choice: 'password',
      username: 'alice@example.com',
      password: 'hunter2',
      saveToVault: true,
    });
  });

  it('Continue stays disabled when password selected but credentials not filled', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByDisplayValue('password'));
    // Only fill username, not password
    await user.type(screen.getByPlaceholderText('Enter username or email'), 'alice@example.com');

    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn).toBeDisabled();
  });

  it('shows email input when email_link is selected', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByDisplayValue('email_link'));
    expect(screen.getByPlaceholderText('Enter your email address')).toBeInTheDocument();
  });

  it('submits {choice: "email_link", email: "..."} when email_link is selected', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByDisplayValue('email_link'));
    await user.type(screen.getByPlaceholderText('Enter your email address'), 'bob@example.com');

    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn).not.toBeDisabled();
    await user.click(continueBtn);

    expect(onApprove).toHaveBeenCalledOnce();
    const payload = JSON.parse(onApprove.mock.calls[0][0]);
    expect(payload).toEqual({ choice: 'email_link', email: 'bob@example.com' });
  });

  it('shows handoff helper text and Continue enabled when oauth_note is selected', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByDisplayValue('oauth_note'));
    expect(screen.getByText(/Complete sign-in in the browser window/i)).toBeInTheDocument();

    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn).not.toBeDisabled();
    await user.click(continueBtn);

    expect(onApprove).toHaveBeenCalledOnce();
    const payload = JSON.parse(onApprove.mock.calls[0][0]);
    expect(payload).toEqual({ choice: 'oauth_note' });
  });

  it('Reject calls onReject and does not call onApprove', async () => {
    const onApprove = vi.fn().mockResolvedValue(undefined);
    const onReject = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <ApprovalModal
        approval={makeAuthGateApproval()}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    await user.click(screen.getByRole('button', { name: /Skip Login/i }));
    expect(onReject).toHaveBeenCalledOnce();
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('returns null when approval is null', () => {
    const { container } = render(
      <ApprovalModal approval={null} onApprove={vi.fn()} onReject={vi.fn()} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('returns null when approval is not PENDING', () => {
    const { container } = render(
      <ApprovalModal
        approval={makeAuthGateApproval({ status: 'APPROVED' })}
        onApprove={vi.fn()}
        onReject={vi.fn()}
      />
    );
    expect(container.firstChild).toBeNull();
  });
});
