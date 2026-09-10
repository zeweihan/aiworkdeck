<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# AI WorkDeck E2E and macOS desktop test environment

This is a local-only recipe for the `codex/ai-codex-alignment-559` worktree. It creates
test state only under `/tmp`; it neither starts the installed app nor reads or changes its
account, credentials, or user data. Run the commands in order. The Electron suites open
visible macOS windows and close them themselves.

## Inventory recorded 2026-09-10

| Item | Available path/version | Result for this worktree |
| --- | --- | --- |
| Node/npm | Node `v22.22.3`, npm `10.9.8` | suitable; frontend and desktop both have `package-lock.json` |
| JDK | `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home` (21.0.9 arm64) | required explicitly; do not rely on Maven's selected JDK |
| Browser | `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` | used by Puppeteer/LOWA by default |
| Installed app | `/Applications/AI WorkDeck.app`, version `0.38.1` | baseline only; it is not this worktree's candidate build |
| Installed LOWA | `/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/lowa` | usable as a local engine source after rebuilding `dist/zetaoffice` |
| Worktree dependencies/assets | `frontend/node_modules`, `desktop/node_modules`, `frontend/dist/zetaoffice`, and `backend/target/*.jar` were absent | install/build them below |

The standard test ports `5174`, `9797`, `9899`, `5188`, and `9848` were free when
inventoried. The scripts choose CDP ports themselves; keep the suites sequential so their
server ports do not collide.

## Build and deterministic checks

```bash
export AWD_REPO='/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.worktrees/ai-codex-alignment-559'
export JAVA_HOME='/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export PUPPETEER_EXECUTABLE_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
export AWD_LOWA_ENGINE='/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/lowa'
cd "$AWD_REPO/frontend" && npm ci
cd "$AWD_REPO/desktop" && npm ci
cd "$AWD_REPO/backend" && mvn -B test
cd "$AWD_REPO/frontend" && npm run check:emits && npm run check:locales && npm run check:nav:full && npm run test:project-home && npm run test:commands
cd "$AWD_REPO/desktop" && npm test
```

The frontend package exposes the following test commands. The first group is fully local
and belongs with the deterministic checks above; the LOWA and Electron groups are run in
the later sections because they need an engine, an H5 server, or a local backend.

| Group | Commands |
| --- | --- |
| Local Node tests | `test:tag-protocol`, `test:inline-review`, `test:completion`, `test:lowa-unit`, `test:project-home`, `test:auth-redirect`, `test:commands`, `test:zeta-relay`, `test:evidence`, `test:plugin-sdk`, `test:panel-dock`, `test:tab-visibility`, `test:insight`, `test:clipboard`, `test:revision-view`, `test:md-table`, `test:ocr-overlay`, `test:capabilities`, `test:team`, `test:member-invite`, `test:optional-components`, `test:desensitize` |
| LOWA engine tests | `test:lowa-e2e`, `test:lowa-big`, `test:lowa-inline-review`, `test:writing-ui`, `test:writing-caret`, `test:lowa-link-preview`, `test:lowa-completion`, `test:lowa-generated`, `test:lowa-reply` |
| App and real desktop tests | `test:app-e2e`, `test:desktop-e2e`, `test:feedback-e2e`, `test:meeting-e2e`, `test:writing-desktop` |

To run every remaining local Node test rather than only the CI subset:

```bash
cd "$AWD_REPO/frontend"
for AWD_TEST in test:tag-protocol test:inline-review test:completion test:lowa-unit test:project-home test:auth-redirect test:commands test:zeta-relay test:evidence test:plugin-sdk test:panel-dock test:tab-visibility test:insight test:clipboard test:revision-view test:md-table test:ocr-overlay test:capabilities test:team test:member-invite test:optional-components test:desensitize; do
  npm run "$AWD_TEST" || exit $?
done
```

Build the H5 and editor bundle next. `build:zetaoffice` deletes `dist/zetaoffice`, so copy
the engine only after that command. `ditto` reads the installed app but does not alter it.

```bash
cd "$AWD_REPO/frontend"
npm run build:h5
npm run build:zetaoffice
ditto "$AWD_LOWA_ENGINE" "$AWD_REPO/frontend/dist/zetaoffice/lowa"
cd "$AWD_REPO/backend"
mvn -B -DskipTests package
export AWD_JAR="$(find "$AWD_REPO/backend/target" -maxdepth 1 -name '*.jar' ! -name '*sources*' -print -quit)"
test -n "$AWD_JAR" && test -f "$AWD_JAR"
```

## Isolated local backend and H5 server

This backend is the primary target for app, desktop, and feedback E2E. Its H2 database,
project files, license ticket, copied skills, and plugins all live under one disposable
temporary directory. The ticket is the documented legacy trial state used by the tests; do
not enable the retired trial-code switch.

```bash
export AWD_E2E_ROOT="$(mktemp -d /tmp/aiworkdeck-e2e.XXXXXX)"
mkdir -p "$AWD_E2E_ROOT/home/.aiworkdeck" "$AWD_E2E_ROOT/backend"
cp "$AWD_JAR" "$AWD_E2E_ROOT/backend.jar"
cp -R "$AWD_REPO/backend/skills" "$AWD_E2E_ROOT/skills"
cp -R "$AWD_REPO/backend/plugins" "$AWD_E2E_ROOT/backend/plugins"
cat > "$AWD_E2E_ROOT/home/.aiworkdeck/license.json" <<'EOF'
{ "mode": "trial", "code": "AWD-T-SEEDED-FOR-E2E", "activatedAt": "2026-08-18T00:00:00Z", "lastVerifiedAt": "2026-08-18T00:00:00Z" }
EOF
chmod 600 "$AWD_E2E_ROOT/home/.aiworkdeck/license.json"
(
  cd "$AWD_E2E_ROOT/backend"
  SECURITY_BROWSER_PROXY_E2E_ALLOWED_HOSTS=127.0.0.1 \
  AI_SKILLS_BUILTIN_DIR="$AWD_E2E_ROOT/skills" "$JAVA_HOME/bin/java" \
    -Duser.home="$AWD_E2E_ROOT/home" -jar "$AWD_E2E_ROOT/backend.jar" \
    --server.port=9797 --spring.profiles.active=desktop \
    '--spring.datasource.url=jdbc:h2:file:'"$AWD_E2E_ROOT"'/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE'
) > "$AWD_E2E_ROOT/backend.log" 2>&1 &
export AWD_E2E_BACKEND_PID=$!
until curl -fsS http://127.0.0.1:9797/api/auth/me >/dev/null; do sleep 1; done

(
  cd "$AWD_REPO/frontend"
  VITE_API_BASE_URL=http://127.0.0.1:9797 npx uni --port 5174
) > "$AWD_E2E_ROOT/vite.log" 2>&1 &
export AWD_E2E_VITE_PID=$!
until curl -fsS http://127.0.0.1:5174 >/dev/null; do sleep 1; done
```

The copied `skills` and `plugins` matter: a bare JAR launched from an isolated current
directory otherwise returns an empty `/api/skills/list`, which makes skill-dependent UI
steps look like product failures.

## Full E2E sequence

Run these one at a time from `frontend/`; every Electron command creates a visible dev
Electron instance with a temporary Electron profile and injects port `9797` into the
renderer. The installed 0.38.1 app can remain open.

```bash
cd "$AWD_REPO/frontend"

# Real LOWA/Chrome: keyboard, IME, revisions, toolbar, export, evidence anchors.
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:lowa-e2e

# LOWA changes adjacent to AI writing behavior.
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:lowa-inline-review
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:writing-ui
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:writing-caret
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:lowa-link-preview
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:lowa-completion
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:lowa-generated
LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" LOWA_E2E_PORT=8901 npm run test:lowa-reply

# Browser app journey, including J11 collaboration and J13 signed local pack fixture.
# AI_E2E=0 is the deterministic no-provider run; omit it only for an authorized real-model run.
JAVA_HOME="$JAVA_HOME" APP_E2E_BASE=http://127.0.0.1:5174 APP_E2E_BACKEND=http://127.0.0.1:9797 APP_E2E_JAR="$AWD_JAR" AI_E2E=0 npm run test:app-e2e

# Visible native desktop path: real LOWA webview -> save -> API download verification;
# also covers desktop-only file UI and BrowserView lifecycle.
APP_E2E_BACKEND=http://127.0.0.1:9797 DESKTOP_E2E_DEVURL=http://127.0.0.1:5174 npm run test:desktop-e2e

# Visible feedback popup: native region screenshot, Chromium synthetic microphone,
# backend attachment persistence and byte readback.
APP_E2E_BACKEND=http://127.0.0.1:9797 FEEDBACK_E2E_DEVURL=http://127.0.0.1:5174 npm run test:feedback-e2e

# Visible meeting-recording path. It starts and cleans up its own isolated backend on 9899.
JAVA_HOME="$JAVA_HOME" MEETING_E2E_JAR="$AWD_JAR" MEETING_E2E_DEVURL=http://127.0.0.1:5174 npm run test:meeting-e2e
```

For an editor-wide performance regression, install `python-docx` and `pillow` in a local
Python environment, then run `LOWA_ENGINE_DIR="$AWD_LOWA_ENGINE" npm run test:lowa-big`.
It generates a 150-page fixture in the system temporary directory and takes about six
minutes.

`test:writing-desktop` is a stricter visible Electron smoke test for completions, inline
review, citations, and link previews. It refuses to run unless both its Java and Electron
profiles are isolated. Prepare a second fixture with the same pattern, but use port 9848
and the names the guard expects:

```bash
export WRITING_E2E_ROOT="$(mktemp -d /tmp/aiworkdeck-writing-e2e.XXXXXX)"
mkdir -p "$WRITING_E2E_ROOT/home/.aiworkdeck" "$WRITING_E2E_ROOT/backend" "$WRITING_E2E_ROOT/frontend/dist"
cp "$AWD_JAR" "$WRITING_E2E_ROOT/backend.jar"
cp -R "$AWD_REPO/backend/skills" "$WRITING_E2E_ROOT/skills"
cp -R "$AWD_REPO/backend/plugins" "$WRITING_E2E_ROOT/backend/plugins"
cp -R "$AWD_REPO/frontend/dist/zetaoffice" "$WRITING_E2E_ROOT/frontend/dist/zetaoffice"
cp "$AWD_E2E_ROOT/home/.aiworkdeck/license.json" "$WRITING_E2E_ROOT/home/.aiworkdeck/license.json"
(
  cd "$WRITING_E2E_ROOT/backend"
  AI_SKILLS_BUILTIN_DIR="$WRITING_E2E_ROOT/skills" "$JAVA_HOME/bin/java" \
    -Duser.home="$WRITING_E2E_ROOT/home" -jar "$WRITING_E2E_ROOT/backend.jar" \
    --server.port=9848 --spring.profiles.active=desktop \
    '--spring.datasource.url=jdbc:h2:file:'"$WRITING_E2E_ROOT"'/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE'
) > "$WRITING_E2E_ROOT/backend.log" 2>&1 &
export WRITING_E2E_BACKEND_PID=$!
until curl -fsS http://127.0.0.1:9848/api/auth/me >/dev/null; do sleep 1; done
cd "$AWD_REPO/frontend"
WRITING_E2E_ROOT="$WRITING_E2E_ROOT" WRITING_E2E_EDITOR_DIST="$WRITING_E2E_ROOT/frontend/dist/zetaoffice" APP_E2E_BACKEND=http://127.0.0.1:9848 DESKTOP_E2E_DEVURL=http://127.0.0.1:5174 npm run test:writing-desktop
```

## Provider boundaries and real-model checks

| Test | Provider behavior | Credentials/network |
| --- | --- | --- |
| LOWA, desktop, feedback, meeting | No LLM request. Meeting selects `OLLAMA` only to pass the setup enum and validates the recorded-state fallback without Tunwu/OSS. | deterministic; no model credentials |
| App E2E with `AI_E2E=0` | Skips the AI-send and English AI-process-card assertions; retains UI, J11 collaboration, and J13 local signed-pack coverage. | deterministic; no model credentials |
| App E2E without `AI_E2E=0` | Sends one real UI message through the configured backend provider (the suite initializes `OPENROUTER` when needed). | requires an already authorized/configured provider; do not put a key in a command or test fixture |
| `RealLlmSmokeTest` | Real OpenRouter tool-selection smoke, default model `deepseek/deepseek-v4-flash`. | `OPENROUTER_API_KEY` supplied from the approved secret location |
| `RealVisionSmokeTest` | Real OpenRouter image request, default model `qwen/qwen3.7-flash`. | same approved key |
| `AllowedModelsLiveContractTest` | Public OpenRouter catalogue/price and tool/vision-capability contract. | `RUN_LIVE_MODEL_CHECK=1`; network required, no API key specified by this test |

With authorization and a key already present in the shell's approved secret source, the real
smokes are:

```bash
cd "$AWD_REPO/backend"
JAVA_HOME="$JAVA_HOME" OPENROUTER_API_KEY="$OPENROUTER_API_KEY" mvn -B test -Dtest=RealLlmSmokeTest,RealVisionSmokeTest
JAVA_HOME="$JAVA_HOME" RUN_LIVE_MODEL_CHECK=1 mvn -B test -Dtest=AllowedModelsLiveContractTest
```

## Real macOS desktop acceptance

The three Electron suites above are the candidate-build desktop test: they invoke the
worktree's Electron binary, show actual macOS windows, use a real LOWA webview, and retain
screenshots/logs under `/tmp`. During `test:desktop-e2e`, `test:feedback-e2e`,
`test:meeting-e2e`, and `test:writing-desktop`, visually confirm that the test window opens,
receives typing/clicking, shows the editor or overlay when expected, and closes at the end.
This validates the current worktree; launching `/Applications/AI WorkDeck.app` only validates
the installed 0.38.1 baseline.

After all suites finish, stop only the two PIDs created by this recipe:

```bash
kill "$AWD_E2E_VITE_PID" "$AWD_E2E_BACKEND_PID" "$WRITING_E2E_BACKEND_PID"
```
