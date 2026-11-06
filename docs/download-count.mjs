// Public GitHub asset statistics only. No visitor identifiers or click tracking.
const API = 'https://api.github.com/repos/matteoawi/Pause/releases';
const CACHE_KEY = 'pause-apk-downloads-v1';
const CACHE_AGE = 10 * 60 * 1000;

export async function readApkDownloads(fetcher = fetch, signal) {
  let total = 0;
  const seen = new Set();
  for (let page = 1; page <= 20; page++) {
    const response = await fetcher(`${API}?per_page=100&page=${page}`, {
      headers: { Accept: 'application/vnd.github+json' },
      credentials: 'omit', referrerPolicy: 'no-referrer', signal
    });
    if (!response.ok) throw new Error('GitHub download statistics unavailable');
    const releases = await response.json();
    if (!Array.isArray(releases) || releases.length > 100) throw new Error('Invalid release data');
    for (const release of releases) {
      if (!release || typeof release.draft !== 'boolean' || typeof release.prerelease !== 'boolean' || !Array.isArray(release.assets)) throw new Error('Invalid release data');
      if (release.draft || release.prerelease) continue;
      for (const asset of release.assets) {
        if (asset?.name !== 'Pause.apk' || asset.state !== 'uploaded') continue;
        if (!Number.isSafeInteger(asset.id) || asset.id <= 0 || !Number.isSafeInteger(asset.download_count) || asset.download_count < 0) throw new Error('Invalid download count');
        if (!seen.has(asset.id)) {
          seen.add(asset.id);
          total += asset.download_count;
          if (!Number.isSafeInteger(total)) throw new Error('Download count out of range');
        }
      }
    }
    if (releases.length < 100) return total;
  }
  // Never present a truncated release history as a complete total.
  throw new Error('Release history exceeds the supported page limit');
}

function cachedCount() {
  try {
    const value = JSON.parse(sessionStorage.getItem(CACHE_KEY));
    const age = Date.now() - value?.checkedAt;
    if (Number.isSafeInteger(value?.total) && value.total >= 0 && Number.isFinite(age) && age >= 0 && age < CACHE_AGE) return value.total;
  } catch { /* Storage can be disabled. The public counter still works. */ }
  return null;
}

async function showDownloads(element) {
  const render = total => { element.textContent = `${new Intl.NumberFormat('en').format(total)} APK downloads`; };
  const cached = cachedCount();
  if (cached !== null) { render(cached); return; }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const total = await readApkDownloads(fetch, controller.signal);
    render(total);
    try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ total, checkedAt: Date.now() })); } catch { /* Optional cache. */ }
  } catch {
    // Keep the existing source link; don't invent zero or block downloading.
  } finally {
    clearTimeout(timeout);
  }
}

if (typeof document !== 'undefined') {
  const element = document.querySelector('[data-download-count]');
  if (element) showDownloads(element);
}
