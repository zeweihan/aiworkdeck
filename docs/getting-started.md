# Getting started

This is the from-source path for contributors and self-hosters. If you only want to try the product, download a [desktop release](https://github.com/zeweihan/aiworkdeck/releases/latest) instead.

## Prerequisites

| Requirement | Version |
|---|---|
| Docker Desktop | Latest (MinerU, PPTX) |
| Java | 17+ (JDK 21 also supported) |
| Node.js | 18+ |
| PostgreSQL | 14+ |
| npm | Use npm, not pnpm |

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

| Service | URL |
|---|---|
| Frontend | http://localhost:5173 |
| Backend | http://localhost:9696 |
| PPTX service | http://localhost:5001 |
| MinerU service | http://localhost:8001 |

Text-to-speech is on-device (bundled Kokoro). `restart-all.sh` does not start the legacy EasyVoice container.

OpenRouter, Gemini, Qichacha, Tushare, PKULaw, Aliyun OCR/Tingwu, and object storage are optional. You can inspect the code and run the basic workbench without them.

## Contributing

1. Fork the repo, branch from `master`.
2. Read [CONTRIBUTING.md](../.github/CONTRIBUTING.md). Code PRs need a [CLA](../legal/CLA.md); the bot asks you to sign on the first PR.
3. Keep changes focused. Substantial API or plugin-SDK changes should start as an [RFC](../rfcs/0000-template.md).

Useful first patches: setup notes for another OS, `.env` examples, plugin samples, tests around parsing / tool calls / frontend flows, bilingual docs.

See also [architecture](architecture.md) and the [plugin guide](plugin-guide.md).
