// COI service worker：给静态托管（加不了响应头的场景）兜底注入 COOP/COEP，
// 让探针页拿到 crossOriginIsolated。仅探针使用；正式部署用 nginx 头。
// 思路同社区通用的 coi-serviceworker（MIT）。
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.cache === 'only-if-cached' && req.mode !== 'same-origin') return;
  event.respondWith(
    fetch(req).then((res) => {
      if (res.status === 0) return res;
      const headers = new Headers(res.headers);
      headers.set('Cross-Origin-Opener-Policy', 'same-origin');
      headers.set('Cross-Origin-Embedder-Policy', 'require-corp');
      return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
    }).catch(() => fetch(req))
  );
});
