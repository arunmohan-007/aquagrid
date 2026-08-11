import { useEffect, useRef } from 'react';
import { Box, Typography } from '@mui/material';
import { Map as MlMap, setWorkerUrl } from 'maplibre-gl';
import type { AddLayerObject, SourceSpecification } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { tokenStore } from '@/lib/auth/tokenStore';
import { baseMapStyle } from '@/features/gis/basemaps';
import { ensureMarkerImages, ensureStyleIcons } from '@/features/gis/iconImages';
import { mapChrome } from '@/features/gis/mapTheme';
import type { ComposedMapLayer } from '../types';

// Same reason as MapView: MapLibre v6 derives its worker URL from `import.meta.url`, which a bundler
// resolves to a path that 404s — no worker is spawned, tiles are fetched and never decoded, and the
// map stays blank with no error. Pointing at it explicitly, before any Map is constructed, fixes it.
setWorkerUrl(maplibreWorkerUrl);

interface Props {
  /** The composed rendering instruction to draw. Null while a preview is in flight. */
  composed: ComposedMapLayer | null | undefined;
  /** `[minLon, minLat, maxLon, maxLat]` in EPSG:4326 — the layer's own extent. */
  extent: [number, number, number, number] | null | undefined;
  height?: number;
}

/**
 * A small MapLibre canvas showing one layer drawn with one style.
 *
 * Used twice: as the layer preview in the registry, and as the live style preview in the style
 * editor. Both are the same question — "what will this look like" — and both are answered by drawing
 * the same composed specification the real map draws, against the same tiles, so the preview is the
 * product rather than a picture of it.
 *
 * A separate map instance rather than a second view of the console's: this one is small, disposable
 * and re-created whenever the style changes, and sharing the console's map would mean an
 * administrator's half-finished style briefly becoming the map everyone else is looking at.
 */
export function LayerPreviewMap({ composed, extent, height = 280 }: Props) {
  const holder = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MlMap | null>(null);
  const readyRef = useRef(false);
  const composedRef = useRef(composed);
  composedRef.current = composed;
  const extentRef = useRef(extent);
  extentRef.current = extent;

  // --- Create once ----------------------------------------------------------------------------
  useEffect(() => {
    if (mapRef.current || !holder.current) return;
    const container = holder.current;

    let map: MlMap;
    try {
      map = new MlMap({
        container,
        style: baseMapStyle('street'),
        center: [78.14, 11.66],
        zoom: 9,
        attributionControl: false,
        // Same-origin through the proxy; the header is attached here so the token never lands in a
        // cacheable URL.
        transformRequest: (url) => {
          if (url.includes('/api/v1/gis/tiles/')) {
            const token = tokenStore.get();
            return token ? { url, headers: { Authorization: `Bearer ${token}` } } : { url };
          }
          return { url };
        },
      });
    } catch {
      // A blocked WebGL context is a browser where the console's own map is not running either.
      // Failing quietly here leaves the surrounding form usable rather than unmounting it.
      return;
    }
    mapRef.current = map;

    map.on('style.load', () => {
      readyRef.current = true;
      apply(map, composedRef.current, extentRef.current);
    });
    // The container is inside a dialog that animates open, so it may still be 0×0 when the map is
    // constructed. A nudge across the next few frames is what makes it paint rather than sit
    // "loaded but never rendered" — the same failure MapView guards against.
    [0, 60, 300].forEach((delay) => window.setTimeout(() => mapRef.current?.resize(), delay));

    const observer = new ResizeObserver(() => map.resize());
    observer.observe(container);

    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
      readyRef.current = false;
    };
  }, []);

  // --- Re-apply whenever the composed style changes --------------------------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !readyRef.current) return;
    apply(map, composed, extent);
  }, [composed, extent]);

  return (
    <Box sx={{ position: 'relative', height, borderRadius: 2, overflow: 'hidden' }}>
      <Box
        ref={holder}
        sx={{
          position: 'absolute',
          inset: 0,
          bgcolor: '#0B1220',
          // MapLibre's stylesheet sets `position: relative` on this element, which clobbers the
          // absolute positioning above and collapses the container to zero height. Re-asserting it
          // with higher specificity is what actually makes the container fill its parent.
          '&.maplibregl-map': { position: 'absolute !important', inset: 0 },
        }}
      />
      {!composed ? (
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'grid',
            placeItems: 'center',
            bgcolor: 'rgba(11,18,32,0.72)',
          }}
        >
          <Typography sx={{ color: mapChrome.textFaint, fontSize: 13 }}>
            Composing preview…
          </Typography>
        </Box>
      ) : null}
    </Box>
  );
}

/**
 * Tears down the previous preview and draws the new one.
 *
 * Rebuilt rather than reconciled, unlike the console's map. A preview exists to answer "what does
 * this style look like now", it holds no panning state worth preserving, and rebuilding removes the
 * whole class of bug where a paint property the new style does not set keeps the old style's value.
 */
function apply(
  map: MlMap,
  composed: ComposedMapLayer | null | undefined,
  extent: [number, number, number, number] | null | undefined,
) {
  try {
    ensureMarkerImages(map);
    if (composed?.requiredIcons?.length) {
      // Fetched asynchronously; the preview repaints when they land. Adding the layers first means
      // the geometry and colours appear immediately rather than waiting on an icon download.
      void ensureStyleIcons(map, composed.requiredIcons).then(() => map.triggerRepaint());
    }

    const style = map.getStyle();
    for (const layer of style?.layers ?? []) {
      if (layer.id.startsWith('assets-')) map.removeLayer(layer.id);
    }
    for (const sourceId of Object.keys(style?.sources ?? {})) {
      if (sourceId.startsWith('assets-')) map.removeSource(sourceId);
    }
    if (!composed) return;

    map.addSource(composed.sourceId, composed.source as unknown as SourceSpecification);
    for (const spec of composed.layers) {
      map.addLayer(spec as unknown as AddLayerObject);
    }

    if (extent) {
      const [minLon, minLat, maxLon, maxLat] = extent;
      if (minLon === maxLon && minLat === maxLat) {
        map.easeTo({ center: [minLon, minLat], zoom: 15, duration: 0 });
      } else {
        map.fitBounds(
          [
            [minLon, minLat],
            [maxLon, maxLat],
          ],
          { padding: 30, maxZoom: 16, duration: 0 },
        );
      }
    }
  } catch (cause) {
    // A malformed style must not take the dialog down with it. MapLibre dispatches `style.load`
    // synchronously from inside its own loading code, and an exception escaping a listener there
    // unwinds the load rather than surfacing as a map error.
    // eslint-disable-next-line no-console
    console.error('[layer preview] could not apply style', cause);
  }
}
