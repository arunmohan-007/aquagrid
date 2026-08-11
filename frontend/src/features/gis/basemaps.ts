import type { StyleSpecification } from 'maplibre-gl';

/**
 * Base map styles, defined inline as raster sources.
 *
 * No style URL and no API key. Most hosted vector styles (MapTiler, Mapbox, Stadia) require a
 * key, and a key that has to be provisioned before the map draws anything is the single most
 * common reason a GIS page ships broken. Raster tiles from OSM and Esri need neither, so the
 * viewer renders on a clean checkout with nothing configured.
 *
 * The trade-off is honest: raster tiles do not restyle or rotate labels the way vector tiles do.
 * When this deployment gets a tile subscription, swapping `style` for a URL is a one-line change
 * here and nothing else moves.
 */

const OSM_ATTRIBUTION = '© OpenStreetMap contributors';
const ESRI_ATTRIBUTION = 'Imagery © Esri, Maxar, Earthstar Geographics';

export type BaseMapId = 'street' | 'satellite' | 'hybrid';

/** UI metadata only. The style is fetched separately via {@link baseMapStyle}. */
export interface BaseMapDef {
  id: BaseMapId;
  title: string;
  caption: string;
  attribution: string;
}

/*
 * Every tile URL is same-origin and proxied — see the `/basemap/*` entries in vite.config.ts,
 * and the matching nginx rules for deployment.
 *
 * The browser must not talk to tile.openstreetmap.org or arcgisonline.com directly. On a managed
 * network those requests can stall silently: MapLibre reports no error, never completes its first
 * render, and the map stays blank with nothing to debug.
 */

/** Esri's imagery service, used by both the satellite and hybrid styles. */
const esriImageryTiles = ['/basemap/esri-imagery/{z}/{y}/{x}'];

/** Place names and boundaries, transparent, drawn over imagery in the hybrid style. */
const esriReferenceTiles = ['/basemap/esri-reference/{z}/{y}/{x}'];

function rasterStyle(
  sources: { id: string; tiles: string[]; attribution: string; maxzoom?: number }[],
): StyleSpecification {
  return {
    version: 8,
    /*
     * Glyphs, added when Layer Style Management made labels configurable.
     *
     * These raster styles have no symbol layers of their own — place names are baked into the tiles
     * — so this was deliberately omitted, and omitting it was right at the time. It stops being
     * right the moment an administrator switches labels on for a layer: a symbol layer with no glyph
     * source does not error, it simply draws nothing, which is indistinguishable from the labels
     * being off and is the worst possible feedback for a setting someone just changed.
     *
     * Proxied through the same `/basemap/*` route as the tiles, for the same three reasons — a
     * managed network can silently drop third-party requests, CORS disappears, and the browser only
     * ever talks to this origin, which is what the deployed Content-Security-Policy requires. The
     * upstream is MapLibre's own font endpoint: no API key, and the same "renders on a clean
     * checkout with nothing configured" property the rest of this file is built around.
     */
    glyphs: '/basemap/fonts/{fontstack}/{range}.pbf',
    sources: Object.fromEntries(
      sources.map((source) => [
        source.id,
        {
          type: 'raster' as const,
          tiles: source.tiles,
          tileSize: 256,
          maxzoom: source.maxzoom ?? 19,
          attribution: source.attribution,
        },
      ]),
    ),
    layers: sources.map((source) => ({
      id: `${source.id}-layer`,
      type: 'raster' as const,
      source: source.id,
    })),
  };
}

export const BASE_MAPS: BaseMapDef[] = [
  {
    id: 'street',
    title: 'Street',
    caption: 'OpenStreetMap — roads, names and landmarks',
    attribution: OSM_ATTRIBUTION,
  },
  {
    id: 'satellite',
    title: 'Satellite',
    caption: 'Esri World Imagery — no labels',
    attribution: ESRI_ATTRIBUTION,
  },
  {
    id: 'hybrid',
    title: 'Hybrid',
    caption: 'Imagery with place names and boundaries',
    attribution: ESRI_ATTRIBUTION,
  },
];

/** Source sets per base map, kept private so nothing can hold a reference to a live style. */
const SOURCE_SETS: Record<BaseMapId, { id: string; tiles: string[]; attribution: string }[]> = {
  street: [
    {
      id: 'osm',
      tiles: ['/basemap/osm/{z}/{x}/{y}.png'],
      attribution: OSM_ATTRIBUTION,
    },
  ],
  satellite: [{ id: 'esri', tiles: esriImageryTiles, attribution: ESRI_ATTRIBUTION }],
  hybrid: [
    { id: 'esri', tiles: esriImageryTiles, attribution: ESRI_ATTRIBUTION },
    { id: 'esri-ref', tiles: esriReferenceTiles, attribution: ESRI_ATTRIBUTION },
  ],
};

/**
 * Builds a **fresh** style object every call. This is not defensive tidiness — it is required.
 *
 * MapLibre takes ownership of the style object it is handed and mutates it in place while
 * loading. Under React StrictMode every effect mounts, unmounts and mounts again, so a shared
 * module-level style is handed to a second map already dirtied and discarded by the first. The
 * result is the worst kind of failure: the constructor succeeds, no error event fires, the canvas
 * is sized correctly, and nothing is ever painted.
 */
export function baseMapStyle(id: BaseMapId): StyleSpecification {
  return rasterStyle(SOURCE_SETS[id] ?? SOURCE_SETS.street);
}

export function baseMapById(id: BaseMapId): BaseMapDef {
  return BASE_MAPS.find((b) => b.id === id) ?? BASE_MAPS[0]!;
}
