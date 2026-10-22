# DOO — GUI-First Personal AI Agent (Phase 1)

> **”You ask. It does.”**

## Running headed locally / noVNC in Docker

### Headed on your own machine (best for login + CAPTCHA)

```bash
# browser-worker/.env (or export before starting)
HEADLESS=false        # or HEADED=true
PROFILE_MODE=persistent   # default — reuses cookies across restarts
STORAGE_STATE_DIR=~/.doo-worker/storage-states
```

Start the worker as normal (`node src/server.js`).  Chromium will open a
real window.  When DOO encounters a login page or CAPTCHA, the **Live
View** card appears; click **Open live view** and interact directly in the
browser window.  The session resumes automatically when the challenge
clears.

> **Why headed matters:** Cloud/datacenter IPs receive more bot
> challenges.  Running headed on your home IP looks like a normal user and
> significantly reduces friction.

### noVNC in Docker / GitHub Codespaces

The Dockerfile exposes an Xvfb-backed browser via noVNC when
`NOVNC=true`.  Forward the noVNC port and open it in a browser tab to see
and control the Chromium window remotely.

```bash
docker run -e NOVNC=true -e HEADED=true -p 6080:6080 doo-worker
# open http://localhost:6080 (or the forwarded Codespaces URL)
```

Add to `.devcontainer/devcontainer.json`:
```json
{ “forwardPorts”: [3001, 6080], “portsAttributes”: { “6080”: { “label”: “Browser (noVNC)” } } }
```

### Live browser view inside the approval card

When a `captcha_request` or `auth_gate` card appears in the UI, a
**LiveBrowser** canvas is shown (if the worker supports it).  Pointer
movements and keystrokes from the canvas are relayed to Chromium via CDP
Input events.  The stream is WebSocket-backed with a 10-minute
short-lived token; the same browser session is kept alive throughout.

### Honest limits

| Scenario | Best setup |
|---|---|
| Sign-in via email + password | Any mode; DOO fills from vault or asks once |
| Sign-in via Google / Apple / OAuth | Headed on home IP; use Live View |
| Cloudflare / reCAPTCHA v2 | Headed on home IP; use Live View |
| reCAPTCHA v3 (invisible badge) | No action needed — DOO ignores invisible badges |
| Bot-block wall (403/429/unusual traffic) | Live View or skip source |
| Cloud/datacenter IP | Challenges increase; headed helps but not guaranteed |

---

## 🤖 What DOO will and won't do about bot checks

DOO is **bot-friendly by design**. Rather than engaging in an adversarial arms race of anti-bot evasion and fingerprint spoofing, DOO operates as a transparent, polite client that honors website policies, prioritizes official interfaces, and hands off challenges to the human operator.

### What DOO will do

1. **Real Browser Environment**:
   - In headed mode (`HEADED=true`), launches real installed Google Chrome (`channel: 'chrome'`) with `launchPersistentContext` per domain.
   - Preserves authentic browser fingerprints: no custom User-Agent strings, no webdriver patch scripts, no hidden flags.
   - Inherits legitimate locale and timezone settings from environment variables (e.g. `en-IN`, `Asia/Kolkata`).

2. **Polite Client Dynamics**:
   - **Human Action Pacing**: Injects randomized pauses (`humanPause` 300–1200 ms) between user interactions.
   - **Single Concurrency**: Locks execution to one page at a time per domain to eliminate multi-tab burst scraping.
   - **Domain Navigation Throttling**: Enforces a minimum 2-second rate limit between navigations to the same domain.
   - **Backoff & Retry-After Adherence**: Immediately honors `Retry-After` headers (both delta-seconds and RFC 1123 HTTP dates) on HTTP 429 (Too Many Requests) and 503 (Service Unavailable) with exponential backoff.
   - **Domain Cooldown**: Enforces an automatic 15-minute cooldown on any domain after a bot block is observed, preventing recurring automated hammering.

3. **Prefer Official Routes First (`ROUTE_PREFERENCE`)**:
   - Before launching a browser, checks for official public REST APIs (Wikipedia, GitHub, Reddit, Hacker News, etc.) or RSS/Atom feeds for the target domain.
   - Recommends and utilizes official search APIs instead of scraping search engine result pages.
   - Automatically downloads and parses `robots.txt` for disallow rules and retrieves terms of service links. If `robots.txt` disallows the path, DOO halts and notifies the user instead of bypassing it.

4. **Session Reuse**:
   - Persists browser profile directories and Playwright `storageState` per domain after verified human login so recurring tasks retain valid sessions without triggering new-visitor challenge gates.

5. **Deterministic Page Classification & Human Hand-Off**:
   - Classifies every page before interacting: `NORMAL`, `LOGIN`, `CAPTCHA_VISIBLE`, or `BOT_BLOCK`.
   - Distinguishes between visible challenges and invisible background badges (such as reCAPTCHA v3), ignoring invisible badges to prevent false alarms.
   - On `CAPTCHA_VISIBLE`, pauses execution, opens the Live View card, and waits for the human user to solve the challenge. Resumes only when the challenge has cleared.
   - On `BOT_BLOCK` (Cloudflare "Attention Required", "Just a moment", HTTP 403/429, or "unusual traffic"), stops immediately, takes a screenshot evidence artifact, sets the domain cooldown, and offers: hand off in live view / try another source / stop. **Never retries in an automated loop.**

6. **Honest & Transparent Reporting**:
   - The final synthesized task result includes an **Automation Transparency & Official Alternatives** report detailing which domains blocked automation, actions attempted, and alternative official APIs or feeds available for that domain.

### What DOO will NOT do

- ❌ **No CAPTCHA solving**: DOO will never use automated CAPTCHA solvers, audio transcribers, or third-party captcha-breaking APIs.
- ❌ **No token farming**: DOO will never farm, harvest, or pre-generate anti-bot bypass tokens or cookies.
- ❌ **No stealth plugins**: DOO does not use `puppeteer-extra-plugin-stealth` or modify internal browser JavaScript objects.
- ❌ **No fingerprint spoofing**: DOO does not spoof Canvas, WebGL, AudioContext, client hints, or TLS client hellos.
- ❌ **No proxy rotation to dodge blocks**: DOO does not rotate residential proxies or IP pools to circumvent rate limits or IP bans.




DOO is an autonomous, GUI-first personal AI agent designed to execute real multi-step tasks inside actual web browsers, capture photographic proof of every action, and rigorously verify whether the requested outcome was actually achieved.

---

## 🏛️ Architecture Overview

```
                      ┌─────────────────────────────────────────┐
                      │          DOO Web Frontend               │
                      │     Next.js 16 (App Router) + TS        │
                      │   Task Composer · Live Feed · Replays   │
                      └────────────────────▲────────────────────┘
                                           │
                        REST API (HTTP)    │ SSE Event Stream
                                           ▼
                      ┌─────────────────────────────────────────┐
                      │          DOO Core Backend               │
                      │          Spring Boot 3.3.4              │
                      │  State Machine · Planner · Verifier     │
                      └───────┬──────────────────────┬──────────┘
                              │                      │
             PostgreSQL 17    │                      │ HTTP Client (Authenticated)
             Neon Cloud DB    │                      │
                      ┌───────▼───────┐      ┌───────▼──────────────────┐
                      │  Flyway Schema│      │   DOO Browser Worker     │
                      │  PostgreSQL   │      │   Node.js 22 + Playwright│
                      └───────────────┘      │   Chromium Headless/UI   │
                                             └──────────────────────────┘
```

### Components

1. **Backend (`/backend`)**:
   - **Framework**: Spring Boot 3.3.4 (Java 21)
   - **Persistence**: PostgreSQL 17 via Spring Data JPA and Flyway migrations
   - **State Machine**: Deterministic lifecycle (`QUEUED` → `RUNNING` → `WAITING_FOR_USER` → `VERIFYING` → `COMPLETED` / `PARTIAL` / `FAILED` / `CANCELLED`)
   - **AI Integration**: Pluggable `AiProvider` interface with Google Gemini 1.5-flash and deterministic demo fallback
   - **Real-time Comms**: Server-Sent Events (SSE) broadcasting live status, step updates, snapshots, and approval prompts

2. **Browser Worker (`/browser-worker`)**:
   - **Runtime**: Node.js 22 + Playwright
   - **Isolation**: Per-task isolated browser contexts with ad/tracker resource blocking and auto-teardown
   - **Action Allowlist**: Strictly validates executable operations (`NAVIGATE`, `CLICK`, `FILL`, `SCREENSHOT`, `EXTRACT_TEXT`, `SCROLL`, `WAIT`, `GO_BACK`, `DONE`)
   - **Security**: Localhost/Private IP blocking, password field input prevention, script execution restrictions

3. **Frontend (`/frontend`)**:
   - **Framework**: Next.js 16 (Turbopack, TypeScript, Tailwind CSS, Lucide icons)
   - **Task Composer**: Direct prompt input, one-click benchmark workflow presets, keyboard shortcuts
   - **Live View**: Real-time browser preview, action stream timeline, cancel controls
   - **Approval Panel**: Modal for sensitive or protected operations (`WAITING_FOR_USER`)
   - **Results & Evidence**: Synthesized summary, verification badges, confidence score, full screenshot lightbox gallery
   - **History Archive**: Searchable audit log of past runs and outcomes

4. **Test Fixture (`/test-fixture`)**:
   - Multi-step interactive HTML web application for deterministic Workflow C testing and validation.

---

## 🛡️ Safety & Reliability Guardrails

- **Strict URL Validation**: Rejects `file://`, `data:`, `javascript:`, `localhost`, `127.0.0.1`, RFC 1918 private subnets, and AWS/GCP cloud metadata endpoints.
- **Action Allowlist**: The agent can only execute predefined, safe browser interactions. Direct code evaluation (`eval`, `<script>`) is blocked at both backend and worker levels.
- **Human-in-the-Loop (`WAITING_FOR_USER`)**: Actions classified as sensitive (e.g. final form submissions, monetary transactions, or credential requests) pause execution and require explicit user approval.
- **Outcome Verification**: Tasks are not marked complete merely because actions ran. An automated verification step evaluates the final browser state, confirmation codes, or extracted data against the original prompt criteria.
- **Crash Recovery**: Orphaned or crashed tasks running longer than 10 minutes without heartbeat are automatically marked `FAILED` with diagnostic recovery logs.

---

## 🚀 Quickstart Guide

### Prerequisites

- **Java**: Temurin JDK 21+
- **Maven**: Apache Maven 3.9+
- **Node.js**: v20+ (v22 recommended)
- **PostgreSQL**: Neon Cloud Database or local PostgreSQL 17

### 1. Environment Configuration

Copy `.env.example` to `.env` in the root and fill in your keys:

```bash
cp .env.example .env
```

Key variables:
```env
SPRING_DATASOURCE_URL=jdbc:postgresql://<neon-host>/neondb?sslmode=require
SPRING_DATASOURCE_USERNAME=neondb_owner
SPRING_DATASOURCE_PASSWORD=<your-password>
DOO_AI_PROVIDER=gemini # or "demo" for offline test mode
GEMINI_API_KEY=<your-gemini-api-key>
```

### 2. One-Command Dev Startup (Windows PowerShell)

Run the included startup script to launch all 4 services simultaneously:

```powershell
.\start-dev.ps1
```

Or start components individually:

#### Browser Worker
```bash
cd browser-worker
npm install
npx playwright install chromium
npm start
# Worker runs on http://localhost:3001
```

#### Test Fixture Server
```bash
npx -y serve -l 8081 test-fixture
# Fixture runs on http://localhost:8081
```

#### Backend
```bash
cd backend
mvn spring-boot:run
# Backend runs on http://localhost:8082
```

#### Frontend
```bash
cd frontend
npm install
npm run dev
# Frontend runs on http://localhost:3000
```

Open [http://localhost:3000](http://localhost:3000) to access DOO.

---

## 🧪 Testing Benchmark Workflows

### Workflow A: Web Research
- **Prompt**: *"Research the top 3 trending open source AI agent frameworks on GitHub. Extract their repository names, key features, and summarize their main strengths."*
- **Outcome**: DOO navigates to live repositories, extracts key capabilities, takes screenshots, synthesizes an answer, and marks the result **VERIFIED**.

### Workflow B: Product Comparison
- **Prompt**: *"Compare Linear and Jira for a 10-person software engineering team. Compare pricing tiers, key workflow differences, and deliver a clear recommendation."*
- **Outcome**: DOO navigates pricing and feature pages, compares tiers side-by-side, extracts exact quotes, and provides source links.

### Workflow C: UI Interaction & Multi-Step Form
- **Prompt**: *"Open http://localhost:8081/index.html, fill out personal info, select developer preferences, submit the form, and verify the confirmation code."*
- **Outcome**: DOO interacts with the local test fixture step-by-step, navigates form steps, requests user approval before final submission, records the confirmation code, and verifies outcome.

---

## 🧪 Running Automated Tests

### Backend Unit & Integration Tests (JUnit 5 + Spring Boot Test)
```bash
cd backend
mvn test
```
*Includes: Task State Machine (12 tests), URL Validator (25 tests), REST API integration (5 tests), AI Provider contracts (23 tests), Action Validation (37 tests), Approval Lifecycle (5 tests).*

### Browser Worker Security Tests (Node.js Test Runner)
```bash
cd browser-worker
node --test src/tests/security.test.js
```
*All 40 security tests passing (URL blocking, SSRF prevention, action validation, fixture exception, scheme tests, length limits).*

### Frontend Production Build Test
```bash
cd frontend
npm run build
```
*Turbopack compilation and TypeScript verification passing with 0 errors.*

---

## ⚠️ Known Limitations (Phase 1)

1. **No login/authentication bypass**: DOO cannot log into websites, bypass CAPTCHA, or complete authentication challenges. Tasks requiring login will pause for user intervention.
2. **No purchasing or consequential transactions**: Checkout, payment, subscription, and account modification actions are explicitly blocked.
3. **No multi-agent coordination**: Phase 1 runs one task at a time per browser session. Concurrent tasks use separate isolated contexts but share the same Chromium process.
4. **External website variability**: Live web research (Workflows A/B) depends on external site availability and layout. Anti-bot measures on some sites may block the agent. The local test fixture (Workflow C) provides a deterministic fallback.
5. **Browser sandbox exception**: The Playwright browser launches with `--no-sandbox` and `--disable-web-security` flags for compatibility in containerized/CI environments. This weakens same-origin policy and OS-level sandboxing. In production, the browser worker should run in a dedicated container or VM with network isolation.
6. **Password field detection**: Password inputs are blocked by selector name matching and DOM `type="password"` inspection. Fields using custom password widgets without standard attributes may not be detected.
7. **Demo mode deterministic behavior**: When no Gemini API key is configured, the agent runs in demo mode with keyword-based classification and hardcoded plans. Demo mode results are clearly labeled in the UI. Demo mode does not exercise real web research or AI reasoning.
8. **Step and timeout limits**: Tasks are bounded to 30 steps maximum and 15 minutes execution timeout. Stale tasks are auto-recovered as FAILED after the timeout.
9. **Screenshot evidence size**: Screenshots are capped at 500KB base64 to prevent database bloat. Full-page screenshots are not captured; only the visible viewport.
10. **WhatsApp, mobile, voice, and recurring schedules**: Not implemented in Phase 1. Architecture supports future channel integration.

---

## 📄 License & Attribution

DOO Phase 1 · Built for autonomous, verifiable browser agent operations.
