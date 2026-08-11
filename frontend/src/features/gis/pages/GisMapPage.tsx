import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Box, CircularProgress, Stack, Tooltip, Typography } from '@mui/material';
import CollapseIcon from '@mui/icons-material/ChevronLeftOutlined';
import AddIcon from '@mui/icons-material/AddOutlined';
import RemoveIcon from '@mui/icons-material/RemoveOutlined';
import RecenterIcon from '@mui/icons-material/MyLocationOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useMapStyle } from '@/features/layers/hooks/useLayers';
import { registerLayerSymbols } from '../layerStyle';
import { useLayers, useLayerExtent } from '../hooks/useGis';
import { MapView, type Bounds, type MapHandle, type PickedFeature } from '../components/MapView';
import { InspectionCard } from '../components/InspectionCard';
import type { BaseMapId } from '../basemaps';
import { MapRail, type RailTool } from '../components/MapRail';
import { MapSearch } from '../components/MapSearch';
import { BaseMapPanel, LayersPanel, LegendPanel, MeasurePanel, type MeasureMode } from '../components/MapPanels';
import { mapChrome, PANEL_WIDTH, toolGradientCss, toolTheme } from '../mapTheme';

const PANEL_TITLES: Record<RailTool, string> = {
  layers: 'Layers',
  basemap: 'Base map',
  measure: 'Measure',
  legend: 'Legend',
};

/**
 * The GIS console.
 *
 * Full-bleed map with a permanent tool rail and one slide-out panel. Only a single panel is ever
 * open: on a map, screen area *is* the product, and a console that stacks panels ends up showing
 * the operator more chrome than cartography.
 *
 * The chrome is dark in both colour schemes — see `mapTheme.ts` for why.
 */
export default function GisMapPage() {
  const { user } = useAuth();
  const { data: layers, isLoading, error } = useLayers();
  /*
   * The map's rendering instructions, composed server-side from each layer's configured style.
   *
   * This is what replaced the hard-coded style table the client used to keep: MapView adds these
   * specifications verbatim, so a layer created in Layer Management or recoloured in Layer Styles
   * draws correctly with no code change. One request for every layer, because the alternative is one
   * per layer on every page load and a map that paints its layers in whatever order the responses
   * happened to arrive.
   */
  const { data: mapStyle } = useMapStyle();
  const composed = useMemo(() => mapStyle ?? [], [mapStyle]);

  /*
   * The chrome's swatches — legend, layers panel, search results, inspection card — read a
   * module-level registry rather than take the composed style as a prop through a dozen components.
   * Filling it here, from the same composition MapView paints from, is what keeps a swatch and the
   * pixels on the map from disagreeing.
   */
  useEffect(() => {
    registerLayerSymbols(composed);
  }, [composed]);

  /*
   * The map opens on the pipe network. It is the only layer visible by default (see the layer
   * catalogue seed), and it is the layer every other asset is positioned relative to — so framing
   * it is the closest thing to "show me my system" the console can do without asking.
   *
   * The pipeline layer is found by asset type rather than by the `pipelines` code, so a tenant that
   * renamed or re-coded the layer still gets the same opening view.
   */
  const pipeLayerCode = layers?.find((l) => l.assetType === 'PIPELINE')?.code;
  const { data: pipeExtent } = useLayerExtent(pipeLayerCode);
  const focusBounds: Bounds | null = pipeExtent
    ? [pipeExtent.minLon, pipeExtent.minLat, pipeExtent.maxLon, pipeExtent.maxLat]
    : null;

  const [tool, setTool] = useState<RailTool | null>('layers');
  const [visibleCodes, setVisibleCodes] = useState<Set<string>>(new Set());
  const [baseMap, setBaseMap] = useState<BaseMapId>('street');
  const [measureMode, setMeasureMode] = useState<MeasureMode>('length');
  const [measuring, setMeasuring] = useState(false);
  const [measured, setMeasured] = useState<number | null>(null);
  const [areaValue, setAreaValue] = useState<number | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [diagnostic, setDiagnostic] = useState<string | null>(null);
  const [picked, setPicked] = useState<PickedFeature | null>(null);

  /*
   * Only one sketch mode is active at a time. `measuring` drives whichever mode is current; the
   * two booleans are derived from it + the mode so MapView (which still takes separate
   * `measuring`/`areaMeasuring` props) gets exactly one of them true.
   */
  const measuringLength = measuring && measureMode === 'length';
  const measuringArea = measuring && measureMode === 'area';

  const mapRef = useRef<MapHandle>(null);
  const seeded = useRef(false);

  // Seed visibility from the catalogue defaults, exactly once.
  if (layers && !seeded.current) {
    seeded.current = true;
    const defaults = layers.filter((l) => l.visible).map((l) => l.code);
    if (defaults.length) setVisibleCodes(new Set(defaults));
  }

  const effectiveLayers = useMemo(
    () => (layers ?? []).map((l) => ({ ...l, visible: visibleCodes.has(l.code) })),
    [layers, visibleCodes],
  );

  const toggle = useCallback((code: string, visible: boolean) => {
    setVisibleCodes((prev) => {
      const next = new Set(prev);
      if (visible) next.add(code);
      else next.delete(code);
      return next;
    });
  }, []);

  const org = user?.organization;
  /*
   * The tenant's own extent wins. The fallback is Tamil Nadu at state level, used only when an
   * organisation has no centroid recorded — opening on the service area beats opening on
   * null island, and a state-level zoom means the operator zooms in rather than out.
   */
  const center: [number, number] = org?.defaultCenter ?? [78.6569, 11.1271];
  const zoom = org?.defaultZoom ?? 7;

  const handlePick = useCallback((lonLat: [number, number], label: string) => {
    mapRef.current?.flyTo(lonLat);
    setFlash(label);
    window.setTimeout(() => setFlash(null), 2600);
  }, []);

  const panelOpen = tool !== null;

  return (
    <Box
      sx={{
        // Cancel the shell's page padding so the console runs edge to edge.
        m: { xs: -2, sm: -3 },
        height: 'calc(100vh - 64px)',
        display: 'flex',
        position: 'relative',
        overflow: 'hidden',
        bgcolor: '#0B1220',
      }}
    >
      <MapRail active={tool} onSelect={(next) => setTool((cur) => (cur === next ? null : next))} />

      {/*
        Slide-out panel. The width is animated directly rather than with MUI's horizontal
        Collapse: that component leaves its wrapper in the flow at a non-zero width, which showed
        as an empty dark band between the rail and the map when no tool was selected.
      */}
      <Box
        sx={{
          width: panelOpen ? PANEL_WIDTH : 0,
          flexShrink: 0,
          height: '100%',
          overflow: 'hidden',
          transition: 'width 220ms ease',
          borderRight: panelOpen ? `1px solid ${mapChrome.border}` : 'none',
        }}
      >
        <Stack
          sx={{
            width: PANEL_WIDTH,
            height: '100%',
            bgcolor: mapChrome.surface,
          }}
        >
          <Stack
            direction="row"
            alignItems="center"
            sx={{
              position: 'relative',
              px: 2,
              py: 1.75,
              borderBottom: `1px solid ${mapChrome.border}`,
              // A faint vertical wash of the active tool's gradient behind the header, so the
              // panel's identity matches the rail button that opened it — without dyeing the body.
              background: tool
                ? `linear-gradient(90deg, ${toolTheme(tool).soft}, transparent 70%)`
                : 'transparent',
            }}
          >
            <Box
              sx={{
                width: 4,
                height: 22,
                borderRadius: 1.5,
                mr: 1.5,
                background: tool ? toolGradientCss(tool) : mapChrome.accent,
                boxShadow: tool ? toolTheme(tool).glow : mapChrome.glow,
              }}
            />
            <Typography sx={{ flexGrow: 1, fontSize: 17, fontWeight: 700, color: mapChrome.text }}>
              {tool ? PANEL_TITLES[tool] : ''}
            </Typography>
            <Tooltip title="Collapse panel">
              <Box
                component="button"
                type="button"
                onClick={() => setTool(null)}
                aria-label="Collapse panel"
                sx={{
                  display: 'grid',
                  placeItems: 'center',
                  width: 28,
                  height: 28,
                  borderRadius: 1.5,
                  border: `1px solid ${mapChrome.border}`,
                  background: 'none',
                  color: mapChrome.textFaint,
                  cursor: 'pointer',
                  '&:hover': { color: mapChrome.text, bgcolor: 'rgba(255,255,255,0.06)' },
                }}
              >
                <CollapseIcon sx={{ fontSize: 18 }} />
              </Box>
            </Tooltip>
          </Stack>

          <Box sx={{ p: 1.75, overflowY: 'auto', flexGrow: 1 }}>
            {tool === 'layers' ? (
              <LayersPanel
                layers={effectiveLayers}
                visibleCodes={visibleCodes}
                onToggle={toggle}
              />
            ) : null}
            {tool === 'basemap' ? <BaseMapPanel value={baseMap} onChange={setBaseMap} /> : null}
            {tool === 'measure' ? (
              <MeasurePanel
                mode={measureMode}
                active={measuring}
                metres={measured}
                squareMetres={areaValue}
                onModeChange={(m) => {
                  // Switching mode clears the active sketch and its readout, so a length line and
                  // an area polygon are never on the map at once.
                  if (m !== measureMode) mapRef.current?.clearMeasurements();
                  setMeasureMode(m);
                  setMeasured(null);
                  setAreaValue(null);
                }}
                onToggle={(on) => {
                  setMeasuring(on);
                  if (!on) {
                    setMeasured(null);
                    setAreaValue(null);
                  }
                }}
                onClear={() => mapRef.current?.clearMeasurements()}
              />
            ) : null}
            {tool === 'legend' ? <LegendPanel layers={effectiveLayers} composed={composed} /> : null}
          </Box>
        </Stack>
      </Box>

      {/* Map surface */}
      <Box
        sx={{
          position: 'relative',
          flexGrow: 1,
          minWidth: 0,
          // Subtle inner vignette so floating chrome (search, zoom, pills) lifts off the
          // cartography. Non-interactive: pointer events pass straight through to the map.
          '&::after': {
            content: '""',
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
            zIndex: 4,
            background:
              'radial-gradient(120% 80% at 50% 35%, transparent 55%, rgba(7,12,24,0.35) 100%)',
          },
        }}
      >
        {isLoading ? (
          <Stack alignItems="center" justifyContent="center" sx={{ height: '100%' }}>
            <CircularProgress size={30} sx={{ color: mapChrome.accent }} />
            <Typography sx={{ mt: 1.5, fontSize: 13, color: mapChrome.textFaint }}>
              Loading map…
            </Typography>
          </Stack>
        ) : error ? (
          <Stack alignItems="center" justifyContent="center" sx={{ height: '100%', p: 3 }}>
            <Alert severity="error" variant="outlined">
              Could not load map layers. {(error as Error).message}
            </Alert>
          </Stack>
        ) : (
          <>
            <MapView
              ref={mapRef}
              layers={effectiveLayers}
              composed={composed}
              defaultCenter={center}
              defaultZoom={zoom}
              baseMap={baseMap}
              measuring={measuringLength}
              onMeasure={setMeasured}
              areaMeasuring={measuringArea}
              onArea={setAreaValue}
              onDiagnostic={setDiagnostic}
              focusBounds={focusBounds}
              onPick={setPicked}
              selected={picked}
            />

            {picked ? (
              <InspectionCard
                // Remounting per asset resets the card's own state (copy confirmation, scroll)
                // instead of carrying one pipe's UI over onto the next.
                key={picked.assetId}
                feature={picked}
                layers={effectiveLayers}
                onClose={() => setPicked(null)}
                onZoom={(lonLat) => mapRef.current?.flyTo(lonLat, 17)}
              />
            ) : null}

            <MapSearch onPick={handlePick} />

            {diagnostic ? (
              <Alert
                severity="error"
                variant="filled"
                onClose={() => setDiagnostic(null)}
                sx={{
                  position: 'absolute',
                  bottom: 56,
                  left: '50%',
                  transform: 'translateX(-50%)',
                  zIndex: 7,
                  maxWidth: 'min(560px, calc(100% - 48px))',
                }}
              >
                Map error: {diagnostic}
              </Alert>
            ) : null}

            {/* Zoom / recentre stack, top-right — slides aside to make room for the card */}
            <Stack
              sx={{
                position: 'absolute',
                top: 16,
                right: 16,
                // The inspection card claims the top-right corner. Rather than stacking the
                // controls under it (unreachable) or docking the card elsewhere (worse place for
                // the thing you just clicked), the controls step left by the card's width.
                transform: picked ? { xs: 'none', sm: 'translateX(-360px)' } : 'none',
                transition: 'transform 220ms ease',
                zIndex: 6,
                borderRadius: 2.5,
                overflow: 'hidden',
                bgcolor: mapChrome.floating,
                border: `1px solid ${mapChrome.border}`,
                backdropFilter: 'blur(10px)',
                boxShadow: mapChrome.shadow,
                // Accent gradient hairline on the top edge for a lit, floating feel.
                '&::before': {
                  content: '""',
                  position: 'absolute',
                  left: 0,
                  right: 0,
                  top: 0,
                  // '1px': a bare 1 is a fraction in `sx`, i.e. 100% — a full-height wash over
                  // the zoom stack rather than a lit top edge.
                  height: '1px',
                  background: 'linear-gradient(90deg, transparent, rgba(103,232,249,0.6), transparent)',
                  pointerEvents: 'none',
                },
              }}
            >
              <MapControlButton label="Zoom in" onClick={() => mapRef.current?.zoomIn()}>
                <AddIcon sx={{ fontSize: 19 }} />
              </MapControlButton>
              <MapControlButton label="Zoom out" onClick={() => mapRef.current?.zoomOut()}>
                <RemoveIcon sx={{ fontSize: 19 }} />
              </MapControlButton>
              <MapControlButton
                label="Recentre"
                // Back to the view the console opened on: the network if this tenant has one,
                // the organisation's home extent otherwise.
                onClick={() =>
                  focusBounds
                    ? mapRef.current?.fitBounds(focusBounds)
                    : mapRef.current?.flyTo(center, zoom)
                }
                last
              >
                <RecenterIcon sx={{ fontSize: 17 }} />
              </MapControlButton>
            </Stack>

            {/* Live measurement readout for the active mode, visible without the panel open */}
            {(() => {
              const value = measureMode === 'area' ? areaValue : measured;
              if (!measuring || value == null) return null;
              const text =
                measureMode === 'area'
                  ? value < 10_000
                    ? `${value.toFixed(0)} m²`
                    : value < 1_000_000
                      ? `${(value / 10_000).toFixed(2)} ha`
                      : `${(value / 1_000_000).toFixed(2)} km²`
                  : value >= 1000
                    ? `${(value / 1000).toFixed(2)} km`
                    : `${value.toFixed(0)} m`;
              return (
                <Box
                  sx={{
                    position: 'absolute',
                    bottom: 16,
                    left: '50%',
                    transform: 'translateX(-50%)',
                    zIndex: 6,
                    px: 2,
                    py: 1,
                    borderRadius: 2.5,
                    bgcolor: mapChrome.floating,
                    border: `1px solid ${mapChrome.accent}`,
                    backdropFilter: 'blur(10px)',
                    boxShadow: `${mapChrome.shadowLg}, ${mapChrome.glow}`,
                    color: mapChrome.text,
                    fontSize: 14,
                    fontWeight: 700,
                  }}
                >
                  {text}
                </Box>
              );
            })()}

            {/* Confirms a search jump landed, since a fly-to with no prior context is easy to miss */}
            {flash ? (
              <Box
                role="status"
                sx={{
                  position: 'absolute',
                  top: 84,
                  left: '50%',
                  transform: 'translateX(-50%)',
                  zIndex: 6,
                  px: 2,
                  py: 0.75,
                  borderRadius: 2,
                  bgcolor: mapChrome.accentSoft,
                  border: `1px solid ${mapChrome.accent}`,
                  boxShadow: mapChrome.glow,
                  color: mapChrome.text,
                  fontSize: 12.5,
                  whiteSpace: 'nowrap',
                }}
              >
                Centred on {flash}
              </Box>
            ) : null}

            {effectiveLayers.length === 0 ? (
              <Typography
                sx={{
                  position: 'absolute',
                  inset: 0,
                  display: 'grid',
                  placeItems: 'center',
                  pointerEvents: 'none',
                  fontSize: 13,
                  color: mapChrome.textFaint,
                }}
              >
                No layers configured for {org?.name}.
              </Typography>
            ) : null}
          </>
        )}
      </Box>
    </Box>
  );
}

function MapControlButton({
  label,
  onClick,
  last,
  children,
}: {
  label: string;
  onClick: () => void;
  last?: boolean;
  children: ReactNode;
}) {
  return (
    <Tooltip title={label} placement="left">
      <Box
        component="button"
        type="button"
        onClick={onClick}
        aria-label={label}
        sx={{
          width: 38,
          height: 36,
          display: 'grid',
          placeItems: 'center',
          border: 0,
          borderBottom: last ? 0 : `1px solid ${mapChrome.border}`,
          background: 'none',
          color: mapChrome.textMuted,
          cursor: 'pointer',
          transition: 'color 160ms ease, background-color 160ms ease, box-shadow 160ms ease',
          '&:hover': { color: mapChrome.accent, bgcolor: 'rgba(103,232,249,0.10)', boxShadow: `inset 0 0 12px ${mapChrome.accentSoft}` },
        }}
      >
        {children}
      </Box>
    </Tooltip>
  );
}
