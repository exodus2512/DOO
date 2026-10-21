# DOO — GUI-First Personal AI Agent (Phase 1)

> **“You ask. It does.”**

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
# Backend runs on http://localhost:8080
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
