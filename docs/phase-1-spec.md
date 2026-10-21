# DOO Phase 1 — Canonical Specification

> Single source of truth for architecture, contracts, lifecycle, limits, security, and acceptance criteria.

---

## 1. Architecture

| Component | Stack | Port | Role |
|---|---|---:|---|
| Frontend | Next.js (React) | 3000 | Task submission, live progress stream, history, evidence, approvals |
| Backend | Spring Boot 3.3.5, Java 21 | 8082 | Task lifecycle, AI orchestration, execution loop, persistence, SSE |
| Browser Worker | Node.js, Express, Playwright | 3001 | Chromium session management, action execution, perception |
| Database | PostgreSQL (prod) / H2 in-memory (dev/test) | 5432 | Durable task, step, evidence, approval, result storage |
| Test Fixture | Static HTML served by `serve` | 8081 | Deterministic multi-step form for Workflow C |

Communication: Backend → Worker via authenticated HTTP (`X-Worker-Secret`). Frontend → Backend via REST + SSE.

---

## 2. Perception Contract — `doo.perception.v1`

At every decision point the worker constructs a **structured observation**. The AI provider receives the interactive-element list and bounded page text for normal decisions; screenshots are complementary.

### Strategy

- **Default:** DOM/accessibility-first. Send `interactive_elements` + bounded `page_text`.
- **Screenshots:** After navigation, after meaningful state changes, after failed/ambiguous actions, and on initial inspection. Configurable via `includeScreenshot`.
- **Re-observe:** After every action that could change page state. Never silently reuse a stale observation.
- **Truncation:** `page_text` capped at 8 000 chars; `interactive_elements` capped at 100. Truncation is explicit in `metadata`.

### Observation Schema

```json
{
  "schema_version": "doo.perception.v1",
  "observation_id": "obs_a1b2c3d4e5f6g7h8",
  "task_id": "task_123",
  "url": "https://example.com/search",
  "title": "Example Search",
  "observed_at": "2026-10-21T10:00:00Z",
  "page_text": {
    "text": "Search the site for products...",
    "truncated": false
  },
  "interactive_elements": [
    {
      "index": 0,
      "role": "textbox",
      "label": "Search",
      "text": "",
      "placeholder": "Search products",
      "type": "search",
      "enabled": true,
      "visible": true,
      "href": null,
      "bounding_box": { "x": 180, "y": 120, "width": 400, "height": 40 }
    },
    {
      "index": 1,
      "role": "button",
      "label": "Submit search",
      "text": "Search",
      "enabled": true,
      "visible": true,
      "href": null,
      "bounding_box": { "x": 600, "y": 120, "width": 100, "height": 40 }
    }
  ],
  "screenshot": {
    "available": true,
    "base64": "<jpeg base64>",
    "mime_type": "image/jpeg"
  },
  "metadata": {
    "interactive_elements_count": 2,
    "interactive_elements_truncated": false,
    "page_text_truncated": false,
    "page_text_length": 31
  }
}
```

Each interactive element carries an `index` stable within that observation only. The `_locator` hints (id, name, role, label, text, tagName) are used internally by `resolveElement` and are not sent to the AI provider.

Password field values are always masked as `"***"`.

### Element Resolution

`resolveElement(page, observation, elementIndex)` resolves an element via five strategies in order:

1. By `id` attribute (most stable)
2. By Playwright `getByRole(role, { name })` (accessibility)
3. By `getByLabel(label)` (form fields)
4. By `[name="..."]` attribute
5. By bounding-box center coordinate (fallback)

---

## 3. Action Contract — `doo.action.v1`

The model returns **one action per turn**, referencing its originating observation.

### Example

```json
{
  "schema_version": "doo.action.v1",
  "observation_id": "obs_a1b2c3d4e5f6g7h8",
  "action": "fill",
  "element_index": 0,
  "text": "Example product",
  "reason": "Enter the requested search query"
}
```

### Action Types

| Action | Required Fields | Notes |
|---|---|---|
| `navigate` | `url` | URL validated (see §9). Screenshots taken after. |
| `click` | `element_index` | Resolved via observation. Legacy `selector` supported. |
| `fill` | `element_index`, `text` | Password fields rejected. Legacy `selector`+`value` supported. |
| `select_option` | `element_index`, `value` | For `<select>` elements. |
| `press_key` | `key`, optionally `element_index` | Safe keys only: Enter, Tab, Escape, Backspace, Delete, Arrow*, Home, End, PageUp, PageDown, Space. |
| `scroll` | `direction` (`up`/`down`), `amount` | Amount capped at 3 000 px. |
| `wait` | `milliseconds` | Capped at 5 000 ms. |
| `go_back` | — | Browser history back. Screenshot taken after. |
| `extract_text` | `selector` (default `body`) | Text truncated at 10 000 chars. |
| `screenshot` | — | Forces screenshot in observation. |
| `finish` | `reason` | Model's assertion that work is done. Does **not** mark task `COMPLETED`. |

### Validation Rules

1. `click`, `fill`, `select_option`, `press_key` referencing an element **must** include `observation_id` and `element_index`.
2. An action referencing a stale/expired observation is rejected.
3. `element_index` must exist in the corresponding observation's `interactive_elements`.
4. Unknown fields are ignored; unknown action types are rejected.
5. The model response is a **proposal**. The backend validates, authorizes, and executes.

### Blocked Actions

These are rejected even if proposed by the model: `EXECUTE_SCRIPT`, `SHELL_COMMAND`, `FILE_WRITE`, `HTTP_REQUEST`, `PURCHASE`, `CHECKOUT`, `SUBSCRIBE`, `DELETE`, `LOGIN`.

### Action Results

Every executed action returns one of: `success`, `failure`, `timeout`, `stale_observation`, `target_not_found`.

---

## 4. Task Lifecycle

```
QUEUED ──→ RUNNING ←──→ WAITING_FOR_USER
              │                   │
              ├──→ VERIFYING ─────┤
              │        │          │
              │        ├──→ COMPLETED
              │        ├──→ PARTIAL
              │        └──→ FAILED
              ├──→ PARTIAL
              ├──→ FAILED
              └──→ CANCELLED
```

### Allowed Transitions

| From | To |
|---|---|
| `QUEUED` | `RUNNING`, `CANCELLED` |
| `RUNNING` | `WAITING_FOR_USER`, `VERIFYING`, `PARTIAL`, `FAILED`, `CANCELLED` |
| `WAITING_FOR_USER` | `RUNNING`, `CANCELLED`, `FAILED` |
| `VERIFYING` | `COMPLETED`, `PARTIAL`, `FAILED` |

Terminal states (`COMPLETED`, `PARTIAL`, `FAILED`, `CANCELLED`) are immutable.

### Execution Cycle

```
Observe → Select one action → Validate → Authorize → Execute → Verify effect → Re-observe → Continue or finish
```

Every step records: action type, originating observation ID, inputs (secrets redacted), execution result, timing, and resulting observation reference.

---

## 5. Terminal State Definitions

### COMPLETED

All success criteria satisfied. Backend verification has sufficient evidence. The model's claim alone is not sufficient; plan exhaustion alone is not sufficient.

### PARTIAL

At least one meaningful, independently verifiable result exists, but one or more success criteria remain incomplete. The result must state what was completed, what remains, why execution stopped, and which evidence supports the completed portion.

Not used merely because the model returned uncertain text or a timeout occurred. If no meaningful verified result exists → `FAILED`.

### FAILED

Task could not meet success criteria and did not produce sufficient useful, verified output. Includes an actionable error code and user-facing explanation.

Error codes: `NO_PROGRESS`, `CONSECUTIVE_FAILURES`, `TASK_DEADLINE_EXCEEDED`, `AI_CALL_BUDGET_EXCEEDED`, `BROWSER_INIT_FAILED`, `PROVIDER_UNAVAILABLE`, `USER_HANDOFF_EXPIRED`.

### CANCELLED

Explicitly cancelled. Not reported as successful. History and evidence preserved.

---

## 6. Budget Limits

| Limit | Default | Config Key |
|---|---:|---|
| Browser actions (steps) | 30 | `TASK_MAX_STEPS` |
| AI provider calls | 40 | `TASK_MAX_AI_CALLS` |
| Max retries (consecutive failures) | 3 | `TASK_MAX_RETRIES` |
| Task deadline | 10 min | `TASK_TIMEOUT_MINUTES` |
| Session idle timeout | 15 min | `SESSION_IDLE_MS` |
| Session absolute TTL | 60 min | `SESSION_TTL_MS` |
| Stale RUNNING recovery | 15 min | `TASK_STALE_MINUTES` |
| Gemini per-call timeout | 30 s | hardcoded |
| Gemini retries per call | 3 | hardcoded |
| Max URL length | 2 048 | hardcoded |
| Max page text | 8 000 chars | `MAX_PAGE_TEXT_LENGTH` |
| Max interactive elements | 100 | `MAX_INTERACTIVE_ELEMENTS` |
| Max screenshot size | 500 KB base64 | hardcoded |
| WAIT action cap | 5 000 ms | hardcoded |
| SCROLL amount cap | 3 000 px | hardcoded |

Budgets are checked **before** starting additional calls. On exhaustion: stop safely, close browser, produce `PARTIAL` if useful verified output exists, else `FAILED`.

---

## 7. No-Progress Detection

Sliding window of action fingerprints. A fingerprint is `actionType|key1=val1&key2=val2` with args sorted alphabetically, ignoring keys prefixed `_`.

| Parameter | Value |
|---|---:|
| Window size | 5 |
| Threshold | 3 |

If any single fingerprint appears ≥ threshold times within the window → terminate with `NO_PROGRESS`. This catches the repeated-FILL regression where the agent fills the same field identically in a loop.

Repetition is not always wrong — the check uses identity of (action + args), so filling different fields or navigating different URLs does not trigger it.

---

## 8. Evidence-Based Verification

The verifier receives: user goal, success criteria, action history, current/final page state, and an `evidenceSummary` list.

Requirements:

- `finish` ≠ `COMPLETED`. The backend must independently verify.
- Plan exhaustion ≠ `COMPLETED`.
- A successful browser action does not prove the task goal was achieved.
- Missing mandatory evidence prevents `COMPLETED`.
- Verification failure → `PARTIAL` or `FAILED` based on actual work completed.

For **UI fixture** tasks: assert the machine-readable `doo-result` JSON and submission ID.

For **research** tasks: verify substantive evidence and traceable source URLs.

For **product comparison**: verify normalized attributes with traceable sources.

Evidence persisted: screenshots, page text, source URLs, action results, verification outcomes. Correlated by task and step.

### Demo Mode Verification

In demo mode, verification passes only if `evidenceSummary` is non-empty AND `visitedUrls` is non-empty. This prevents false positives.

### Live Mode Verification

AI-assisted. The prompt includes the evidence block. If the provider fails during verification, the result is `FAILED` — never a fabricated success.

---

## 9. Security Rules

### URL Validation

Enforced at both backend (`UrlValidator`) and worker (`security.js`).

| Rule | Detail |
|---|---|
| Allowed schemes | `http:`, `https:` only |
| Blocked schemes | `file:`, `ftp:`, `data:`, `javascript:`, `blob:` |
| Blocked hosts | localhost, 127.x.x.x, 10.x.x.x, 192.168.x.x, 172.16-31.x.x, 169.254.x.x, ::1, fc00:*, fe80:*, metadata.google.internal |
| Localhost exception | `localhost:8081` and `127.0.0.1:8081` allowed for test fixture |
| Max URL length | 2 048 characters |

### Browser Security

- Chromium launched headless with `--no-sandbox` (Codespaces/container requirement) and `--disable-setuid-sandbox`.
- `--disable-web-security` is **not** used.
- Worker authenticated via `X-Worker-Secret` header; secret not exposed to frontend.
- Browser contexts isolated per task; cleaned up on every terminal path.
- SSRF mitigation via URL validation on NAVIGATE; redirects are not independently checked (known limitation).

### Action Security

- Model output validated against action allowlist before execution.
- Blocked actions rejected regardless of model proposal.
- Password fields never filled; password values never appear in observations.
- Purchase/checkout/subscribe/delete actions prohibited.
- Page content treated as untrusted — cannot override action policy.

### Approval Enforcement

Approval decisions enforced in backend, not merely by model prompting. Approvals have a 10-minute expiry. Expired, duplicate, mismatched, or consumed approvals are rejected.

---

## 10. Workflows

### A. Web Research

1. Understand the research question
2. Navigate and search via browser UI (submit search via button or Enter)
3. Visit relevant results, extract visible content
4. Gather traceable source URLs
5. Verify sufficient substantive evidence
6. Return structured answer distinguishing findings from uncertainty
7. Produce `PARTIAL` if research incomplete

Not complete merely because planned steps were exhausted or a search page was reached.

### B. Product Comparison

1. Navigate to product pages via browser
2. Extract product info (name, price, features) from visible page content
3. Normalize comparable attributes
4. Distinguish unavailable info from confirmed facts
5. Produce comparison with traceable sources
6. Never fabricate prices, specs, or reviews

### C. Multi-Step UI Interaction

Uses the test fixture at `http://localhost:8081/index.html`:

1. Navigate to the fixture
2. Fill Step 1 (name, email, company) — `#full-name`, `#email`, `#company`
3. Click "Next →" to proceed
4. Fill Step 2 (select plan, choose use case) — `#plan` select, radio buttons
5. Click "Next →" to review
6. Click "✓ Submit Registration"
7. Verify the machine-readable `#doo-result` JSON contains `status: "REGISTERED"` and a valid `submissionId`

The fixture produces a `data-testid="doo-verification-result"` element with JSON that can be parsed for deterministic verification.

---

## 11. Session Resume & Expiry

| Parameter | Default |
|---|---|
| Idle timeout | 15 minutes |
| Absolute TTL | 60 minutes |
| Reaper interval | 60 seconds |
| Approval expiry | 10 minutes |

### WAITING_FOR_USER

When approval is required:

1. Persist task state, pending request, evidence, session ID, expiry timestamps
2. Keep browser context alive within permitted lifetime
3. On resume: validate approval against pending request; obtain fresh observation before next action
4. On expiry: close browser, mark `FAILED` with `USER_HANDOFF_EXPIRED`

### Lost Sessions

A session ID in the database whose browser context is gone (worker restart) cannot be treated as resumable. Detect → reconcile → `PARTIAL` or `FAILED`.

### Cleanup

Every browser context is eventually closed: completion, failure, cancellation, timeout, worker shutdown. The reaper handles abandoned sessions.

---

## 12. AI Provider Abstraction

Interface: `AiProvider` with methods `classifyWorkflow`, `generatePlan`, `selectNextAction`, `synthesizeResult`, `verify`.

### Configuration

| Key | Default | Description |
|---|---|---|
| `AI_PROVIDER` | `gemini` | Provider selection |
| `GEMINI_API_KEY` | (empty) | API key via env var only |
| `GEMINI_MODEL` | `gemini-3.5-flash` | Model ID |
| `DEMO_MODE` | `false` | Force deterministic fallback |

### Demo Mode

- Explicit and observable: labeled in logs and health endpoint as `aiMode: "demo"`
- Uses deterministic classification, planning, action selection
- Verification requires evidence to pass
- Never silently represents deterministic output as live-model execution

### Live Mode

- Provider failures → `FAILED` or truthful `PARTIAL` (never fabricated success)
- Exponential backoff: 2^attempt seconds + jitter, 3 retries per call
- 30-second per-call timeout
- Structured output validated against action/planning schemas
- A failed provider call cannot bypass verification, authorization, or budgets

### Credentials

API keys in environment variables or secret store only. Never in source control. Never in frontend bundles.

---

## 13. Acceptance Gate

Phase 1 is complete when **all** of the following hold:

1. Perception contract and action schema documented and implemented end to end
2. Research no-progress regression fixed and covered by repeatable test
3. All three workflows complete successfully against acceptance criteria
4. Agent cannot mark an unmet task as `COMPLETED`
5. Budgets, deadlines, handoff expiry, session cleanup, restart behavior work
6. Terminal states and transitions have one consistent implementation
7. Security boundaries and task ownership enforced and tested
8. Frontend accurately reflects backend state
9. Migrations, configuration, environment setup reproducible
10. Codespaces configuration uses headless browser, documents port forwarding
11. All required automated tests pass
12. A successful live end-to-end workflow run with the real AI provider evidenced
13. Setup guide works from a clean environment
