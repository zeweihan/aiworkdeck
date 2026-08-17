<p align="center">
  <a href="https://www.aiworkdeck.com">
    <img src=".github/assets/icon.png" width="88" alt="AI Workdeck logo">
  </a>
</p>

<h1 align="center">AI Workdeck</h1>

<p align="center">
  <strong>The AI-native workspace for legal and document-heavy work.</strong><br>
  <sub>让法律人聚焦专业判断 — 面向法律与文档密集型团队的 AI 原生工作台</sub>
</p>

<p align="center">
  <a href="https://github.com/zeweihan/aiworkdeck/stargazers"><img src="https://img.shields.io/github/stars/zeweihan/aiworkdeck?style=social" alt="Stars"></a>
  <a href="https://github.com/zeweihan/aiworkdeck/releases"><img src="https://img.shields.io/github/v/release/zeweihan/aiworkdeck?color=1A5336" alt="Release"></a>
  <a href="https://github.com/zeweihan/aiworkdeck/releases"><img src="https://img.shields.io/badge/platform-macOS%20%7C%20Windows-1A5336" alt="Platform"></a>
  <a href="legal/LICENSE"><img src="https://img.shields.io/badge/license-AGPLv3-blue.svg" alt="License: AGPLv3"></a>
  <a href="legal/COMMERCIAL-LICENSE.md"><img src="https://img.shields.io/badge/commercial%20license-available-1A5336.svg" alt="Commercial license"></a>
  <a href="https://www.aiworkdeck.com"><img src="https://img.shields.io/badge/website-aiworkdeck.com-1A5336.svg" alt="Website"></a>
</p>

<p align="center">
  <strong>English</strong> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://www.aiworkdeck.com">
    <img src=".github/assets/workspace-ai.png" alt="AI Workdeck — project workspace with document preview and AI agent panel (real product screenshot)" width="900">
  </a>
</p>

---

> **VS Code** gives developers one place for files, extensions, terminals, Git, and AI coding assistants.
>
> **AI Workdeck** aims to give lawyers and document-heavy teams one place for matters, documents, agents, plugins, evidence, and review.

## Why Star This Repo

Star AI Workdeck if you care about any of these problems:

- Building AI-native legal or professional-service workflows
- Moving from chatbot add-ons to a real workspace where files, context, agents, and plugins live together
- Self-hosting document AI infrastructure with private data, audit trails, and organization-level workflows
- Exploring MCP-style agent orchestration, document parsing, embedded LibreOffice editing, AI slides, TTS, OCR, and evidence-chain workflows in one codebase

## What It Is

AI Workdeck Community Edition is the open-source kernel of AI Workdeck. It is not the full commercial SaaS product. The kernel is published so developers, law firms, legal-tech builders, and document-AI teams can inspect, self-host, integrate, and extend the core workflow infrastructure.

## Download & Run

Just want to try it? You don't need to build from source.

**➡️ [Download the latest desktop app](https://github.com/zeweihan/aiworkdeck/releases/latest)**

| Platform | Installer | Notes |
|---|---|---|
| macOS (Apple Silicon) | `AI Workdeck-<version>-arm64.dmg` | Signed and notarized |
| Windows | `AI Workdeck Setup <version>.exe` | Not yet code-signed; SmartScreen may warn |

> Intel mac builds are discontinued (upstream Python dependencies no longer ship x86_64 wheels). The last Intel dmg remains available in older releases.

Double-click to install. On first launch, a setup wizard lets you pick one AI provider — a cloud API key, or a fully local model via [Ollama](https://ollama.com) (zero key, data never leaves your machine). No Java, Docker, or PostgreSQL required: the backend, a trimmed JRE, and a local database are bundled in.

> The desktop build is the fastest way to evaluate AI Workdeck. To self-host the full stack or contribute code, see [Quick Start](#quick-start) below.

## Access & Unlock

The desktop app asks for an unlock code on first launch. There is no account to create and no sign-in: the code is entered once, on this machine, and the workspace opens directly from then on.

**Public trial code** — copy the whole string, hyphens included:

```
AWD-T-AEAW-U4WW-LCW4-T7RX-BLHO-V5DL-GZXB-QYKD-MX3O-4A7P-WFXU-6QVT-IE5Y-NL4X-PMIJ-ZQSZ-YY6K-N2H4-6WGB-SDOG-2LM7-JO62-PJDO-ASKY-NYR2-TLGR-YKUE-HYIK
```

The trial code is verified offline against a public key built into the application, so unlocking works without network access and without sending anything about your machine anywhere. Every feature is available in the trial; the workspace simply carries a discreet "trial" marker in the top bar.

**Full version.** Create an account at [aiworkdeck.com](https://www.aiworkdeck.com), generate an account key (prefix `awdk_`) on the account page, and paste it into the same unlock field. The key is verified online, removes the trial marker, and at the same time connects the desktop app to your account — synchronising anything you have purchased and enabling the hosted AI channel. You can disconnect at any time from the settings page, section 「账户与用量」 (Account & usage).

Two notes for firms evaluating deployment: unlocking is per machine and is stored locally at `~/.aiworkdeck/`; a self-hosted team deployment (browser access to a shared server) has no unlock gate at all.

## Demo

| | |
|---|---|
| Website | [aiworkdeck.com](https://www.aiworkdeck.com) |
| Product walkthrough | [Intro video](https://www.aiworkdeck.com/videos/intro.mp4) |
| Feature showcase | [AI Workdeck Showcase](https://www.aiworkdeck.com/zh/showcase) |

## Screenshots

The workspace above is a real product screenshot (demo project with fictitious data). Below: the plugin & skill marketplace, and a design preview of where the document workbench is heading.

<p align="center">
  <img src=".github/assets/plugin-marketplace.png" alt="Plugin and skill marketplace (design preview)" width="900">
</p>
<p align="center">
  <img src=".github/assets/workdeck-vision.png" alt="Document workbench with AI redlines (design preview)" width="900">
</p>

## Core Capabilities

| Area | What the kernel provides |
|---|---|
| **Workspace** | Project/file tree, document staging, favorites, clipboard memory, work logs |
| **AI document work** | Drafting, review, extraction, desensitization, Markdown and document preview |
| **Agent layer** | Main agent interface, streaming responses, contextual file tags, MCP-oriented orchestration |
| **Document editing** | Embedded LibreOffice (WASM) editor with native zh-CN UI, local DOCX editing with tracked changes, AI editor primitives, document links, diff viewing |
| **Parsing and generation** | MinerU document parsing, AI PPT generation, text-to-speech workflows |
| **Plugin surface** | Left-sidebar plugins, tool configuration, dedicated panes for vertical workflows |
| **Deployment** | Java/Spring backend, Vue/uni-app frontend, Electron desktop shell, Dockerized services |
| **Governance** | Private deployment path, audit-friendly workflow records, commercial licensing path |

## Architecture

```mermaid
flowchart TB
  User["User workspace"] --> IDE["IDE interaction layer"]
  IDE --> LO["Embedded LibreOffice editor"]
  IDE --> Agent["Agent and chat interface"]
  Agent --> MCP["MCP / tool orchestration"]
  MCP --> Skills["Document skills and plugins"]
  Skills --> Data["PostgreSQL, object storage, file context"]
  Skills --> Services["MinerU, PPTX service, TTS, OCR"]
  Data --> Security["Private deployment and audit controls"]
  Services --> Security
```



## Data Processing & Privacy

AI Workdeck is designed for **self-hosted, private deployment**. The following diagram shows which components process data locally vs. externally:

```
┌─────────────────────────────────────────────────────────────────┐
│  Your Infrastructure (private network)                          │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────┐  │
│  │ AI Agent │  │  MinerU  │  │   PPTX   │  │ Sensitive Data │  │
│  │ (Ollama) │  │ (Docker) │  │ (Docker) │  │    Masking     │  │
│  │  LOCAL   │  │  LOCAL   │  │  LOCAL   │  │     LOCAL      │  │
│  └──────────┘  └──────────┘  └──────────┘  └────────────────┘  │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────────────┐  │
│  │PostgreSQL│  │   RAG    │  │    Local File Storage        │  │
│  │  LOCAL   │  │  LOCAL   │  │          LOCAL               │  │
│  └──────────┘  └──────────┘  └──────────────────────────────┘  │
│                                                                 │
├────────────────────────── Optional External ────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐   │
│  │   OCR    │  │ Meeting  │  │ Company  │  │  Gemini /    │   │
│  │ (Aliyun) │  │ (Tingwu) │  │(Qichacha)│  │  OpenRouter  │   │
│  │ EXTERNAL │  │ EXTERNAL │  │ EXTERNAL │  │  CONFIGURABLE│   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

| Component | Default Location | Can Run Locally? | Notes |
|---|---|---|---|
| AI inference (chat/agent) | Local (Ollama) | ✅ Yes | Default is localhost:11434 |
| RAG / embeddings | Local (Apache Tika) | ✅ Yes | InMemoryEmbeddingStore |
| Document parsing (MinerU) | Local (Docker) | ✅ Yes | No external call |
| PPTX generation | Local (Docker) | ✅ Yes | No external call |
| Sensitive data masking | Local (regex) | ✅ Yes | Chinese PII patterns, no external call |
| Document storage | Local filesystem | ✅ Yes | Configurable: local / OSS / S3 |
| OCR | **Platform-sourced** (relayed to Aliyun through our servers) | No local fallback | Default on fresh installs; switchable to your own key or disabled |
| Text-to-speech | Local engine (bundled Kokoro) | Yes | On-device only; there is no cloud path |
| Company data lookup | **Platform-sourced** (relayed to Qichacha through our servers) | No local fallback | Default on fresh installs; switchable to your own key or disabled |
| Meeting transcription | **Platform-sourced** (audio staged in our object storage, deleted on completion) | On-device engine ships in a later version | Default on fresh installs; switchable to your own key or disabled |
| AI model (cloud) | **External** (Gemini/OpenRouter) | ✅ Use Ollama | Configurable provider |
| Anonymous usage stats | Local ledger + daily aggregated counts | ✅ Can be disabled | Counts and enum values only, no content; one switch in Settings, see legal/PRIVACY.md |

**For air-gapped deployments**: Ollama + local storage + MinerU + PPTX service keeps all documents entirely within your network. Disable OCR, company data, meeting transcription, and cloud AI providers — the core workspace, document editing, agent orchestration, and due-diligence workflows function without external services. Speech synthesis needs no disabling: it is on-device already.

## Evidence Chain & Audit Status

> **Status**: Foundation in place, cryptographic provenance is on the roadmap.

The Community Edition currently provides **relational audit logging**:

- **Activity logging**: `UserActivityLog` records every user action (LOGIN, OPEN_FILE, PAGE_VIEW, etc.) with timestamps via Hibernate `@CreationTimestamp` and metadata stored as JSON
- **Due-diligence tracking**: `DdItem` records state transitions (PENDING → UPLOADED → APPROVED/REJECTED) with `uploadedAt` and `uploadedBy`
- **Conversation audit**: `ConversationFileChange` logs document additions and modifications per session
- **File metadata**: `ProjectFile` stores `createdAt`/`updatedAt` timestamps and file paths in PostgreSQL

**What is NOT yet implemented** (on the roadmap):
- Cryptographic document hashing (SHA-256 checksums)
- Tamper-evident audit trails (Merkle chains or signed logs)
- File version history and diff tracking
- Immutable append-only evidence log

The architecture's plugin surface is designed to accommodate these features. If you need cryptographic provenance for compliance or litigation support, please open an issue describing your requirements — it helps us prioritize.

## Licensing FAQ for Law Firms

### Can our firm use AI Workdeck internally without disclosing our modifications?

**Usually yes — with one nuance worth understanding.** AGPLv3 affirms your right to run the software and to modify it for your own use. Running an *unmodified* copy internally creates no source-disclosure obligation. If you *modify* AI Workdeck and then make the modified version available to people over a network — which can include your own lawyers and staff using it as an internal web application — AGPLv3 Section 13 can require you to offer **those users** the corresponding source code of your modified version. That source stays within your firm: you are not required to publish it to the public or to us. If you would rather keep your modifications, plugins, or integrations proprietary and free of any AGPLv3 obligation, a commercial license removes the copyleft requirement entirely.

### What about the network-use clause (AGPLv3 Section 13)?

Section 13 is triggered by **network interaction with a modified version**, and the people entitled to the corresponding source are the **users of that modified version**. Two common cases:

- **Internal tool behind your firewall (modified).** Your staff are the users. You may need to offer them the corresponding source, but you are not required to disclose it publicly or to us. An unmodified deployment carries no such obligation.
- **Client-facing portal or external service (modified).** Your clients are the users, and AGPLv3 would require offering them the source of your modified version. If you do not want to do that, a commercial license is the clean path.

### What about proprietary plugins and extensions?

The current architecture runs plugins in-process. Under AGPLv3, this means the copyleft *may* extend to proprietary plugins. If your firm plans to build proprietary workflow extensions, the **commercial license** provides a clean legal basis for:
- Closed-source plugins and integrations
- Proprietary on-premise deployment without source disclosure
- Commercial SaaS products built on the AI Workdeck kernel

See [`legal/COMMERCIAL-LICENSE.md`](legal/COMMERCIAL-LICENSE.md) or contact [hi@aiworkdeck.com](mailto:hi@aiworkdeck.com).

### Where does our data go?

**AI conversations do not pass through our servers.** Conversation content goes straight from your machine to the model provider you selected: with local Ollama it is processed on the device only; with a cloud provider it goes to that third party; and even on the "AI Workdeck cloud" tier our servers only issue the key and settle usage. Documents and contract text, AI conversation text, file names, and project and client information therefore never flow through our servers — and in a self-hosted deployment with Ollama + local storage + Docker services, **they never leave your network at all**.

**The platform-sourced tier does pass through our servers.** On a fresh desktop install, image text recognition, web search, company registry data, securities and financial data, statute and case law search, and meeting transcription default to the "platform-sourced" tier: AI Workdeck buys from the vendor on your behalf and bills in Credits, so no key of your own is needed, and the content each call requires is relayed to the vendor through our servers. Meeting audio is the only item staged in our object storage — deleted as soon as transcription finishes, with a 24-hour lifecycle rule as a backstop; every other service passes content through only at call time and retains no request content. All six vendors are inside mainland China. Speech synthesis is not among them: it has an on-device tier only, synthesizing locally in the bundled engine. The per-service breakdown (what passes through, how long it is kept, when it is deleted) is in [`legal/PRIVACY.md`](legal/PRIVACY.md).

**Every item can be switched.** Under Settings → Platform Services any of them can be moved to your own key (the desktop app then connects to that vendor directly, bypassing us) or to an on-device tier, or disabled entirely; existing installs that already have a vendor key configured are not moved. Team-hosted server deployments and the Office add-in are always own-key — the platform-sourced tier is not offered to them.

The app shares anonymous aggregated usage statistics by default (daily counts of feature usage only, tied to a random install ID) to help improve the product; this can be turned off with one switch in Settings, and the exact data shape is documented in [`legal/PRIVACY.md`](legal/PRIVACY.md).

## Quick Start

> For developers who want to self-host the full stack or contribute code. If you just want to try the app, use [Download & Run](#download--run) above instead.

### Prerequisites

| Requirement | Version |
|---|---|
| Docker Desktop | Latest (for MinerU, PPTX, TTS services) |
| Java | 17+ (JDK 21 also supported) |
| Node.js | 18+ |
| PostgreSQL | 14+ |

### Steps

```bash
# 1. Clone
git clone https://github.com/zeweihan/aiworkdeck.git
cd aiworkdeck

# 2. Configure environment
cp backend/.env.example backend/.env.production
cp pptx-service/.env.example pptx-service/.env

# 3. Create database
# Create a PostgreSQL database named `checkba`
# or update backend environment variables for your own database name

# 4. Start all services
chmod +x restart-all.sh
./restart-all.sh
```

### Services

| Service | URL |
|---|---|
| Frontend | `http://localhost:5173` |
| Backend | `http://localhost:9696` |
| PPTX service | `http://localhost:5001` |
| MinerU service | `http://localhost:8001` |
| EasyVoice | `http://localhost:9549` |

Common optional providers: OpenRouter, Gemini, Qichacha, Tushare, PKULaw, Aliyun (OCR and Tingwu transcription), and object storage. Not every provider is required to inspect the code or run the basic workbench.

## Repository Map

| Path | Purpose |
|---|---|
| `backend/` | Spring Boot backend, agent/tool APIs, document services |
| `frontend/` | Vue/uni-app web frontend for the workbench |
| `desktop/` | Electron desktop shell |
| `pptx-service/` | AI-native PPT generation service |
| `mineru-service/` | MinerU-based document parsing service |
| `easyvoice/` | Text-to-speech service |
| `docs/` | Engineering notes, editor migration notes, storage and workflow docs |
| `legal/` | AGPLv3 license, CLA, commercial license, trademark terms |

## Roadmap

- [ ] Cleaner one-command local demo with sample data
- [ ] Public plugin SDK and example plugins
- [ ] More legal-document workflows: due diligence, shareholder meeting review, contract review, evidence timelines
- [ ] Better self-hosting guides for private law-firm and enterprise deployments
- [ ] More auditable work records: version history, diff, citations, and review logs
- [ ] Bilingual documentation for the community edition

## Contributing

We welcome issues, discussions, docs improvements, integration notes, and focused pull requests. Please read [CONTRIBUTING.md](.github/CONTRIBUTING.md) before submitting a PR.

Useful first contributions:

- Reproduce and document local setup paths on different operating systems
- Improve self-hosting docs and `.env` examples
- Add plugin examples
- Add tests around document parsing, agent tool calls, and frontend workflows
- Improve English and Chinese documentation

## Licensing

AI Workdeck Community Edition is released under the GNU Affero General Public License v3.0.

If you modify this project and provide it as a network service, AGPLv3 generally requires that you provide the corresponding source code to users of that service.

Commercial licensing is available for:

- Closed-source SaaS delivery
- Proprietary on-premise delivery
- Commercial products that need to integrate the kernel without releasing proprietary modifications
- Dedicated enterprise support and implementation assistance

See [LICENSE](legal/LICENSE) and [COMMERCIAL-LICENSE.md](legal/COMMERCIAL-LICENSE.md). For commercial licensing, contact [hi@aiworkdeck.com](mailto:hi@aiworkdeck.com).

**AI Workdeck®** is a registered trademark in China (classes 9, 35, 42). Building on the kernel is welcome under the licenses above, but using the **AI Workdeck** name or logo for a commercial offering requires a brand or certification agreement — see [TRADEMARKS.md](legal/TRADEMARKS.md) and the **Brand & Certification Programs** in [COMMERCIAL-LICENSE.md](legal/COMMERCIAL-LICENSE.md).

## Background

Read [WHY.md](WHY.md) for the product thesis and founder story.

---

## ⭐ Star History

<p align="center">
  <a href="https://github.com/zeweihan/aiworkdeck/stargazers">
    <img src="https://raw.githubusercontent.com/zeweihan/aiworkdeck/star-history/star-history.svg" alt="Star history chart" width="760">
  </a>
</p>
<p align="center">
  <sub>Self-hosted chart, refreshed weekly by <a href=".github/workflows/star-history.yml">a scheduled workflow</a>.</sub>
</p>

<p align="center">
  If this direction matters to you, please <a href="https://github.com/zeweihan/aiworkdeck/stargazers">⭐ star the repo</a> and share it with someone building legal AI, document AI, or professional-service infrastructure.
</p>
