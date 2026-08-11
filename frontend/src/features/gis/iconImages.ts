import type { Map as MlMap } from 'maplibre-gl';
import { tokenStore } from '@/lib/auth/tokenStore';
import { MARKER_IMAGE_PREFIX, MARKER_SHAPES, markerImage } from './markerShapes';

/**
 * Registers every icon a composed style needs, before the layers referencing them are added.
 *
 * <p>MapLibre draws nothing for a missing image and reports no error, so an icon that fails to load
 * is a point layer that silently stops appearing — the hardest kind of styling bug to see. The
 * server therefore tells the client exactly which icons a layer needs (`requiredIcons`), and this
 * fetches and registers them before `addLayer` runs.
 *
 * There are three kinds, all registered under the same `ag-` prefix so the composer needs no branch:
 *
 * - **Built-in shapes** (`ag-circle`, `ag-diamond`…) — drawn on a canvas here, no download.
 * - **Library icons** (`ag-lib-water`) — free vendored SVG from Mapbox Maki and Google Material,
 *   fetched from the API.
 * - **Uploaded symbols** (`ag-sym-<uuid>`) — the tenant's own files.
 *
 * ## Why the SVG is rasterised rather than injected
 *
 * Uploaded SVG is user content served from this origin, which makes it a document with the
 * application's privileges rather than a picture. Loading it through `new Image()` onto a canvas is
 * what the HTML specification calls *secure static mode*: no script runs, no external resource is
 * fetched, no declarative animation. That property holds regardless of what the file contains, which
 * is why it is the strongest of the three layers guarding these uploads — the server-side sanitiser
 * and the response CSP being the other two.
 */

/** Rasterisation size. Large enough that `icon-size` can scale up without visible softness. */
const RASTER_SIZE = 64;

/**
 * Icons already registered on a given map, so a style reload does not refetch them.
 *
 * Keyed by the map instance rather than globally: the console's map and a preview map are separate
 * MapLibre instances with separate image atlases, and an id registered on one is absent on the
 * other.
 */
const registered = new WeakMap<MlMap, Set<string>>();

/**
 * Ensures the built-in shapes exist on this map.
 *
 * Called on every `style.load`, because a base-map switch tears down the image atlas along with
 * everything else — a point layer styled as a diamond would otherwise come back empty after
 * switching to Satellite, with nothing to explain it.
 */
export function ensureMarkerImages(map: MlMap): void {
  for (const shape of MARKER_SHAPES) {
    const id = `${MARKER_IMAGE_PREFIX}${shape}`;
    if (map.hasImage(id)) continue;
    const image = markerImage(shape);
    if (!image) return;
    map.addImage(id, image, { sdf: true });
  }
}

/**
 * Fetches and registers the library and uploaded icons a composed style references.
 *
 * Asynchronous and idempotent. Callers await it before adding layers; a second call for an icon
 * already present is a no-op, so a style reload costs nothing.
 *
 * A single icon that fails to load does not reject the whole batch — one broken upload should cost
 * its own marker, not every other layer's.
 */
export async function ensureStyleIcons(map: MlMap, iconIds: readonly string[]): Promise<void> {
  const done = registered.get(map) ?? new Set<string>();
  registered.set(map, done);

  const wanted = [...new Set(iconIds)].filter((icon) => {
    const imageId = `${MARKER_IMAGE_PREFIX}${icon}`;
    return !done.has(imageId) && !map.hasImage(imageId);
  });
  if (wanted.length === 0) return;

  await Promise.all(
    wanted.map(async (icon) => {
      const imageId = `${MARKER_IMAGE_PREFIX}${icon}`;
      try {
        const image = await loadIcon(icon);
        // The map may have been torn down while the fetch was in flight — a preview dialog closing
        // mid-load is the ordinary case, not an error worth reporting.
        if (!image || map.hasImage(imageId)) return;
        /*
         * Registered as SDF so `icon-color` tints it, which is what lets a classified expression
         * colour the same glyph green for in-service and red for faulty. A symbol uploaded as
         * full-colour is registered non-SDF instead; the server records which, and the icon id
         * carries no hint of it, so that flag is read from the symbol list below.
         */
        map.addImage(imageId, image, { sdf: sdfFor(icon) });
        done.add(imageId);
      } catch (cause) {
        // eslint-disable-next-line no-console
        console.error(`[map] icon ${icon} could not be registered`, cause);
      }
    }),
  );
}

/**
 * Whether an icon should be registered as a tintable silhouette.
 *
 * Library icons always are — they are single-colour glyphs by design. Uploaded ones follow the
 * choice their uploader made, which {@link setUploadedSymbolTinting} records when the symbol list
 * loads. Defaulting to `true` for an unknown id matches the upload form's own default, so a symbol
 * registered before its metadata arrives still tints rather than rendering as an untinted black
 * silhouette.
 */
function sdfFor(icon: string): boolean {
  if (icon.startsWith('lib-')) return true;
  return uploadedTinting.get(icon) ?? true;
}

const uploadedTinting = new Map<string, boolean>();

/**
 * Records which uploaded symbols are tintable.
 *
 * Fed from the symbol list so {@link ensureStyleIcons} does not need a second request per icon just
 * to learn one boolean.
 */
export function setUploadedSymbolTinting(symbols: readonly { iconName: string; tintable: boolean }[]): void {
  symbols.forEach((symbol) => uploadedTinting.set(symbol.iconName, symbol.tintable));
}

/** Fetches an icon's bytes and rasterises them to an `ImageData` MapLibre can register. */
async function loadIcon(icon: string): Promise<ImageData | null> {
  const url = icon.startsWith('lib-')
    ? `/api/v1/layer-styles/library-icons/${encodeURIComponent(icon.slice('lib-'.length))}/content`
    : `/api/v1/map-symbols/${encodeURIComponent(icon.slice('sym-'.length))}/content`;

  /*
   * Fetched with the bearer token rather than handed straight to `Image.src`.
   *
   * An `<img>` request carries cookies but not an Authorization header, and these endpoints are
   * gated on `gis:style:read` — so the direct-src version 401s. Fetching first and converting to a
   * blob URL keeps the credential in a header where it belongs and still hands the browser an image
   * to decode in secure static mode.
   */
  const token = tokenStore.get();
  const response = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) return null;

  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  try {
    return await rasterise(objectUrl);
  } finally {
    // Revoked as soon as the bitmap exists; leaking these accumulates for the life of the tab.
    URL.revokeObjectURL(objectUrl);
  }
}

/** Draws a URL onto a canvas at {@link RASTER_SIZE}, preserving aspect ratio and centring it. */
function rasterise(url: string): Promise<ImageData | null> {
  return new Promise((resolve) => {
    const image = new Image();
    image.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = RASTER_SIZE;
      canvas.height = RASTER_SIZE;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        resolve(null);
        return;
      }
      /*
       * Fit inside the square rather than stretch to it. A wide glyph stretched to a square is a
       * glyph nobody recognises, and recognisability is the entire reason someone uploaded their own
       * symbol instead of using a circle.
       */
      const natural = Math.max(image.width || RASTER_SIZE, image.height || RASTER_SIZE);
      const scale = RASTER_SIZE / natural;
      const width = (image.width || RASTER_SIZE) * scale;
      const height = (image.height || RASTER_SIZE) * scale;
      ctx.drawImage(image, (RASTER_SIZE - width) / 2, (RASTER_SIZE - height) / 2, width, height);
      resolve(ctx.getImageData(0, 0, RASTER_SIZE, RASTER_SIZE));
    };
    // A file that will not decode resolves null rather than rejecting: the caller logs one icon's
    // failure and carries on with the rest.
    image.onerror = () => resolve(null);
    image.src = url;
  });
}
