import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef } from 'react';
import { AttributionControl, Map as MlMap, ScaleControl, setWorkerUrl } from 'maplibre-gl';
import type {
  AddLayerObject,
  ExpressionSpecification,
  GeoJSONSource,
  LngLatLike,
  MapMouseEvent,
  SourceSpecification,
} from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// The `?url` suffix tells Vite to emit the worker as its own asset and hand us its resolved URL,
// instead of bundling it into the main entry where the worker code would never run.
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { Box } from '@mui/material';
import { tokenStore } from '@/lib/auth/tokenStore';
import { mapChrome } from '../mapTheme';
import { describeMapError } from '../mapErrors';
import { baseMapStyle, type BaseMapId } from '../basemaps';
import { ensureMarkerImages, ensureStyleIcons } from '../iconImages';
import type { LayerSummary } from '../api/gisApi';
import type { ComposedMapLayer } from '@/features/layers/types';

/*
 * MapLibre v6 split its tile-decoding web worker into a separate file and derives the worker URL
 * from `import.meta.url`. Under a bundler (Vite dev optimiser, or a production Rollup build) that
 * resolution is unreliable: the worker is not a sibling of the bundled entry, the computed URL 404s
 * silently, no worker is ever spawned, tiles are fetched but never decoded, and the map stays blank
 * forever with `loaded=false` and no error event. This is exactly the v5→v6 migration's documented
 * footgun. Pointing at the worker explicitly — before any Map is constructed — fixes it.
 *
 * This runs once at module load, which is before the first `new MlMap(...)` in the effect below.
 */
setWorkerUrl(maplibreWorkerUrl);

/**
 * Imperative surface the console chrome drives the map through.
 *
 * Anything that can be declarative (which layers, which base map, length/area measure on/off) is
 * a prop. Camera moves cannot be: the view is animated and stateful, and re-deriving it from React
 * state on every render fights the map rather than driving it.
 */
export interface MapHandle {
  zoomIn: () => void;
  zoomOut: () => void;
  flyTo: (lonLat: [number, number], zoom?: number) => void;
  fitBounds: (bounds: Bounds) => void;
  clearMeasurements: () => void;
}

/** A bounding box in EPSG:4326, ordered `[minLon, minLat, maxLon, maxLat]`. */
export type Bounds = [number, number, number, number];

/**
 * An asset the operator clicked, as read from the vector tile.
 *
 * These five properties are everything `ST_AsMVT` puts in the tile, which is deliberately little —
 * the tile is the hot path and must not carry an attribute bag. It is enough to render the
 * inspection card's header immediately, with no request in flight, while the full record loads.
 */
export interface PickedFeature {
  assetId: string;
  assetCode: string | null;
  name: string | null;
  status: string | null;
  assetType: string | null;
  /** Catalogue code of the layer the feature was drawn from, e.g. `pipelines`. */
  layerCode: string;
  /** Where the operator clicked, not the feature's centroid — a line has no single point. */
  lngLat: [number, number];
}

const MEASURE_SOURCE = 'ag-measure';
const MEASURE_AREA_SOURCE = 'ag-measure-area';

/**
 * The MapLibre GL map surface.
 *
 * MapLibre replaced OpenLayers here: it renders vector tiles on the GPU, so a dense pipe network
 * pans smoothly instead of re-rasterising on the main thread, and layer styling becomes data
 * rather than imperative style functions.
 *
 * The instance is created once into a ref — it is imperative and does not tolerate React
 * re-rendering its container. React owns only the surrounding chrome.
 *
 * Tiles carry the bearer token as a request header via `transformRequest`, so the credential
 * never lands in a URL that a proxy or access log might retain.
 */
export const MapView = forwardRef<MapHandle, {
  layers: LayerSummary[];
  /*
   * The server-composed MapLibre specifications, one per layer. The map applies these verbatim and
   * decides nothing about appearance — which is what lets a layer created at runtime, or one an
   * administrator recoloured a minute ago, draw correctly with no code change.
   *
   * `layers` above still carries the catalogue: which codes exist and which the operator has
   * switched on. The two are separate because visibility is client state that changes on every
   * click, while the composed style is server state that changes when someone edits a style.
   */
  composed: ComposedMapLayer[];
  defaultCenter: [number, number];
  defaultZoom: number;
  baseMap: BaseMapId;
  measuring: boolean;
  onMeasure: (metres: number | null) => void;
  areaMeasuring: boolean;
  onArea: (squareMetres: number | null) => void;
  onDiagnostic: (message: string | null) => void;
  /*
   * Extent to frame the opening camera on, once, as soon as both it and the map exist. It arrives
   * from a separate request, so it may land before or after the map finishes loading; both orders
   * are handled, and the fit is applied at most once so it can never yank the camera out from
   * under an operator who has already started panning.
   */
  focusBounds?: Bounds | null;
  /** Fired with the topmost asset under the pointer, or null when the click hit bare map. */
  onPick?: (feature: PickedFeature | null) => void;
  /** The picked asset to paint a selection halo around. */
  selected?: PickedFeature | null;
  /*
   * Optional lifecycle hook for diagnostics (e.g. logging phase transitions). Not rendered in the
   * UI — the previous debug "map status" box that consumed this was removed; phase is internal.
   */
  onPhase?: (phase: string) => void;
}>(function MapView(
  { layers, composed, defaultCenter, defaultZoom, baseMap, measuring, onMeasure, areaMeasuring, onArea, onDiagnostic, focusBounds, onPick, selected, onPhase },
  ref,
) {
  const holder = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MlMap | null>(null);
  const styleApplied = useRef(false);
  /*
   * Whether a style is loaded far enough to accept `addSource`/`addLayer`.
   *
   * Deliberately not `map.isStyleLoaded()`: that reports whether the style *and every tile of every
   * source* have finished loading, so it is false for as long as the base map is still fetching
   * imagery. Gating layer rebuilds on it silently drops them during precisely the window a base-map
   * switch creates.
   */
  const styleReady = useRef(false);
  const onDiagnosticRef = useRef(onDiagnostic);
  onDiagnosticRef.current = onDiagnostic;
  const onPhaseRef = useRef(onPhase);
  onPhaseRef.current = onPhase;
  // Wrap so callers that omit `onPhase` don't need a null-check at every emit site.
  const emitPhase = (p: string) => onPhaseRef.current?.(p);

  // Length measurement vertices, in lon/lat. Held in a ref because the click handler is bound once.
  const measurePoints = useRef<[number, number][]>([]);
  const measuringRef = useRef(measuring);
  measuringRef.current = measuring;
  const onMeasureRef = useRef(onMeasure);
  onMeasureRef.current = onMeasure;

  // Area measurement vertices, in lon/lat — a polygon ring, parallel to the length sketch.
  const areaPoints = useRef<[number, number][]>([]);
  const areaMeasuringRef = useRef(areaMeasuring);
  areaMeasuringRef.current = areaMeasuring;
  const onAreaRef = useRef(onArea);
  onAreaRef.current = onArea;
  // Latest layer list, so a style reload can rebuild sources without re-binding handlers.
  const layersRef = useRef(layers);
  layersRef.current = layers;
  const composedRef = useRef(composed);
  composedRef.current = composed;

  // Selection, read by the once-bound click handler and by the style-reload resync.
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  const selectedRef = useRef(selected);
  selectedRef.current = selected;

  // Opening camera. Read through a ref by the `load` handler, which is bound once.
  const focusBoundsRef = useRef(focusBounds);
  focusBoundsRef.current = focusBounds;
  const focusApplied = useRef(false);

  /**
   * Frames the focus extent, at most once per mounted map.
   *
   * Called from both the map's `load` event and an effect on the prop, because either can be the
   * later of the two — the extent is a separate request and the map waits on its container's size.
   */
  const applyFocus = useCallback(() => {
    const map = mapRef.current;
    const bounds = focusBoundsRef.current;
    if (!map || !bounds || focusApplied.current) return;
    focusApplied.current = true;
    fitBounds(map, bounds);
  }, []);

  useImperativeHandle(ref, () => ({
    zoomIn: () => mapRef.current?.zoomIn(),
    zoomOut: () => mapRef.current?.zoomOut(),
    flyTo: (lonLat, zoom) =>
      mapRef.current?.flyTo({
        center: lonLat as LngLatLike,
        zoom: zoom ?? Math.max(mapRef.current.getZoom(), 16),
        duration: 900,
      }),
    fitBounds: (bounds) => {
      if (mapRef.current) fitBounds(mapRef.current, bounds, 700);
    },
    clearMeasurements: () => {
      measurePoints.current = [];
      pushMeasureGeometry(mapRef.current, []);
      onMeasureRef.current(null);
      areaPoints.current = [];
      pushAreaGeometry(mapRef.current, []);
      onAreaRef.current(null);
    },
  }));

  // --- Create the map once -------------------------------------------------------------------
  useEffect(() => {
    emitPhase('effect-ran');
    if (mapRef.current) {
      emitPhase('already-created');
      return;
    }
    if (!holder.current) {
      // Previously a silent return. A null container produces a page with full chrome and no
      // map, and no indication anywhere that the map was never even attempted.
      emitPhase('no-container');
      onDiagnosticRef.current('Map container was not ready; the map was never created.');
      return;
    }
    // Captured once so every closure below (some invoked on a later frame) reads the same element
    // and TypeScript can narrow the ref's nullable type inside them.
    const container = holder.current;

    /*
     * Wait for the container to have a non-zero size before constructing.
     *
     * MapLibre reads the container's clientWidth/clientHeight once, at construction, and creates a
     * WebGL canvas of exactly that size. If the container is still 0×0 at that instant — common
     * when this lazy chunk resolves before the flex layout has settled, or while a panel width
     * animation is mid-flight — the map constructs "successfully" into a zero-size canvas. It then
     * never finishes loading its style (a render is required to complete the load, and a 0×0
     * canvas never renders), reports no error, and stays permanently blank with `canvas=0x0`.
     *
     * Polling on animation frames terminates the moment layout grants a size, which is almost
     * always the very next frame; the 5s ceiling turns a layout that never settles into a
     * diagnosable failure instead of an infinite wait.
     */
    let cancelled = false;
    let raf = 0;

    const hasSize = () => container.clientWidth > 0 && container.clientHeight > 0;

    const build = () => {
      emitPhase('constructing');

      /*
       * The constructor creates the WebGL context synchronously. If that fails — blocked GPU,
       * software-rendering fallback disabled, a hardened browser policy — it throws here, before
       * any `error` handler could be attached, and React swallows it into a blank canvas. Catching
       * it is the difference between a diagnosable failure and an empty rectangle.
       */
      let tileAttempts = 0;
      let tileOk = 0;

      let map: MlMap;
      try {
        map = new MlMap({
          container,
          style: baseMapStyle(baseMap),
          center: defaultCenter,
          zoom: defaultZoom,
          minZoom: 3,
          maxZoom: 20,
          attributionControl: false,
          // Tiles are same-origin through the proxy; the header is attached here so the token is
          // never written into a cacheable URL.
          transformRequest: (url, resourceType) => {
            if (resourceType === 'Tile') tileAttempts++;
            if (resourceType === 'Tile' && url.includes('/api/v1/gis/tiles/')) {
              const token = tokenStore.get();
              return token ? { url, headers: { Authorization: `Bearer ${token}` } } : { url };
            }
            return { url };
          },
        });
      } catch (cause) {
        const message = cause instanceof Error ? cause.message : String(cause);
        // eslint-disable-next-line no-console
        console.error('[map] construction failed', cause);
        onDiagnosticRef.current(`Map could not start: ${message}`);
        return;
      }
      mapRef.current = map;
      emitPhase('constructed');

      map.addControl(new ScaleControl({ maxWidth: 110, unit: 'metric' }), 'bottom-left');
      map.addControl(new AttributionControl({ compact: true }), 'bottom-right');

      /*
       * MapLibre reports tile, style and WebGL failures through this event and nowhere else.
       * Without a handler they are swallowed and the operator is left staring at an empty canvas
       * with no way to tell a dead tile server from a broken style.
       */
      map.on('error', (event) => {
        const message = (event as { error?: Error }).error?.message ?? 'Unknown map error';
        // The raw message goes to the console — it carries the failing URL, which the operator-
        // facing text deliberately drops — while the UI gets the actionable translation.
        // eslint-disable-next-line no-console
        console.error('[map]', message, event);
        onDiagnosticRef.current(describeMapError(message));
      });

      /*
       * Tile successes, counted from MapLibre's own event rather than by re-fetching.
       *
       * This previously issued a second `fetch(url)` per tile from `transformRequest`. That copy
       * carried no Authorization header, so every asset tile was requested twice and the duplicate
       * always came back 401 — leaving `tileOk` pinned at zero for precisely the layers the counter
       * existed to measure, and doubling tile traffic to say so.
       */
      map.on('data', (event) => {
        if ((event as { tile?: unknown }).tile) tileOk++;
      });

      /*
       * If the map has not finished loading in a few seconds, report the internal state rather
       * than a generic message. Which of these flags is false localises the fault immediately:
       * a style that never loaded is a different bug from a loaded style that never painted.
       */
      const watchdog = window.setTimeout(() => {
        if (map.loaded()) return;
        let sources = 0;
        try {
          sources = Object.keys(map.getStyle()?.sources ?? {}).length;
        } catch {
          sources = -1;
        }
        const canvas = map.getCanvas();
        onDiagnosticRef.current(
          `Map did not finish loading — styleLoaded=${map.isStyleLoaded()} ` +
            `loaded=${map.loaded()} sources=${sources} ` +
            `canvas=${canvas?.width ?? 0}x${canvas?.height ?? 0}`,
        );
      }, 6_000);

      /*
       * Every style load lands here — the first one, and every base-map switch.
       *
       * `setStyle` with a style object takes MapLibre's diff path (`Style.setState`), which diffs
       * the new style against the *serialized current* style. The asset sources and layers are
       * added at runtime and appear in no base-map style, so the diff removes every one of them:
       * switching to Satellite deletes the network. `style.load` is the one event MapLibre fires
       * after that removal on both the diff path and the full-rebuild path, so this is where the
       * layers go back.
       */
      map.on('style.load', () => {
        emitPhase('style-loaded');
        styleReady.current = true;
        safeSync(map, layersRef.current, composedRef.current, selectedRef.current, onDiagnosticRef.current);
        // The measure sources are recreated empty by that rebuild. Re-push the sketches so an
        // in-progress measurement survives a base-map switch instead of vanishing with its readout
        // still on screen.
        pushMeasureGeometry(map, measurePoints.current);
        pushAreaGeometry(map, areaPoints.current);
      });

      /*
       * `load` only fires after the first completed render, and a render only happens once the
       * canvas has a non-zero size. A container that gains its size after the map is constructed
       * therefore leaves the map permanently "loaded but never painted": no error, no event, no
       * pixels. Nudging it across the next few frames costs nothing and covers that case.
       */
      [0, 50, 250, 1000].forEach((delay) =>
        window.setTimeout(() => {
          if (!mapRef.current) return;
          mapRef.current.resize();
          mapRef.current.triggerRepaint();
        }, delay),
      );

      // Live telemetry for developer diagnostics only — routed to the console, never to the UI.
      // Earlier this overwrote the `phase` lifecycle signal every second, which kept a debug
      // "map status" box permanently on screen because the telemetry string never equalled the
      // 'loaded' literal the box checked against. Telemetry now stays out of React state.
      const telemetry = window.setInterval(() => {
        const canvas = map.getCanvas();
        // eslint-disable-next-line no-console
        console.debug(
          '[map telemetry]',
          `loaded=${map.loaded()} style=${map.isStyleLoaded()} ` +
            `canvas=${canvas?.width ?? 0}x${canvas?.height ?? 0} ` +
            `box=${container.clientWidth}x${container.clientHeight} ` +
            `tiles=${tileAttempts}/${tileOk}`,
        );
      }, 1000);
      map.on('load', () => {
        emitPhase('loaded');
        window.clearTimeout(watchdog);
        onDiagnosticRef.current(null);
        styleReady.current = true;
        safeSync(map, layersRef.current, composedRef.current, selectedRef.current, onDiagnosticRef.current);
        applyFocus();
      });

      map.on('click', (event: MapMouseEvent) => {
        const lngLat: [number, number] = [event.lngLat.lng, event.lngLat.lat];
        // Area mode wins if both are somehow on: a filled polygon is the more disruptive sketch.
        if (areaMeasuringRef.current) {
          areaPoints.current = [...areaPoints.current, lngLat];
          pushAreaGeometry(map, areaPoints.current);
          onAreaRef.current(polygonArea(areaPoints.current));
          return;
        }
        if (measuringRef.current) {
          measurePoints.current = [...measurePoints.current, lngLat];
          pushMeasureGeometry(map, measurePoints.current);
          onMeasureRef.current(pathLength(measurePoints.current));
          return;
        }
        // Not sketching: the click is an inspection. A miss reports null, which closes the card —
        // clicking bare map to dismiss is the gesture every map product has trained operators on.
        onPickRef.current?.(pickFeature(map, event.point, layersRef.current, lngLat));
      });

      /*
       * Pointer feedback on hover, so an operator can tell a clickable asset from cartography
       * before committing a click. Suppressed while sketching, where the crosshair means something
       * else and every pixel is a valid target.
       */
      map.on('mousemove', (event: MapMouseEvent) => {
        if (measuringRef.current || areaMeasuringRef.current) return;
        const over = pickFeature(map, event.point, layersRef.current, [0, 0]) !== null;
        map.getCanvas().style.cursor = over ? 'pointer' : '';
      });
      // Finishing a measurement must not also zoom the map. Applies to both length and area modes.
      map.on('dblclick', (event) => {
        if (measuringRef.current || areaMeasuringRef.current) event.preventDefault();
      });

      /*
       * MapLibre sizes its canvas from the container and does not watch it. The container's width
       * changes every time the tool panel opens or closes; without this the canvas keeps its old
       * dimensions and the page background shows through as a band along the edge that grew.
       */
      const observer = new ResizeObserver(() => map.resize());
      observer.observe(container);

      teardown = () => {
        window.clearInterval(telemetry);
        window.clearTimeout(watchdog);
        observer.disconnect();
        map.remove();
        mapRef.current = null;
      };
    };

    // The cleanup closure captures the teardown that `build` assigns once it runs.
    let teardown: (() => void) | undefined;

    if (hasSize()) {
      build();
    } else {
      emitPhase('waiting-for-size');
      const begun = Date.now();
      const tick = () => {
        if (cancelled) return;
        if (hasSize()) {
          build();
          return;
        }
        if (Date.now() - begun > 5_000) {
          onDiagnosticRef.current(
            'Map container never received a size. The surrounding layout collapsed to zero height.',
          );
          emitPhase('no-size');
          return;
        }
        raf = window.requestAnimationFrame(tick);
      };
      raf = window.requestAnimationFrame(tick);
    }

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(raf);
      teardown?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // --- Base map ------------------------------------------------------------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    /*
     * Skip the first run. The constructor has already applied this exact style, and calling
     * setStyle again while the initial load is still in flight aborts it — leaving a map that
     * has a correctly sized canvas, no style, and no error: a silent blank map.
     */
    if (!styleApplied.current) {
      styleApplied.current = true;
      return;
    }
    /*
     * Closed until the new style announces itself. The diff path re-opens it synchronously inside
     * `setStyle`; the full-rebuild fallback (taken when the diff cannot be expressed) tears the
     * style down and reloads it asynchronously, and an `addSource` in that gap throws.
     */
    styleReady.current = false;
    map.setStyle(baseMapStyle(baseMap));
  }, [baseMap]);

  // --- Opening camera ------------------------------------------------------------------------
  useEffect(() => {
    // No-ops until the map exists; the `load` handler covers the case where the extent arrived
    // first, and this covers the case where the map was ready before the extent request returned.
    applyFocus();
  }, [focusBounds, applyFocus]);

  // --- Asset layers and selection --------------------------------------------------------------
  // One effect for both: the selection halo is drawn from the selected feature's own source, so it
  // has to be rebuilt whenever the layer set changes anyway.
  useEffect(() => {
    const map = mapRef.current;
    // Gated on the style being *loadable-into*, not on every tile having arrived — see `styleReady`.
    // A toggle made while the base map was still fetching imagery used to be dropped entirely.
    if (!map || !styleReady.current) return;
    safeSync(map, layers, composed, selected ?? null, onDiagnosticRef.current);
  }, [layers, composed, selected]);

  // --- Measure mode --------------------------------------------------------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    // Either sketch mode uses the crosshair; the cursor clears only when both are off.
    map.getCanvas().style.cursor = measuring || areaMeasuring ? 'crosshair' : '';
    // Leaving measure mode discards the sketch; a stale line over the map is worse than none.
    if (!measuring) {
      measurePoints.current = [];
      pushMeasureGeometry(map, []);
      onMeasureRef.current(null);
    }
  }, [measuring, areaMeasuring]);

  // --- Area mode -----------------------------------------------------------------------------
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    // Leaving area mode discards the polygon; a stale fill over the map is worse than none.
    if (!areaMeasuring) {
      areaPoints.current = [];
      pushAreaGeometry(map, []);
      onAreaRef.current(null);
    }
  }, [areaMeasuring]);

  return (
    <Box
      ref={holder}
      sx={{
        position: 'absolute',
        inset: 0,
        bgcolor: '#0B1220',
        // MapLibre adds the `.maplibregl-map` class to this element and ships a stylesheet that
        // sets `position: relative` on it — which clobbers the `position: absolute` above and
        // collapses the container to zero height (its canvas child is absolutely positioned, so
        // there is no in-flow content to give it height). Re-asserting absolute positioning on the
        // map class, with higher specificity than MapLibre's own rule, is what actually makes the
        // container fill its `position: relative` parent. Without this the map constructs into a
        // 0px-tall box and never renders — the classic "blank map, no error" failure.
        '&.maplibregl-map': { position: 'absolute !important', inset: 0 },
        // MapLibre's own controls, restyled to match the console chrome.
        '& .maplibregl-ctrl-scale': {
          background: mapChrome.floating,
          border: `1px solid ${mapChrome.border}`,
          borderTop: 'none',
          borderRadius: '8px',
          color: mapChrome.text,
          fontSize: 11,
          fontWeight: 600,
          padding: '2px 8px',
          margin: '0 0 16px 16px',
          backdropFilter: 'blur(8px)',
        },
        '& .maplibregl-ctrl-attrib': {
          background: mapChrome.floating,
          borderRadius: '8px 0 0 0',
          margin: 0,
        },
        '& .maplibregl-ctrl-attrib a, & .maplibregl-ctrl-attrib': {
          color: mapChrome.textFaint,
          fontSize: 10.5,
        },
        '& .maplibregl-ctrl-attrib-button': { filter: 'invert(1)' },
      }}
    />
  );
});

// --- Camera ------------------------------------------------------------------------------------

/**
 * Frames a lon/lat bounding box.
 *
 * Padded so the outermost assets are not welded to the canvas edge, and capped at z16: a network of
 * one pipe (or several pipes in the same street) has a near-zero extent, and an uncapped fit would
 * slam the camera to maximum zoom where the operator sees a single line and no context. A truly
 * degenerate box — every asset at the same coordinate — has no aspect ratio for `fitBounds` to
 * work from, so it is centred at a fixed neighbourhood zoom instead.
 */
function fitBounds(map: MlMap, [minLon, minLat, maxLon, maxLat]: Bounds, duration = 0) {
  if (![minLon, minLat, maxLon, maxLat].every(Number.isFinite)) return;

  if (minLon === maxLon && minLat === maxLat) {
    map.easeTo({ center: [minLon, minLat], zoom: 15, duration });
    return;
  }
  map.fitBounds(
    [
      [minLon, minLat],
      [maxLon, maxLat],
    ],
    { padding: 60, maxZoom: 16, duration },
  );
}

// --- Layer synchronisation -------------------------------------------------------------------

/**
 * Runs {@link syncLayers} without ever throwing into MapLibre.
 *
 * MapLibre dispatches `load` and `styledata` synchronously from inside its own style-loading
 * code. An exception escaping a listener there does not surface as a map `error` — it unwinds
 * the load itself, leaving a map with a correctly sized canvas, no style, no error event and
 * nothing painted. The base map must not be able to fail because an asset layer is malformed.
 */
function safeSync(
  map: MlMap,
  layers: LayerSummary[],
  composed: ComposedMapLayer[],
  selected: PickedFeature | null | undefined,
  report: (message: string | null) => void,
) {
  try {
    ensureMarkerImages(map);
    const visible = new Set(layers.filter((l) => l.visible).map((l) => l.code));
    /*
     * Library and uploaded icons are fetched, so registration is asynchronous while `addLayer` is
     * not. The layers are added immediately with whatever is registered, and the icons are re-applied
     * once they arrive — MapLibre repaints a layer when an image it references appears, so the only
     * visible effect is a marker that fills in a frame later rather than one that never appears.
     */
    const needed = composed
      .filter((layer) => visible.has(layer.code))
      .flatMap((layer) => layer.requiredIcons ?? []);
    if (needed.length > 0) {
      void ensureStyleIcons(map, needed).then(() => map.triggerRepaint());
    }
    syncLayers(map, composed, visible);
    syncHighlight(map, selected ?? null);
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause);
    // eslint-disable-next-line no-console
    console.error('[map] layer sync failed', cause);
    report(`Asset layers could not be drawn: ${message}`);
  }
}

/**
 * Reconciles the map's sources and layers with the server-composed style.
 *
 * This function used to build five render layers per catalogue entry and read their colours from a
 * hard-coded table keyed by layer code. That worked while the layer set was fixed in a migration; it
 * could not survive layers being created at runtime, because a layer the table did not name rendered
 * grey until someone shipped a release — the same failure Data Management removed for fields.
 *
 * Now the server sends whole MapLibre layer specifications, composed from each layer's stored style,
 * and this adds them verbatim. Nothing here decides what a layer looks like, so a new layer or a
 * recoloured one is a database change. The render-layer ids are unchanged
 * (`assets-<code>-fill`, `-line-casing`, `-line`, `-point-halo`, `-point`, plus `-label`) because
 * `pickFeature` queries them and `removeAssetLayer` tears them down.
 *
 * Reconciled rather than rebuilt, so panning state and the tile cache survive a toggle.
 */
function syncLayers(map: MlMap, composed: ComposedMapLayer[], visibleCodes: Set<string>) {
  const wanted = composed.filter((layer) => visibleCodes.has(layer.code));
  const wantedCodes = new Set(wanted.map((layer) => layer.code));

  // Remove what is no longer wanted.
  for (const layer of composed) {
    if (wantedCodes.has(layer.code)) continue;
    removeAssetLayer(map, layer.code);
  }

  for (const layer of wanted) {
    if (map.getSource(layer.sourceId)) continue;
    map.addSource(layer.sourceId, layer.source as unknown as SourceSpecification);
    /*
     * Draw order is the order the server sent: polygons, then lines, then points, then labels. It
     * is load-bearing rather than cosmetic — a meter added before the DMA it sits inside disappears
     * under the zone's fill, and a label under a line is unreadable. Preserving the array order is
     * the whole contract, so nothing here sorts or filters it.
     */
    for (const spec of layer.layers) {
      map.addLayer(spec as unknown as AddLayerObject);
    }
  }

  ensureMeasureLayers(map);
}

// --- Picking and selection ---------------------------------------------------------------------

/**
 * Render layers that carry a clickable asset, topmost first.
 *
 * Casings are excluded: they paint the same feature as the line above them, and querying both just
 * returns each pipe twice. Layers the registry marks not queryable are excluded too — a boundary set
 * held purely as context should not intercept a click meant for the network drawn on top of it, and
 * that is now a setting in Layer Management rather than something the client decides.
 */
function pickableLayerIds(map: MlMap, layers: LayerSummary[]): string[] {
  const ids: string[] = [];
  for (const layer of layers) {
    if (!layer.visible || layer.queryable === false) continue;
    for (const suffix of ['point', 'line', 'fill']) {
      const id = `assets-${layer.code}-${suffix}`;
      if (map.getLayer(id)) ids.push(id);
    }
  }
  return ids;
}

/**
 * The topmost asset rendered under a screen point, or null.
 *
 * A small square around the cursor rather than the exact pixel: a pipe is drawn 1–6 px wide, and
 * requiring a hit on that exact line turns inspection into a game of darts. Six pixels of slop is
 * roughly a fingertip's worth of aim at desktop resolution.
 */
function pickFeature(
  map: MlMap,
  point: { x: number; y: number },
  layers: LayerSummary[],
  lngLat: [number, number],
): PickedFeature | null {
  const ids = pickableLayerIds(map, layers);
  if (ids.length === 0) return null;

  const slop = 6;
  const found = map.queryRenderedFeatures(
    [
      [point.x - slop, point.y - slop],
      [point.x + slop, point.y + slop],
    ],
    { layers: ids },
  );
  const hit = found[0];
  if (!hit) return null;

  const props = hit.properties ?? {};
  const assetId = typeof props.id === 'string' ? props.id : null;
  if (!assetId) return null;

  // `assets-<code>-<suffix>`: the code itself may contain hyphens (`open-wells`), so the suffix is
  // stripped from the end rather than the string being split on every hyphen.
  const layerCode = String(hit.layer.id).replace(/^assets-/, '').replace(/-(point|line|fill)$/, '');

  return {
    assetId,
    assetCode: asText(props.asset_code),
    name: asText(props.name),
    status: asText(props.status),
    assetType: asText(props.asset_type),
    layerCode,
    lngLat,
  };
}

function asText(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

const HIGHLIGHT_GLOW = 'ag-highlight-glow';
const HIGHLIGHT_LINE = 'ag-highlight-line';
const HIGHLIGHT_POINT = 'ag-highlight-point';

/**
 * Paints the selection halo on the selected feature.
 *
 * Drawn as three dedicated layers filtered on the feature's `id`, rather than as a feature-state
 * flag on the layer's own paint. The source promotes `id` to the MapLibre feature id, so
 * `setFeatureState` would now work — but the halo is a wider glow *under* a crisp white line, which
 * is two marks the styled layer does not have and cannot grow from a state flag. Feature state is
 * the right tool for recolouring an existing mark (hover), not for adding new ones.
 *
 * The halo is a wide translucent glow beneath a crisp white line, which reads on top of any layer
 * colour and over both dark cartography and bright imagery; a colour swap alone would be invisible
 * against a base map that happens to share the layer's hue.
 */
function syncHighlight(map: MlMap, selected: PickedFeature | null) {
  for (const id of [HIGHLIGHT_GLOW, HIGHLIGHT_LINE, HIGHLIGHT_POINT]) {
    if (map.getLayer(id)) map.removeLayer(id);
  }
  if (!selected) return;

  const sourceId = `assets-${selected.layerCode}`;
  if (!map.getSource(sourceId)) return;

  const match: ExpressionSpecification = ['==', ['get', 'id'], selected.assetId];
  const isLine: ExpressionSpecification = [
    'any',
    ['==', ['geometry-type'], 'LineString'],
    ['==', ['geometry-type'], 'Polygon'],
  ];

  map.addLayer({
    id: HIGHLIGHT_GLOW,
    type: 'line',
    source: sourceId,
    'source-layer': selected.layerCode,
    filter: ['all', match, isLine],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': mapChrome.accent,
      'line-opacity': 0.55,
      'line-blur': 3,
      'line-width': ['interpolate', ['linear'], ['zoom'], 8, 8, 14, 14, 18, 22],
    },
  });
  map.addLayer({
    id: HIGHLIGHT_LINE,
    type: 'line',
    source: sourceId,
    'source-layer': selected.layerCode,
    filter: ['all', match, isLine],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': '#FFFFFF',
      'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1.6, 14, 3, 18, 5],
    },
  });
  map.addLayer({
    id: HIGHLIGHT_POINT,
    type: 'circle',
    source: sourceId,
    'source-layer': selected.layerCode,
    filter: ['all', match, ['==', ['geometry-type'], 'Point']],
    paint: {
      'circle-color': 'rgba(0,0,0,0)',
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 8, 7, 14, 12, 18, 20],
      'circle-stroke-color': '#FFFFFF',
      'circle-stroke-width': 2.5,
    },
  });
}

function removeAssetLayer(map: MlMap, code: string) {
  const sourceId = `assets-${code}`;
  // Includes the casing + halo layers added for the professionalised symbology, so toggling a
  // layer off never leaves orphan paint on the map.
  for (const suffix of ['fill', 'line-casing', 'line', 'point-halo', 'point', 'label']) {
    const id = `${sourceId}-${suffix}`;
    if (map.getLayer(id)) map.removeLayer(id);
  }
  if (map.getSource(sourceId)) map.removeSource(sourceId);
}

// --- Measurement -----------------------------------------------------------------------------

function ensureMeasureLayers(map: MlMap) {
  if (!map.getSource(MEASURE_SOURCE)) {
    map.addSource(MEASURE_SOURCE, {
      type: 'geojson',
      data: { type: 'FeatureCollection', features: [] },
    });
    map.addLayer({
      id: `${MEASURE_SOURCE}-line`,
      type: 'line',
      source: MEASURE_SOURCE,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': mapChrome.accent, 'line-width': 3, 'line-dasharray': [2, 1.5] },
    });
    // Glow under the sketch line, so an in-progress measurement stays visible over busy imagery.
    map.addLayer({
      id: `${MEASURE_SOURCE}-point-halo`,
      type: 'circle',
      source: MEASURE_SOURCE,
      filter: ['==', ['geometry-type'], 'Point'],
      paint: {
        'circle-radius': 11,
        'circle-color': mapChrome.accent,
        'circle-opacity': 0.3,
        'circle-blur': 0.8,
      },
    });
    map.addLayer({
      id: `${MEASURE_SOURCE}-point`,
      type: 'circle',
      source: MEASURE_SOURCE,
      filter: ['==', ['geometry-type'], 'Point'],
      paint: {
        'circle-radius': 5,
        'circle-color': mapChrome.accent,
        'circle-stroke-width': 2,
        'circle-stroke-color': '#0B1220',
      },
    });
  }
  ensureAreaLayers(map);
}

/**
 * The area sketch source and its render layers.
 *
 * A separate GeoJSON source from the length sketch so the two can coexist (only one is active at a
 * time, but keeping them independent avoids a sketch of one type clobbering the other's geometry
 * on a mid-flight mode switch). The fill is drawn semi-transparent so the asset network beneath it
 * stays visible — an operator measuring a DMA needs to see the meters inside it.
 */
function ensureAreaLayers(map: MlMap) {
  if (map.getSource(MEASURE_AREA_SOURCE)) return;
  map.addSource(MEASURE_AREA_SOURCE, {
    type: 'geojson',
    data: { type: 'FeatureCollection', features: [] },
  });
  map.addLayer({
    id: `${MEASURE_AREA_SOURCE}-fill`,
    type: 'fill',
    source: MEASURE_AREA_SOURCE,
    filter: ['==', ['geometry-type'], 'Polygon'],
    paint: { 'fill-color': mapChrome.accent, 'fill-opacity': 0.18 },
  });
  map.addLayer({
    id: `${MEASURE_AREA_SOURCE}-line`,
    type: 'line',
    source: MEASURE_AREA_SOURCE,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': mapChrome.accent, 'line-width': 2.5, 'line-dasharray': [2, 1.5] },
  });
  // Glow under each area vertex, mirroring the length sketch, for a consistent vivid feel.
  map.addLayer({
    id: `${MEASURE_AREA_SOURCE}-point-halo`,
    type: 'circle',
    source: MEASURE_AREA_SOURCE,
    filter: ['==', ['geometry-type'], 'Point'],
    paint: {
      'circle-radius': 11,
      'circle-color': mapChrome.accent,
      'circle-opacity': 0.3,
      'circle-blur': 0.8,
    },
  });
  map.addLayer({
    id: `${MEASURE_AREA_SOURCE}-point`,
    type: 'circle',
    source: MEASURE_AREA_SOURCE,
    filter: ['==', ['geometry-type'], 'Point'],
    paint: {
      'circle-radius': 5,
      'circle-color': mapChrome.accent,
      'circle-stroke-width': 2,
      'circle-stroke-color': '#0B1220',
    },
  });
}

function pushMeasureGeometry(map: MlMap | null, points: [number, number][]) {
  if (!map) return;
  const source = map.getSource(MEASURE_SOURCE) as GeoJSONSource | undefined;
  if (!source) return;
  source.setData(measureFeatureCollection(points));
}

function measureFeatureCollection(points: [number, number][]): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = points.map((coordinates) => ({
    type: 'Feature',
    properties: {},
    geometry: { type: 'Point', coordinates },
  }));
  if (points.length >= 2) {
    features.push({
      type: 'Feature',
      properties: {},
      geometry: { type: 'LineString', coordinates: points },
    });
  }
  return { type: 'FeatureCollection', features };
}

/**
 * Great-circle length of a path, in metres.
 *
 * Haversine rather than a planar sum: at Tamil Nadu's latitude a planar length over a 20 km main
 * is out by hundreds of metres, which is the difference between ordering the right pipe and the
 * wrong one.
 */
function pathLength(points: [number, number][]): number | null {
  if (points.length < 2) return null;
  const R = 6_371_008.8;
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const [lon1, lat1] = points[i - 1]!;
    const [lon2, lat2] = points[i]!;
    const dLat = ((lat2 - lat1) * Math.PI) / 180;
    const dLon = ((lon2 - lon1) * Math.PI) / 180;
    const a =
      Math.sin(dLat / 2) ** 2 +
      Math.cos((lat1 * Math.PI) / 180) *
        Math.cos((lat2 * Math.PI) / 180) *
        Math.sin(dLon / 2) ** 2;
    total += 2 * R * Math.asin(Math.sqrt(a));
  }
  return total;
}

function pushAreaGeometry(map: MlMap | null, points: [number, number][]) {
  if (!map) return;
  const source = map.getSource(MEASURE_AREA_SOURCE) as GeoJSONSource | undefined;
  if (!source) return;
  source.setData(areaFeatureCollection(points));
}

/**
 * Builds the area sketch geometry: a vertex Point per click, plus a closed Polygon once three or
 * more points exist. With only two points a line is drawn instead so the operator sees the ring
 * being assembled rather than nothing.
 */
function areaFeatureCollection(points: [number, number][]): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = points.map((coordinates) => ({
    type: 'Feature',
    properties: {},
    geometry: { type: 'Point', coordinates },
  }));
  if (points.length >= 3) {
    // GeoJSON polygons require a closed ring: first vertex repeated as the last.
    const ring = [...points, points[0]!];
    features.push({
      type: 'Feature',
      properties: {},
      geometry: { type: 'Polygon', coordinates: [ring] },
    });
  } else if (points.length === 2) {
    features.push({
      type: 'Feature',
      properties: {},
      geometry: { type: 'LineString', coordinates: points },
    });
  }
  return { type: 'FeatureCollection', features };
}

/**
 * Planar area of a polygon ring on the sphere, in square metres.
 *
 * Uses the spherical-excess formulation via the shoelace identity applied in an equirectangular
 * projection centred on the polygon's own latitude. That is accurate to a small fraction of a
 * percent for the parcel and DMA sizes this console measures (hectares to a few km²); a full
 * ellipsoidal treatment (Karney) would add dozens of lines for accuracy that field staff cannot
 * read off the readout. The polygon is implicitly closed — the returned ring need not repeat its
 * first vertex.
 */
function polygonArea(points: [number, number][]): number | null {
  const n = points.length;
  if (n < 3) return null;
  const R = 6_371_008.8;

  // Mean latitude of the ring — the latitude the equirectangular projection is honest at.
  let meanLat = 0;
  for (const [, lat] of points) meanLat += lat;
  meanLat /= n;
  const cosLat = Math.cos((meanLat * Math.PI) / 180);
  const mPerDegLat = (Math.PI * R) / 180;
  const mPerDegLon = (Math.PI * R * cosLat) / 180;

  // Shoelace on the projected plane. Sign reflects winding order; take the absolute value.
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const [lonA, latA] = points[i]!;
    const [lonB, latB] = points[(i + 1) % n]!;
    const xa = lonA * mPerDegLon;
    const xb = lonB * mPerDegLon;
    const ya = latA * mPerDegLat;
    const yb = latB * mPerDegLat;
    sum += xa * yb - xb * ya;
  }
  return Math.abs(sum) / 2;
}
