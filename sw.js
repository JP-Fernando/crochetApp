// sw.js — Crochet Pattern Guide Service Worker
// Caches the current app shell for offline use.
// PDFs are stored in IndexedDB (not the cache) so they persist across installs.

const CACHE = 'crochet-v2';
const PRECACHE = ['index.html', 'manifest.json', 'sw.js'];
const APP_SHELL = new Set(['/','/index.html','/manifest.json','/sw.js']);

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(PRECACHE))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;

  const isAppShell = e.request.mode === 'navigate' || APP_SHELL.has(url.pathname);
  if (!isAppShell) return;

  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const fresh = await fetch(e.request);
      cache.put(e.request, fresh.clone());
      return fresh;
    } catch (err) {
      const cached = await cache.match(e.request);
      if (cached) return cached;
      throw err;
    }
  })());
});
