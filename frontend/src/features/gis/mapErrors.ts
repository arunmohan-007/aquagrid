/**
 * Translation of raw map engine failures into operator-facing text.
 *
 * Kept out of `components/MapView.tsx` so that file exports only its component: a non-component
 * export there disables React Fast Refresh for the whole module, and the map is precisely the
 * surface where losing hot reload hurts — its lifecycle bugs only reproduce after a full
 * construct/style/paint cycle.
 */

/**
 * Rewrites a raw MapLibre failure into something an operator can act on.
 *
 * MapLibre reports every tile failure as `AJAXError: <status text> (<code>): <url>` and nothing
 * else — no response body, no distinction between "the server said no" and "there was no server".
 * That distinction matters more in development than it looks: the Vite proxy answers **500** when
 * it cannot open a connection to the backend at all, so a stopped API is byte-for-byte
 * indistinguishable from a genuine server fault unless the request URL is taken into account.
 * Surfacing the raw string sends the reader off to debug a tile query that was never reached.
 *
 * The status code is always kept in the message, so a real 500 from a running backend is still
 * reported as a 500 and this never becomes a lie — it only adds the hint that explains the common
 * case. An unrecognised message is returned unchanged rather than swallowed.
 */
export function describeMapError(message: string): string {
  const ajax = /AJAXError:\s*.*?\((\d{3})\):\s*(\S+)/.exec(message);
  const [, statusText, url] = ajax ?? [];
  if (statusText === undefined || url === undefined) return message;

  const status = Number(statusText);

  if (url.includes('/basemap/')) {
    return `Base map tiles are unavailable (HTTP ${status}) — check the network connection.`;
  }

  if (url.includes('/api/v1/gis/tiles/')) {
    if (status === 401 || status === 403) {
      return 'Your session has expired — sign in again to load map data.';
    }
    if (status === 404) {
      const layer = /\/tiles\/([^/]+)\//.exec(url)?.[1];
      return layer
        ? `Map layer "${layer}" does not exist on the server.`
        : `Map layer not found (HTTP ${status}).`;
    }
    // 500 lands here from a genuine backend fault *and* from the dev proxy failing to connect.
    return `Cannot reach the AquaGrid API (HTTP ${status}) — check the backend is running on port 8088.`;
  }

  return message;
}
