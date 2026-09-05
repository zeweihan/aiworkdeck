# Plugin guide

Plugins extend AI WorkDeck with extra tools and sidebar panes. They run **in-process** with the host (JAR) or in a sandboxed iframe (web). There is no JVM sandbox — treat a plugin directory as equivalent to running code as the user.

Two shapes:

| Kind | What it is | Start here |
|---|---|---|
| JAR | Java tools on the host classpath | [examples/hello-plugin](../examples/hello-plugin/) |
| Web | Static HTML pane + postMessage bridge | [examples/hello-web-plugin](../examples/hello-web-plugin/) |

The full contract is [PLUGIN_SPEC.md](PLUGIN_SPEC.md). Distribution (submit, review, Ed25519 sign, revoke) is [PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md). Skills (prompt workflows, not JARs) are [SKILL_SPEC.md](SKILL_SPEC.md).

## Install a local plugin

Default scan directory is `plugins/` under the backend working directory:

| Build | Plugin dir |
|---|---|
| Local dev | `backend/plugins/` |
| Desktop app | `~/.aiworkdeck/plugins/` |

Copy the example, restart the backend or hit **Rescan** on the plugin marketplace (`POST /api/plugins/rescan`). New plugins load disabled; enable them on the marketplace page before they appear in the left sidebar. Replacing an already-loaded JAR needs a backend restart — rescan only picks up new metadata.

### JAR example

```bash
mvn -q -f backend/plugin-api/pom.xml install
cd examples/hello-plugin
mvn -q package
mkdir -p ../../backend/plugins/hello-plugin
cp manifest.json target/hello-plugin-1.0.0.jar ../../backend/plugins/hello-plugin/
```

Needs JDK 17+ and Maven. `com.checkba:plugin-api` is not on Maven Central; install it from this repo first.

### Web example

```bash
cp -R examples/hello-web-plugin backend/plugins/
```

No build step. The SDK file `web/awd-plugin-sdk.js` is a copy of [sdk/plugin-sdk/](../sdk/plugin-sdk/) — edit the SDK there, then sync the copies.

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

`id` is required and stable (kebab-case). Duplicate ids: first scan wins. Permissions are the author's declaration, not a runtime grant — the host still enforces them on the bridge.

## Web SDK

Source of truth: [sdk/plugin-sdk/README.md](../sdk/plugin-sdk/README.md).

```html
<script src="awd-plugin-sdk.js"></script>
<script>
  (async function () {
    const ctx = await awd.ready();
    await awd.ui.toast('hi, ' + ctx.pluginId);
  })();
</script>
```

Load the SDK with a synchronous `<script>` before your code. The host sends `init` on iframe `load`; a late listener never sees the handshake.

## Publish

- Local drop-in: copy into `plugins/` (you trust that code).
- Marketplace: [aiworkdeck.com/zh/plugins](https://www.aiworkdeck.com/zh/plugins) or [workdeck.ai/en/plugins](https://www.workdeck.ai/en/plugins). Listings are human-reviewed and signed. See [PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md).
- Skills (no JAR): [SKILL_SPEC.md](SKILL_SPEC.md).

Roadmap for the public plugin API: [PLUGIN_API_ROADMAP.md](PLUGIN_API_ROADMAP.md).
