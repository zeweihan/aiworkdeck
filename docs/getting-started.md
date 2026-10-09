<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Getting started

> Originally contributed by @AzazelSensei in #730; refreshed 2026-09-29.

This is the from-source path for contributors and self-hosters. If you only want to try the product, download a [desktop release](https://github.com/zeweihan/aiworkdeck/releases/latest) instead; it bundles the backend, a JRE, and a local database, so none of the prerequisites below are needed.

> **Root `docker-compose.yml` is not a full stack / 根目录 compose 不是全栈.** It only starts the MinerU and PPTX sidecars; the backend, frontend, and PostgreSQL run on the host via `restart-all.sh`. See [docker-sidecars.md](docker-sidecars.md). Law-firm evaluators should start with [firm-eval-topology-a.md](firm-eval-topology-a.md) (desktop release, no prerequisites).

## Prerequisites

| Requirement | Version |
|---|---|
| Docker Desktop | Latest (MinerU and PPTX sidecars; the rest of the stack starts without it) |
| Java | JDK 21 (what CI builds with; the plugin SPI is compiled for 21). Newer JDKs such as 25 have crashed the Maven build on macOS. |
| Maven | 3.x |
| Node.js | 18+ (CI uses 20) |
| PostgreSQL | 14+ |
| npm | Use npm, not pnpm |

`restart-all.sh` is a bash script that relies on `lsof`; it is written for macOS and Linux.

## Run the stack

```bash
git clone https://github.com/zeweihan/aiworkdeck.git
cd aiworkdeck

cp backend/.env.example backend/.env.production
cp pptx-service/.env.example pptx-service/.env

# Create a PostgreSQL database named `checkba`
# (or point the backend env at your own database name)

chmod +x restart-all.sh
./restart-all.sh
```

The script starts the Docker sidecars, builds and starts the backend, starts the H5 dev server, and opens a dev Electron window.

| Service | URL |
|---|---|
| Frontend (H5 dev server) | http://localhost:5173 |
| Backend | http://localhost:9696 |
| PPTX service | http://localhost:5001 |
| MinerU service | http://localhost:8001 |

Running the parts one at a time:

| Part | Command | Notes |
|---|---|---|
| Backend | `cd backend && ./restart-backend.sh` | `mvn package -DskipTests`, then restarts the jar on 9696; log in `backend/app.log` |
| Frontend | `cd frontend && npm run dev:h5` | Port 5173. The end-to-end suites use a separate dev server on 5174 (`npx uni --port 5174`). |
| Desktop | `cd desktop && npm run dev` | Dev Electron window; reuses the backend already running on 9696 |

Text-to-speech is on-device (bundled Kokoro). `restart-all.sh` does not start the legacy EasyVoice container.

OpenRouter, Gemini, Qichacha, Tushare, PKULaw, Aliyun OCR/Tingwu, and object storage are optional. You can inspect the code and run the basic workbench without them.

## What you should see

The app opens straight into the workbench with a **Welcome** tab; there is no sign-in or unlock step at startup. You can create a project and open, edit, and save documents without an account. AI, paid Marketplace content, the team case library, and mobile sync ask you to sign in, in place, the first time you use them.

## Tests

- Backend: `cd backend && mvn -B test` (JDK 21).
- Frontend: `frontend/package.json` lists the suites (`npm run test:commands`, `npm run test:lowa-e2e`, `npm run test:app-e2e`, and others). The end-to-end suites need the backend on 9696 and a dev server on 5174.

## Contributing

1. Fork the repo, branch from `master`.
2. Read [CONTRIBUTING.md](../.github/CONTRIBUTING.md). Code PRs need a [CLA](../legal/CLA.md); the bot asks you to sign on the first PR.
3. Keep changes focused. Substantial API or plugin-SDK changes should start as an [RFC](../rfcs/0000-template.md).
4. New first-party source files carry the two-line SPDX header used across the repo (`SPDX-FileCopyrightText` plus `SPDX-License-Identifier: AGPL-3.0-or-later`); CI checks newly added files.
5. `docs/` is listed in `.gitignore`. To add a document there, use `git add -f`.
6. The app version has a single source: `desktop/package.json`. Do not bump versions in other files.

Useful first patches: setup notes for another OS, `.env` examples, plugin samples, tests around parsing / tool calls / frontend flows, bilingual docs.

See also [architecture](architecture.md) and the [plugin guide](plugin-guide.md).
