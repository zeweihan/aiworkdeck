# Third-Party Components Bundled with AI WorkDeck

Last reviewed: 2026-09-09

AI WorkDeck's desktop installers bundle several independently developed components. Each runs as a separate process or a separately loaded module and talks to AI WorkDeck over local HTTP or a documented interface. They are aggregated with AI WorkDeck, not derived from it, and each stays under its own license. AI WorkDeck's own license (AGPL-3.0, see [`LICENSE`](LICENSE)) and its Commercial License (see [`COMMERCIAL-LICENSE.md`](COMMERCIAL-LICENSE.md)) do not change the terms below.

This file is the human-readable inventory. Machine-readable per-file annotations live in the repository root `REUSE.toml`.

## Sidecar services (Python, bundled with the desktop app)

Packaging: `desktop/scripts/prepare-python-service.js` installs each service's `requirements.lock` in full into the installer. Model weights are not in the installer; the app downloads them on first use from the sources named below.

### pptx-service (PPT generation)

| Item | Detail |
|---|---|
| Upstream | [banana-slides](https://github.com/Anionex/banana-slides) by Anionex and contributors |
| License | AGPL-3.0. Vendored from upstream commit `a9a5c36` (2026-02-17), the commit in which upstream changed its license from CC BY-NC-SA 4.0 to AGPL-3.0. Earlier snapshots of this directory were under CC BY-NC-SA 4.0. |
| Where | `pptx-service/`, full source in this repository. Our modifications are marked `[checkba]` in the code and listed in `pptx-service/UPGRADE_CHECKBA.md`; files we added carry AGPL-3.0-or-later headers. |
| Notable dependencies | PyMuPDF (AGPL-3.0, dual-licensed with Artifex commercial terms; pulled in by pdf2docx), img2pdf (LGPL-3.0-or-later), pikepdf (MPL-2.0), python-pptx (MIT) |
| Source availability | This repository contains the complete corresponding source of the service as shipped. |

### mineru-service (document parsing and OCR)

| Item | Detail |
|---|---|
| Upstream | [MinerU](https://github.com/opendatalab/MinerU) by the MinerU Team (OpenDataLab) |
| License | Apache-2.0 with additional terms (see upstream `LICENSE.md`): a separate commercial license is required only above 100 million monthly active users or USD 20 million monthly revenue, and online services based on MinerU must state that MinerU is used. |
| Attribution | AI WorkDeck uses MinerU for local document parsing and OCR. |
| Where | `mineru-service/` is a thin HTTP wrapper around the official `mineru` package; no upstream source is vendored. |
| Notable dependencies | torch (BSD-3-Clause), onnxruntime (MIT), opencv-python (Apache-2.0), pypdfium2 (Apache-2.0 or BSD-3-Clause). No copyleft packages in the lock file. |
| Models | Downloaded on first use from ModelScope or Hugging Face under the licenses stated on each model card. |

### kokoro-service (speech synthesis)

| Item | Detail |
|---|---|
| Upstream | [kokoro](https://github.com/hexgrad/kokoro) and [misaki](https://github.com/hexgrad/misaki) by hexgrad |
| License | Apache-2.0 (packages and the Kokoro-82M model weights) |
| Where | `kokoro-service/` is a thin OpenAI-compatible HTTP wrapper; no upstream source is vendored. |
| Copyleft dependencies | phonemizer-fork (GPL-3.0-or-later), espeakng-loader (MIT wrapper that ships a compiled espeak-ng shared library; espeak-ng itself is GPL-3.0), num2words (LGPL-2.1). These are used by the English phonemization path. They are distributed unmodified; source is available from their upstream projects. |

### asr-service (speech recognition)

| Item | Detail |
|---|---|
| Upstream | [faster-whisper](https://github.com/SYSTRAN/faster-whisper) and [CTranslate2](https://github.com/OpenNMT/CTranslate2) by SYSTRAN and OpenNMT |
| License | MIT (packages) and MIT (faster-whisper-medium model weights) |
| Where | `asr-service/` is a thin HTTP wrapper; no upstream source is vendored. |
| Copyleft dependencies | None. |

## Document editor

### LibreOffice WASM (LOWA)

| Item | Detail |
|---|---|
| Upstream | [LibreOffice core](https://github.com/LibreOffice/core), branch `distro/allotropia/zeta-24-2`, by The Document Foundation and contributors |
| License | MPL-2.0 |
| Where | Shipped as compiled artifacts (`soffice.js`, `soffice.wasm`, `soffice.data`). Build recipe and our patches are in `desktop/lowa-build/` (`RECIPE.md`, `patches/`). |
| Source availability | The corresponding source is the upstream branch above plus the patches in `desktop/lowa-build/`. Together they reproduce the shipped artifacts. |
| Related | zeta.js marshalling layer by [allotropia](https://github.com/allotropia/zetajs), MIT, vendored under `frontend/src/zetaoffice/public/` (see `UPSTREAM.md` there). CJK fonts under the SIL Open Font License 1.1, downloaded at build time. |

## Diagram layout

### Graphviz

| Item | Detail |
|---|---|
| Upstream | [Graphviz](https://graphviz.org/) by AT&T Research and contributors |
| License | EPL-2.0 |
| Where | Only the `dot` binary and the `core`, `dot_layout`, and `neato_layout` plugins are bundled, taken from upstream release builds (`desktop/scripts/prepare-graphviz.js`). No rendering backends are included. |
| Source availability | https://gitlab.com/graphviz/graphviz |

## Components no longer bundled

- easyVoice (upstream `cosin2077/easyVoice`): a copy was kept in this repository from 2026-01-05 to 2026-09-09 and was never wired into a release build. It was removed because the upstream project publishes no license. Speech synthesis uses the bundled Kokoro service.

## Maintenance

- Adding a sidecar, a vendored directory, or a binary to the desktop bundle requires a row here and an annotation in `REUSE.toml` in the same pull request.
- When re-vendoring `pptx-service`, take upstream by commit hash, not by tag: the `v0.4.0` tag predates the AGPL relicense.
- Copyleft dependencies must be listed even when the code path that uses them is optional; the obligation attaches to distribution, not to execution.
