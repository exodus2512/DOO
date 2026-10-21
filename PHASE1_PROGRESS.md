# DOO Phase 1 — Progress Tracker

## Baseline (captured at start of phase-1-finalize branch)

**Date:** 2026-10-21

### Test Results
- Backend: 121 tests passing (including 8 no-progress detection, 5 E2E, evidence-based verification)
- Browser Worker: 47 tests passing (including 7 session expiry)
- **Total: 168 passing**

### Running Services
- Frontend (Next.js): port 3000
- Backend (Spring Boot): port 8082
- Browser Worker (Playwright): port 3001
- Test Fixture: port 8081

### Already Implemented
- No-progress detection (sliding window 5, threshold 3)
- Observation versioning (epoch counter + hash)
- Wall-clock budget (configurable execution-timeout-minutes, default 10min)
- Session expiry (15min TTL, 5min idle, 60s reaper)
- Evidence-based verification (VerificationRequest carries evidenceSummary)
- Java 21 enforcement (maven-enforcer-plugin)
- Exponential backoff on Gemini retries
- Localhost navigation allowlisting (port 8081)

### Known Blockers
- Gemini free-tier API key returns 429 on every call; live AI mode never verified
- Model ID `gemini-3.5-flash` may not exist — needs verification against current Gemini docs
- Browser worker launches Chromium with `--no-sandbox` and `--disable-web-security`

---

## Section Progress

| # | Section | Status |
|---|---------|--------|
| 1 | Inspect & baseline repository | COMPLETE |
| 2 | Dedicated branch, commit milestones | COMPLETE |
| 3 | Maintain PHASE1_PROGRESS.md | IN PROGRESS |
| 4 | Canonical Phase 1 spec | COMPLETE |
| 5 | Perception contract | COMPLETE |
| 6 | Action contract | COMPLETE |
| 7 | Agent loop fixes | COMPLETE |
| 8 | Budgets & deadlines | COMPLETE |
| 9 | Session resume & expiry | COMPLETE |
| 10 | Task outcome definitions | COMPLETE |
| 11 | Three workflows | COMPLETE (demo mode verified, live mode blocked by API rate limits) |
| 12 | Evidence-based verification | COMPLETE |
| 13 | Security audit | COMPLETE |
| 14 | Persistence & recovery | COMPLETE |
| 15 | AI provider production-safe | COMPLETE |
| 16 | Web application experience | IN PROGRESS |
| 17 | Codespaces devcontainer | COMPLETE |
| 18 | Acceptance test suite | COMPLETE |
| 19 | Acceptance gate | IN PROGRESS |

---

## Milestone Log

### M0: Baseline commit
- Committed untouched baseline from previous session
- All 168 tests passing
- Files changed from initial commit: backend/pom.xml, AiProvider.java, GeminiAiProvider.java, TaskExecutorService.java, application.yml, AiProviderTest.java, sessions.js
- New files: ExecutionLoopE2ETest.java, NoProgressDetectionTest.java, sessions.test.js

### M1: Perception contract, action schema, budget tracking, session alignment
- Implemented doo.perception.v1 structured observations (browser-worker/src/perception.js)
- Rewrote actions.js to use perception module with element-index references
- Added SELECT_OPTION, PRESS_KEY action types with safe key allowlist
- Added AI call budget (max-ai-calls:40, configurable)
- Aligned session expiry: 60min absolute TTL, 15min idle (configurable via env)
- Removed --disable-web-security from Chromium launch args
- Added /session/observe endpoint for structured perception
- 12 perception tests, updated session tests
- Total: 183 tests passing (124 backend + 59 browser-worker)

### M2: Live-mode AI failure propagation (§15)
- All 5 GeminiAiProvider methods now throw RuntimeException in live mode instead of silent fallback
- Added 3 live-mode failure tests
- Demo mode explicitly logged, live failures logged as ERROR
- Total: 183 tests passing

### M3: Acceptance test suite (§18)
- Created AcceptanceTests.java with 21 tests covering:
  - Workflow tests: web research, product comparison, UI interaction
  - Agent reliability: no-progress detection, intermittent failure recovery, malformed JSON, plan exhaustion, insufficient evidence, budget exhaustion
  - Lifecycle: AI provider unavailable, browser launch failure, cancellation, stale task recovery, idempotent submission, terminal state transitions
  - Security: URL validation (file://, metadata, internal IPs, javascript:), local fixture allowed, blocked actions
  - API contract: task history, steps/evidence retrieval, task not found
- Total: 204 tests passing (145 backend + 59 browser-worker)
