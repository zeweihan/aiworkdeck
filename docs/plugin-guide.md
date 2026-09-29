<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Plugin guide

> Originally contributed by @AzazelSensei in #730; refreshed 2026-09-29.

Plugins extend AI WorkDeck with extra tools, sidebar panes, and declarative content. JAR plugins run **in-process** with the host; web plugins run in a sandboxed iframe. There is no JVM sandbox, so treat write access to a plugin directory as equivalent to running code as the user.

Four shapes:

| Kind | What it is | Start here |
|---|---|---|
| JAR | Java tools on the host classpath, optionally using the host SPI (`backend/plugin-api/`) | [examples/hello-plugin](../examples/hello-plugin/) |
| Web | Static HTML pane + postMessage bridge | [examples/hello-web-plugin](../examples/hello-web-plugin/) |
| Declarative | Data only (document templates, style profiles, settings, l10n); no code runs | [examples/hello-declarative-plugin](../examples/hello-declarative-plugin/) |
| Evidence provider | JAR implementing the public evidence-retrieval SPI, with a conformance kit | [examples/hello-evidence-plugin](../examples/hello-evidence-plugin/) |

The full contract is [PLUGIN_SPEC.md](PLUGIN_SPEC.md) (currently v2.10). Distribution (submit, review, Ed25519 sign, revoke) is [PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md). Skills (prompt workflows, not JARs) are [SKILL_SPEC.md](SKILL_SPEC.md).

## Install a local plugin

Default scan directory is `plugins/` under the backend working directory (configurable with `ai.plugins.dir`):

| Build | Plugin dir |
|---|---|
| Local dev | `backend/plugins/` |
| Desktop app | `~/.aiworkdeck/plugins/` |

Copy the plugin directory in, then restart the backend or hit **Rescan** on the plugin marketplace (`POST /api/plugins/rescan`, admin only; on the desktop app the local user is admin). A plugin dropped in by hand is **enabled by default** and shows up in the left sidebar after the rescan; plugins installed from the online marketplace are the ones that arrive disabled until you enable them. Replacing an already-loaded JAR needs a backend restart: rescan only picks up new plugins and metadata, and classes already loaded into the JVM are not unloaded.

### JAR example

```bash
mvn -q -f backend/plugin-api/pom.xml install
cd examples/hello-plugin
mvn -q package
mkdir -p ../../backend/plugins/hello-plugin
cp manifest.json target/hello-plugin-1.0.0.jar ../../backend/plugins/hello-plugin/
```

Needs JDK 21 and Maven (the SPI module `backend/plugin-api` is compiled for Java 21). `com.checkba:plugin-api` is not on Maven Central; install it from this repo first.

### Web example

```bash
cp -R examples/hello-web-plugin backend/plugins/
```

No build step. The SDK file `web/awd-plugin-sdk.js` is a byte-identical copy of [sdk/plugin-sdk/](../sdk/plugin-sdk/): edit the SDK there, then sync the copies.

### Develop a web plugin inside the app

The desktop app has a built-in **plugin development** path that skips review: enable the built-in `plugin-dev` skill in the marketplace, and a plugin-development panel appears in the left sidebar. Plugin source lives in the project folder (`插件开发/<id>/`, "plugin development") where you can edit it and keep version history; installing copies it into `plugins/<id>/`, enables it, and marks the directory with a `.awd-dev` file. The AI can scaffold and install through the same path (`plugin_dev_scaffold` / `plugin_dev_install`).

This path accepts **code-free plugins only** (sandboxed web panes): a manifest with any `backendJars`, `tools`, `skills`, or `packs` is rejected, because JAR code runs with the host's full permissions and must go through review. Dev-installed plugins may call experimental `x-` bridge methods, and an install never overwrites a marketplace-installed directory of the same id.

For browser-only iteration, the plugin template zip from the website ships `dev/host-simulator.html`, a static page that fakes the host bridge with the same iframe sandbox.

## Manifest (minimum)

Each plugin is a directory with `manifest.json`:

```json
{
  "id": "hello-plugin",
  "name": "Hello",
  "version": "1.0.0",
  "permissions": [],
  "tools": [],
  "frontendEntry": null,
  "backendJars": ["hello-plugin-1.0.0.jar"]
}
```

`id` is required and stable (kebab-case); enable/disable state is keyed on it. Duplicate ids: first scan wins. Permissions are the author's declaration, not a runtime grant; the host still enforces them on tool dispatch and on the bridge.

If your plugin uses newer host features, declare `minHostVersion` (semver). An older host still lists the plugin but does not load it, and shows an "upgrade the client" hint instead. Unknown manifest fields are ignored, so newer manifests stay loadable on older hosts.

## Web SDK

Source of truth: [sdk/plugin-sdk/README.md](../sdk/plugin-sdk/README.md). Protocol details: [PLUGIN_SPEC.md](PLUGIN_SPEC.md) section 8.

```html
<script src="awd-plugin-sdk.js"></script>
<script>
  (async function () {
    const ctx = await awd.ready();
    await awd.ui.toast('hi, ' + ctx.pluginId);
  })();
</script>
```

Load the SDK with a synchronous `<script>` before your code. The host sends `init` on iframe `load`; a late listener never sees the handshake, and `ready()` hangs forever.

Every bridge message carries the envelope `awd: 1` (for example `{ awd: 1, type: "init", context: {...} }`, then `call` / `result` pairs). The iframe has no `allow-same-origin`, so the plugin talks to the host only through this bridge. The contract is append-only: new methods get new names, old ones are not changed, and unfinished methods carry an `x-` prefix. Beyond files, storage, and toasts, the bridge covers document read/write (`awd.doc.*`, `editor` permission), host events, theme tokens, AI requests billed to the user's Credits (`awd.ai.request`, `ai` permission), and calling the plugin's own JAR tools. An older host answers unknown methods with `unknown_method`, so plugins should degrade gracefully.

## Publish

- Local drop-in: copy into `plugins/` (you trust that code).
- Marketplace: [aiworkdeck.com/zh/plugins](https://www.aiworkdeck.com/zh/plugins) or [workdeck.ai/en/plugins](https://www.workdeck.ai/en/plugins). Listings are human-reviewed and Ed25519-signed, and marketplace installs are verified file by file and start disabled. See [PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md).
- Skills (no JAR): [SKILL_SPEC.md](SKILL_SPEC.md).

Roadmap for the public plugin API: [PLUGIN_API_ROADMAP.md](PLUGIN_API_ROADMAP.md).
