const CACHE_NAME = 'cineanime-shell-v11';
const CORE_PRECACHE_URLS = [
  './',
  './index.html',
  './about.html',
  './manifest.webmanifest',
  './icon.svg',
  './icon-192.png',
  './icon-512.png'
];
const OPTIONAL_PRECACHE_URLS = [
  './servers.json',
  './vendor/hls.min.js'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(async (cache) => {
      await cache.addAll(CORE_PRECACHE_URLS);
      await Promise.allSettled(OPTIONAL_PRECACHE_URLS.map((u) => cache.add(u)));
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) {
    return;
  }

  const accept = req.headers.get('accept') || '';
  const isNetworkFirst =
    req.mode === 'navigate' ||
    accept.includes('text/html') ||
    url.pathname.endsWith('/manifest.webmanifest') ||
    url.pathname.endsWith('/servers.json');

  if (isNetworkFirst) {
    event.respondWith(
      fetch(req, { cache: 'no-store' })
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, copy)).catch(() => {});
          }
          return res;
        })
        .catch(() =>
          caches.match(req).then((r) => {
            if (r) return r;
            if (req.mode === 'navigate' || accept.includes('text/html')) {
              return caches.match('./index.html');
            }
            return Response.error();
          })
        )
    );
    return;
  }

  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req).then((res) => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, copy)).catch(() => {});
        }
        return res;
      });
    })
  );
});
