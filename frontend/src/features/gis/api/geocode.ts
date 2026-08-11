/**
 * Nominatim (OpenStreetMap) geocoding client.
 *
 * Requests go same-origin through the `/geocode` proxy (see vite.config.ts and the matching nginx
 * location). That is deliberate for three reasons:
 *
 * 1. Nominatim's usage policy asks for ≤1 request/second and an identifying User-Agent. A browser
 *    cannot set a custom User-Agent, but a proxy can — and the proxy is where rate limits are best
 *    enforced for a multi-operator deployment.
 * 2. It removes CORS from the equation entirely.
 * 3. A browser on a managed network may silently drop requests to a third party while the same URL
 *    fetches cleanly from a terminal. Same-origin requests cannot be filtered that way.
 *
 * No API key is required.
 */

export interface GeocodeResult {
  /** Display name, already formatted by Nominatim (e.g. "Chennai, Tamil Nadu, India"). */
  name: string;
  /** Longitude (WGS84). Nominatim returns these as strings; parsed here. */
  lon: number;
  /** Latitude (WGS84). */
  lat: number;
  /** Nominatim's result category (city, residential, water, …) — for a small caption. */
  type?: string;
}

interface NominatimRaw {
  display_name?: string;
  lon?: string | number;
  lat?: string | number;
  type?: string;
}

function toNumber(value: string | number | undefined): number | undefined {
  if (value === undefined) return undefined;
  const n = typeof value === 'number' ? value : Number.parseFloat(value);
  return Number.isFinite(n) ? n : undefined;
}

/**
 * Forward geocode: place name → coordinates.
 *
 * Throws on anything that is not a `2xx` with the expected shape, so the caller (a react-query
 * queryFn) can surface the failure rather than silently rendering an empty result list.
 */
export async function geocodeSearch(query: string, signal?: AbortSignal): Promise<GeocodeResult[]> {
  const term = query.trim();
  if (term.length < 2) return [];

  const url = `/geocode/search?${new URLSearchParams({
    q: term,
    format: 'json',
    limit: '6',
    addressdetails: '0',
  }).toString()}`;

  // Build init conditionally so an absent signal is not passed as `undefined` (rejected under
  // exactOptionalPropertyTypes) and react-query can still cancel in flight.
  const init: RequestInit = { method: 'GET', headers: { Accept: 'application/json' } };
  if (signal) init.signal = signal;

  const response = await fetch(url, init);
  if (!response.ok) {
    throw new Error(`Geocode request failed (${response.status})`);
  }
  const payload = (await response.json()) as NominatimRaw[];

  const results: GeocodeResult[] = [];
  for (const item of payload) {
    const lon = toNumber(item.lon);
    const lat = toNumber(item.lat);
    const name = item.display_name?.trim();
    if (lon === undefined || lat === undefined || !name) continue;
    // Only carry `type` when Nominatim provided one, so the result stays exact-optional-clean.
    const result: GeocodeResult = { name, lon, lat };
    if (item.type) result.type = item.type;
    results.push(result);
  }
  return results;
}

/**
 * Parses a `"lat, lng"` (or `"lat lng"`) string into a coordinate, or `null` if it is not a valid
 * pair within the WGS84 range.
 *
 * Tolerates spaces, commas, and either `lat, lng` / `lng, lat` ordering ambiguity by checking the
 * ranges: if the first number is outside ±90 it is treated as longitude.
 */
export function parseCoordinate(input: string): { lon: number; lat: number } | null {
  const parts: number[] = input
    .split(/[\s,;]+/)
    .map((p) => Number.parseFloat(p))
    .filter((n) => Number.isFinite(n));
  if (parts.length !== 2) return null;
  let lat = parts[0]!;
  let lon = parts[1]!;
  // If the first value cannot be a latitude, assume the user typed `lng, lat`.
  if (Math.abs(lat) > 90) {
    const swap = lat;
    lat = lon;
    lon = swap;
  }
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return { lat, lon };
}
