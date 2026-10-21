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
| 2 | Dedicated branch, commit milestones | IN PROGRESS |
| 3 | Maintain PHASE1_PROGRESS.md | IN PROGRESS |
| 4 | Canonical Phase 1 spec | NOT STARTED |
| 5 | Perception contract | NOT STARTED |
| 6 | Action contract | NOT STARTED |
| 7 | Agent loop fixes | NOT STARTED |
| 8 | Budgets & deadlines | NOT STARTED |
| 9 | Session resume & expiry | NOT STARTED |
| 10 | Task outcome definitions | NOT STARTED |
| 11 | Three workflows | NOT STARTED |
| 12 | Evidence-based verification | NOT STARTED |
| 13 | Security audit | NOT STARTED |
| 14 | Persistence & recovery | NOT STARTED |
| 15 | AI provider production-safe | NOT STARTED |
| 16 | Web application experience | NOT STARTED |
| 17 | Codespaces devcontainer | NOT STARTED |
| 18 | Acceptance test suite | NOT STARTED |
| 19 | Acceptance gate | NOT STARTED |

---

## Milestone Log

### M0: Baseline commit
- Committed untouched baseline from previous session
- All 168 tests passing
- Files changed from initial commit: backend/pom.xml, AiProvider.java, GeminiAiProvider.java, TaskExecutorService.java, application.yml, AiProviderTest.java, sessions.js
- New files: ExecutionLoopE2ETest.java, NoProgressDetectionTest.java, sessions.test.js
