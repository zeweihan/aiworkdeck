# AI Workdeck Community Edition (Kernel)

> **AI Workdeck Community Edition (Kernel) — open-source core for document workflows (AGPLv3). Commercial license available for proprietary SaaS / enterprise use.**

## 1. Project Positioning
This repository hosts the **Community Edition (Kernel)** of AI Workdeck. It provides the core open-source infrastructure for verifiable, integrable document workflow and evidence chain capabilities.

**It is NOT the full commercial SaaS product.**
We open-source this kernel to empower developers to audit, integrate, and build upon our core document processing technologies.

## 2. Intellectual Property & "Look and Feel"
**Important Notice on Project Structure & Design:**
The value of AI Workdeck lies not just in the lines of code, but in the specific **Structure, Sequence, and Organization (SSO)** of its IDE-based document workflow.

*   **Protected Expression:** We assert copyright over the unique arrangement of our "Document-as-Code" workflow, the specific orchestration of AI agents within the editor context, and the visual/functional hierarchy of the IDE panels.
*   **Anti-Cloning:** If you create a derivative work that mimics this project's unique "IDE Structure and Logic Flow"—even if you reimplement the code from scratch—we generally consider this a derivative work of our **Trade Dress** and **Creative Expression**.
*   **Good Faith:** If you are inspired by this architecture, strict compliance with the AGPLv3 is expected. If you attempt to clone the *concept* and *workflow* for a closed-source product, you are violating the spirit and potentially the letter of our IP rights.

## 3. Licensing Strategy
We believe in sustainable open source.
- **Community / Open Source Use:** You are free to use, modify, and distribute this software under the terms of the **GNU Affero General Public License v3.0 (AGPLv3)**.
- **Proprietary / Commercial SaaS Use:** If you wish to use this software in a closed-source SaaS environment, proprietary enterprise deployment, or distribute it without releasing your modifications, **you must obtain a Commercial License**.

This dual-licensing model ensures the project remains free for the open-source community while providing a sustainable path for commercial integrations compared to closed-source copying.

## 3. Licensing Terms

### Community Edition (AGPLv3)
The "Community Edition / Kernel" of this project is released under the **AGPLv3**.

> If you modify this program and provide it as a network service to others (typical SaaS scenario), you must provide the corresponding source code to the users of that service under the AGPLv3.

### Commercial License
If you wish to use this project for:
- Closed-source SaaS delivery
- Proprietary on-premise delivery
- Distributing as part of a commercial non-open-source product

Please [contact us to acquire a Commercial License](mailto:hi@aiworkdeck.com).

*See [LICENSE](legal/LICENSE) for the full AGPLv3 text and [COMMERCIAL-LICENSE](legal/COMMERCIAL-LICENSE.md) for commercial licensing details.*

## Quick Start

### 1. Clone Repository
```bash
git clone https://github.com/zeweihan/aiworkdeck.git
cd aiworkdeck
```

### 2. Prerequisites
Before running the project, ensure you have the following installed. We provide links to the official installation guides:

- **Docker** (Required for PPTX generation & MinerU)
    - [Install Docker Desktop](https://www.docker.com/products/docker-desktop/)
- **Java 17+** (Required for Backend)
    - [Install JDK 17](https://www.oracle.com/java/technologies/downloads/#java17) or use [SDKMAN!](https://sdkman.io/) (`sdk install java 17.0.10-tem`)
- **Node.js 18+** (Required for Frontend & Desktop)
    - [Install Node.js](https://nodejs.org/en/download/) or use [nvm](https://github.com/nvm-sh/nvm) (`nvm install 18`)
- **PostgreSQL Database** (Required for Backend)
    - The project does **not** provide a Docker database container. You must have a running PostgreSQL instance.
    - [Download PostgreSQL](https://www.postgresql.org/download/) or use a Cloud Database.
    - **Setup**: Create a database named `checkba` (or customize in `.env`).

### 3. Configuration
Copy the example configuration files to their production counterparts and fill in your API keys.

#### 3.1 Backend Configuration
Copy `backend/.env.example` to `backend/.env.production`.

| Variable | Description | Default / Fallback | How to Obtain |
| :--- | :--- | :--- | :--- |
| **Server** | | | |
| `SERVER_PORT` | Backend Port | `9696` | N/A |
| **Database** | | | |
| `DB_HOST` | Database Host | `localhost` | Local Install or Cloud Provider |
| `DB_PORT` | Database Port | `5432` | |
| `DB_NAME` | Database Name | `checkba` | `CREATE DATABASE checkba;` |
| `DB_USERNAME` | Database User | `postgres` | |
| `DB_PASSWORD` | Database Password | (Required) | |
| **External APIs** | | | |
| `QICHACHA_KEY` | Company Data | N/A | [Qichacha Open Platform](https://openapi.qcc.com/) |
| `TUSHARE_TOKEN` | Stock Data | N/A | [Tushare](https://tushare.pro/) |
| `ELEVENLABS_KEY`| TTS Service | N/A | [ElevenLabs](https://elevenlabs.io/) |
| `PKULAW_TOKEN` | Legal Data | N/A | [PKULaw](https://mcp.pkulaw.com/) |
| `WPS_APP_ID` | Document Editor | N/A | [WPS WebOffice](https://wwo.wps.cn/) |
| **AI Models** | | | |
| `OPENROUTER_KEY`| LLM Aggregator | N/A | [OpenRouter](https://openrouter.ai/) |
| `GEMINI_API_KEY`| Google LLM | N/A | [Google AI Studio](https://aistudio.google.com/) |
| **Storage** | | | |
| `OSS_ACCESS_KEY`| Aliyun OSS | N/A | [Aliyun Console](https://oss.console.aliyun.com/) |

#### 3.2 PPTX Service Configuration (AI Slides)
Copy `pptx-service/.env.example` to `pptx-service/.env`.

| Variable | Description | Default / Fallback | How to Obtain |
| :--- | :--- | :--- | :--- |
| `AI_PROVIDER` | SDK Format | `gemini` | `gemini` or `openai` |
| **Gemini** | | | |
| `GOOGLE_API_KEY` | GenAI Key | (Required) | [Google AI Studio](https://aistudio.google.com/) |
| `GOOGLE_API_BASE`| Base URL | `https://generativelanguage.googleapis.com`| Optional Proxy |
| **OpenAI** | | | |
| `OPENAI_API_KEY` | OpenAI Key | N/A | [OpenAI Platform](https://platform.openai.com/) |
| `OPENAI_API_BASE`| Base URL | `https://api.openai.com/v1` | Optional Proxy |
| **Common** | | | |
| `TEXT_MODEL` | LLM Model | `gemini-2.0-flash-exp` | Model ID from Provider |
| `IMAGE_MODEL` | Image Model | `gemini-2.0-flash-exp` | Model ID from Provider |
| `MINERU_URL` | MinerU URL | `http://mineru-service:8000` | Local Docker Service |

### 4. Build & Run
We provide a one-click script to build and start all services (Backend, Frontend, Desktop, and Docker containers).

```bash
chmod +x restart-all.sh
./restart-all.sh
```
*Note: This script automatically checks for dependencies, stops old processes, packages the backend, and starts all services in the correct order.*

### 5. Local Access (Intranet Penetration)
If you are running this locally and need to expose it to the internet (e.g., for external API callbacks or remote access), we recommend using **cpolar**.

- **Official Website**: [https://www.cpolar.com/](https://www.cpolar.com/)

You can use cpolar to easily map your local ports (e.g., 5173 for Frontend, 9696 for Backend) to a public URL.
