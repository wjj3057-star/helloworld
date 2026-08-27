/* 오프라인 지원 — 독서실·지하철처럼 망이 없는 곳에서도 열리게 한다.
   앱(APK)은 파일을 안에 담고 있어 원래 오프라인이고, 이건 웹으로 쓸 때를 위한 것이다.

   전략은 "망 먼저, 안 되면 캐시".
   이 앱은 파일 하나가 전부라 캐시 먼저로 두면 고쳐도 반영이 늦는다.
   망이 되면 늘 최신을 받고, 안 되면 마지막으로 받아 둔 것을 쓴다. */
const CACHE = "workbook-scanner-v1";
const ASSETS = ["./", "./index.html", "./manifest.webmanifest", "./icon.svg"];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => c.addAll(ASSETS))
      .catch(() => {})           // 하나라도 못 받으면 조용히 넘어간다
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  // 다른 출처(있을 리 없지만)는 건드리지 않는다
  if (new URL(req.url).origin !== self.location.origin) return;

  e.respondWith(
    fetch(req)
      .then((res) => {
        // 받아온 것을 다음 오프라인을 위해 넣어 둔다
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(req).then((hit) => hit || caches.match("./index.html")))
  );
});
