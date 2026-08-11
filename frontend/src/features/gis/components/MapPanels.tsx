import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Box, Collapse, Stack, Switch, Typography } from '@mui/material';
import ChevronIcon from '@mui/icons-material/ChevronRightOutlined';
import PointIcon from '@mui/icons-material/PlaceOutlined';
import LineIcon from '@mui/icons-material/TimelineOutlined';
import AreaIcon from '@mui/icons-material/HexagonOutlined';
import SatelliteIcon from '@mui/icons-material/SatelliteAltOutlined';
import StreetIcon from '@mui/icons-material/MapOutlined';
import HybridIcon from '@mui/icons-material/LayersOutlined';
import { mapChrome, toolGradientCss, toolTheme } from '../mapTheme';
import { BASE_MAPS, type BaseMapId } from '../basemaps';
import { layerSymbol, type SymbolShape } from '../layerStyle';
import type { ComposedMapLayer } from '@/features/layers/types';
import type { LayerSummary } from '../api/gisApi';

/**
 * Panel bodies for the map console rail.
 *
 * Each is a plain content block; the page owns the sliding container, the heading and the
 * collapse control, so a new tool is a component plus a rail entry and nothing else.
 */

// --- Layers ------------------------------------------------------------------------------

/**
 * A legend/row swatch whose *shape* matches the geometry the layer paints on the map.
 *
 * A round dot for every layer is a legend that lies — a pipeline is a line and a tank is a
 * hexagon, and saying so here means an operator can match a symbol on the map to its key without
 * reading the label. The shape comes straight from `layerSymbol()`, so the legend can never drift
 * from the renderer's palette again.
 */
function SymbolSwatch({ code, size = 14 }: { code: string; size?: number }) {
  const sym = layerSymbol(code);
  const stroke = sym.glow;
  return (
    <Box
      aria-hidden
      sx={{
        width: size,
        height: size,
        flexShrink: 0,
        display: 'grid',
        placeItems: 'center',
        color: sym.colour,
        // Soft glow so the swatch has the same luminance as the point halo on the map.
        filter: `drop-shadow(0 0 4px ${sym.glow}66)`,
      }}
    >
      <SymbolGlyph shape={sym.shape} colour={sym.colour} stroke={stroke} size={size} />
    </Box>
  );
}

/** Draws the actual glyph for a shape, using the same colour the map paints it. */
function SymbolGlyph({
  shape,
  colour,
  stroke,
  size,
}: {
  shape: ReturnType<typeof layerSymbol>['shape'];
  colour: string;
  stroke: string;
  size: number;
}) {
  switch (shape) {
    case 'line':
      return (
        <Box
          sx={{
            width: size,
            height: size,
            display: 'grid',
            placeItems: 'center',
          }}
        >
          {/* A short, slightly tapered dash — what a pipeline looks like mid-span. */}
          <Box
            sx={{
              width: size,
              height: Math.max(2, size * 0.16),
              borderRadius: 2,
              background: colour,
              boxShadow: `0 0 6px ${stroke}88`,
            }}
          />
        </Box>
      );
    case 'fill':
      return (
        <Box
          sx={{
            width: size,
            height: size,
            borderRadius: 1,
            background: colour,
            opacity: 0.4,
            border: `1.5px solid ${colour}`,
          }}
        />
      );
    case 'diamond':
      return <Box sx={{ width: size * 0.8, height: size * 0.8, background: colour, transform: 'rotate(45deg)', borderRadius: 1 }} />;
    case 'square':
      return <Box sx={{ width: size * 0.85, height: size * 0.85, background: colour, borderRadius: 1.5 }} />;
    case 'triangle':
      return (
        <Box
          component="svg"
          viewBox="0 0 100 100"
          sx={{ width: size, height: size, display: 'block' }}
        >
          <polygon points="50,8 92,88 8,88" fill={colour} stroke={stroke} strokeWidth={6} strokeLinejoin="round" />
        </Box>
      );
    case 'hexagon':
      return (
        <Box
          component="svg"
          viewBox="0 0 100 100"
          sx={{ width: size, height: size, display: 'block' }}
        >
          <polygon points="50,4 93,27 93,73 50,96 7,73 7,27" fill={colour} stroke={stroke} strokeWidth={6} strokeLinejoin="round" />
        </Box>
      );
    case 'star':
      return (
        <Box component="svg" viewBox="0 0 100 100" sx={{ width: size, height: size, display: 'block' }}>
          <polygon
            points="50,4 61,37 96,37 68,58 79,92 50,71 21,92 32,58 4,37 39,37"
            fill={colour}
            stroke={stroke}
            strokeWidth={5}
            strokeLinejoin="round"
          />
        </Box>
      );
    case 'pin':
      return (
        <Box component="svg" viewBox="0 0 100 100" sx={{ width: size, height: size, display: 'block' }}>
          <path
            d="M50 6a30 30 0 0 0-30 30c0 22 30 58 30 58s30-36 30-58A30 30 0 0 0 50 6z"
            fill={colour}
            stroke={stroke}
            strokeWidth={5}
            strokeLinejoin="round"
          />
        </Box>
      );
    case 'circle':
    default:
      return <Box sx={{ width: size * 0.85, height: size * 0.85, borderRadius: '50%', background: colour, border: `1.5px solid ${stroke}` }} />;
  }
}

/**
 * Groups layers by geometry family.
 *
 * The catalogue is a flat list, but an operator thinks in kinds of thing — points they inspect,
 * lines they trace, areas they report on. Grouping mirrors that, and keeps the panel legible as
 * the catalogue grows past what fits on one screen.
 *
 * The pipe network leads: it is the only layer the console opens with, the one the map zooms to,
 * and the spine every other asset is positioned against — so it is what the panel should offer
 * first rather than something the operator scrolls past the point layers to reach.
 */
const GROUPS: { id: string; title: string; icon: ReactNode; types: string[] }[] = [
  { id: 'network', title: 'Pipe Network', icon: <LineIcon />, types: ['PIPELINE'] },
  {
    id: 'points',
    title: 'Point assets',
    icon: <PointIcon />,
    types: ['METER', 'VALVE', 'HYDRANT', 'SENSOR', 'SERVICE_CONNECTION'],
  },
  {
    id: 'facilities',
    title: 'Facilities',
    icon: <AreaIcon />,
    types: ['TANK', 'RESERVOIR', 'PUMP_STATION', 'OPEN_WELL', 'BORE_WELL'],
  },
  { id: 'zones', title: 'Zones & boundaries', icon: <AreaIcon />, types: ['DMA', 'PANCHAYAT'] },
];

export function LayersPanel({
  layers,
  visibleCodes,
  onToggle,
}: {
  layers: LayerSummary[];
  visibleCodes: Set<string>;
  onToggle: (code: string, visible: boolean) => void;
}) {
  const grouped = useMemo(() => {
    const claimed = new Set<string>();
    const groups = GROUPS.map((group) => {
      const members = layers.filter((l) => group.types.includes(l.assetType));
      members.forEach((m) => claimed.add(m.code));
      return { ...group, members };
    }).filter((g) => g.members.length > 0);

    // Anything the map above does not classify still has to be reachable.
    const rest = layers.filter((l) => !claimed.has(l.code));
    if (rest.length) {
      groups.push({ id: 'other', title: 'Other layers', icon: <PointIcon />, types: [], members: rest });
    }
    return groups;
  }, [layers]);

  if (layers.length === 0) {
    return <EmptyNote>No layers are configured for your organisation yet.</EmptyNote>;
  }

  return (
    <Stack spacing={1.25}>
      {grouped.map((group) => (
        <LayerGroup
          key={group.id}
          title={group.title}
          icon={group.icon}
          count={group.members.filter((m) => visibleCodes.has(m.code)).length}
          total={group.members.length}
        >
          {group.members.map((layer) => (
            <Stack
              key={layer.code}
              direction="row"
              alignItems="center"
              spacing={1}
              sx={{ px: 1.25, py: 0.5, borderRadius: 1.5, '&:hover': { bgcolor: 'rgba(255,255,255,0.04)' } }}
            >
              <SymbolSwatch code={layer.code} size={14} />
              <Typography
                variant="body2"
                sx={{ flexGrow: 1, color: mapChrome.textMuted, fontSize: 13 }}
              >
                {layer.title}
              </Typography>
              <Switch
                size="small"
                checked={visibleCodes.has(layer.code)}
                onChange={(_, checked) => onToggle(layer.code, checked)}
                inputProps={{ 'aria-label': `Show ${layer.title}` }}
              />
            </Stack>
          ))}
        </LayerGroup>
      ))}
    </Stack>
  );
}

function LayerGroup({
  title,
  icon,
  count,
  total,
  children,
}: {
  title: string;
  icon: ReactNode;
  count: number;
  total: number;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(true);

  return (
    <Box
      sx={{
        borderRadius: 2.5,
        bgcolor: mapChrome.card,
        border: `1px solid ${mapChrome.border}`,
        overflow: 'hidden',
      }}
    >
      <Box
        component="button"
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        sx={{
          width: '100%',
          border: 0,
          background: 'none',
          cursor: 'pointer',
          display: 'flex',
          alignItems: 'center',
          gap: 1.25,
          px: 1.25,
          py: 1.25,
          color: mapChrome.text,
          '&:hover': { bgcolor: mapChrome.cardHover },
        }}
      >
        <Box
          aria-hidden
          sx={{
            width: 32,
            height: 32,
            borderRadius: 1.5,
            display: 'grid',
            placeItems: 'center',
            flexShrink: 0,
            bgcolor: mapChrome.accentSoft,
            color: mapChrome.accent,
            '& .MuiSvgIcon-root': { fontSize: 18 },
          }}
        >
          {icon}
        </Box>
        <Typography
          sx={{
            flexGrow: 1,
            textAlign: 'left',
            fontSize: 12.5,
            fontWeight: 700,
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
          }}
        >
          {title}
        </Typography>
        <Typography variant="caption" sx={{ color: mapChrome.textFaint, fontSize: 11 }}>
          {count}/{total}
        </Typography>
        <ChevronIcon
          sx={{
            fontSize: 18,
            color: mapChrome.textFaint,
            transition: 'transform 180ms ease',
            transform: open ? 'rotate(90deg)' : 'none',
          }}
        />
      </Box>
      <Collapse in={open}>
        <Stack spacing={0.25} sx={{ px: 0.75, pb: 1 }}>
          {children}
        </Stack>
      </Collapse>
    </Box>
  );
}

// --- Base map ----------------------------------------------------------------------------

/**
 * A pure-CSS schematic thumbnail that hints at what each base map looks like, without fetching
 * a single tile. Each id paints its own miniature: Street is a dark grid of cyan road-lines,
 * Satellite is a green→brown terrain gradient, Hybrid layers a place-name dot over the terrain.
 *
 * Real tile previews would need a network call and a configured tile source — both of which can
 * fail on a cold checkout, leaving a broken-image card. A schematic can never break, and it
 * communicates the *kind* of map, which is all the picker needs to do.
 */
function BaseMapThumb({ id }: { id: BaseMapId }) {
  if (id === 'satellite') {
    return (
      <Box
        aria-hidden
        sx={{
          width: '100%',
          height: '100%',
          background:
            'linear-gradient(135deg, #1e3a2a 0%, #2d5a3d 35%, #4a6b3a 55%, #6b6233 75%, #3d2f1e 100%)',
          position: 'relative',
          overflow: 'hidden',
          // A soft "terrain" mottle so it reads as imagery, not a flat gradient.
          '&::after': {
            content: '""',
            position: 'absolute',
            inset: 0,
            background:
              'radial-gradient(ellipse at 30% 40%, rgba(120,180,90,0.30), transparent 55%), radial-gradient(ellipse at 75% 70%, rgba(90,70,40,0.35), transparent 50%)',
          },
        }}
      />
    );
  }
  if (id === 'hybrid') {
    return (
      <Box
        aria-hidden
        sx={{
          width: '100%',
          height: '100%',
          background:
            'linear-gradient(135deg, #1e3a2a 0%, #2d5a3d 40%, #4a6b3a 60%, #6b6233 85%, #3d2f1e 100%)',
          position: 'relative',
          overflow: 'hidden',
          '&::before': {
            content: '""',
            position: 'absolute',
            inset: 0,
            background:
              'linear-gradient(90deg, rgba(103,232,249,0.55) 1px, transparent 1px) 0 0 / 33% 100%, linear-gradient(0deg, rgba(103,232,249,0.35) 1px, transparent 1px) 0 0 / 100% 50%',
          },
          '&::after': {
            content: '""',
            position: 'absolute',
            left: '38%',
            top: '34%',
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: '#67E8F9',
            boxShadow: '0 0 0 3px rgba(103,232,249,0.25), 0 0 10px rgba(103,232,249,0.7)',
          },
        }}
      />
    );
  }
  // street (default)
  return (
    <Box
      aria-hidden
      sx={{
        width: '100%',
        height: '100%',
        background: '#0B1220',
        position: 'relative',
        overflow: 'hidden',
        // A road grid: primary avenues + secondary cross-streets, in the aqua accent family.
        '&::before': {
          content: '""',
          position: 'absolute',
          inset: 0,
          background:
            'linear-gradient(90deg, rgba(103,232,249,0.55) 1.5px, transparent 1.5px) 0 0 / 38% 100%, linear-gradient(0deg, rgba(103,232,249,0.35) 1px, transparent 1px) 0 0 / 100% 33%',
        },
        '&::after': {
          content: '""',
          position: 'absolute',
          left: 0,
          right: 0,
          top: '46%',
          height: 2.5,
          background: 'linear-gradient(90deg, rgba(59,130,246,0.85), rgba(103,232,249,0.85))',
          boxShadow: '0 0 8px rgba(103,232,249,0.5)',
        },
      }}
    />
  );
}

/** Icons live here, not in `basemaps.ts` — that module is style data and stays JSX-free. */
const BASE_MAP_ICONS: Record<BaseMapId, ReactNode> = {
  street: <StreetIcon />,
  satellite: <SatelliteIcon />,
  hybrid: <HybridIcon />,
};

export function BaseMapPanel({
  value,
  onChange,
}: {
  value: BaseMapId;
  onChange: (next: BaseMapId) => void;
}) {
  const theme = toolTheme('basemap');
  return (
    <Stack spacing={1.25}>
      {BASE_MAPS.map((option) => {
        const selected = option.id === value;
        return (
          <Box
            key={option.id}
            component="button"
            type="button"
            onClick={() => onChange(option.id)}
            aria-pressed={selected}
            sx={{
              display: 'flex',
              gap: 0,
              width: '100%',
              textAlign: 'left',
              cursor: 'pointer',
              p: 0,
              overflow: 'hidden',
              borderRadius: 2.5,
              bgcolor: mapChrome.card,
              border: `1px solid ${selected ? theme.from : mapChrome.border}`,
              boxShadow: selected ? theme.glow : 'none',
              color: mapChrome.text,
              transition: 'border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease',
              '&:hover': { bgcolor: mapChrome.card, borderColor: selected ? theme.from : mapChrome.borderStrong },
              '&:hover .ag-basemap-thumb': { transform: 'scale(1.04)' },
            }}
          >
            {/* Thumbnail — the schematic preview, with a gradient selected ring when active. */}
            <Box
              sx={{
                position: 'relative',
                width: 76,
                flexShrink: 0,
                borderRight: `1px solid ${mapChrome.border}`,
                overflow: 'hidden',
              }}
            >
              <Box
                className="ag-basemap-thumb"
                sx={{ width: '100%', height: '100%', transition: 'transform 220ms ease' }}
              >
                <BaseMapThumb id={option.id} />
              </Box>
              {selected ? (
                <Box
                  aria-hidden
                  sx={{
                    position: 'absolute',
                    inset: 0,
                    border: `2px solid ${theme.from}`,
                    boxShadow: `inset 0 0 14px ${theme.from}66`,
                    pointerEvents: 'none',
                  }}
                />
              ) : null}
            </Box>
            <Stack direction="row" alignItems="center" spacing={1.25} sx={{ px: 1.5, py: 1.5, flexGrow: 1 }}>
              <Box
                sx={{
                  color: selected ? theme.from : mapChrome.textFaint,
                  display: 'grid',
                  '& .MuiSvgIcon-root': { fontSize: 22 },
                }}
              >
                {BASE_MAP_ICONS[option.id]}
              </Box>
              <Box sx={{ minWidth: 0 }}>
                <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{option.title}</Typography>
                <Typography variant="caption" sx={{ color: mapChrome.textFaint, fontSize: 11, display: 'block' }}>
                  {option.caption}
                </Typography>
              </Box>
            </Stack>
          </Box>
        );
      })}
    </Stack>
  );
}

// --- Measure (length + area) ------------------------------------------------------------

export type MeasureMode = 'length' | 'area';

export function MeasurePanel({
  mode,
  active,
  metres,
  squareMetres,
  onModeChange,
  onToggle,
  onClear,
}: {
  mode: MeasureMode;
  active: boolean;
  metres: number | null;
  squareMetres: number | null;
  onModeChange: (mode: MeasureMode) => void;
  onToggle: (on: boolean) => void;
  onClear: () => void;
}) {
  const theme = toolTheme('measure');
  const value = mode === 'area' ? squareMetres : metres;
  const help =
    mode === 'area'
      ? 'Click to add polygon vertices, double-click to finish.'
      : 'Click to add points along the route, double-click to finish.';
  const label = mode === 'area' ? 'Enclosed area' : 'Measured length';

  return (
    <Stack spacing={1.5}>
      {/* Length | Area mode toggle. Segmented control: only one sketch mode is active at a time,
          and switching mode clears the active sketch so a length line is never confused with an
          area polygon. */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 0.5,
          p: 0.5,
          borderRadius: 2.5,
          bgcolor: mapChrome.card,
          border: `1px solid ${mapChrome.border}`,
        }}
      >
        {(['length', 'area'] as const).map((m) => {
          const selected = mode === m;
          return (
            <Box
              key={m}
              component="button"
              type="button"
              onClick={() => onModeChange(m)}
              aria-pressed={selected}
              sx={{
                border: 0,
                borderRadius: 1.5,
                py: 0.85,
                cursor: 'pointer',
                fontFamily: 'inherit',
                fontSize: 13,
                fontWeight: 700,
                color: selected ? '#0B1220' : mapChrome.textFaint,
                background: selected ? toolGradientCss('measure') : 'transparent',
                boxShadow: selected ? theme.glow : 'none',
                transition: 'background 160ms ease, color 160ms ease, box-shadow 160ms ease',
                '&:hover': selected ? {} : { color: mapChrome.text, bgcolor: 'rgba(255,255,255,0.06)' },
              }}
            >
              {m === 'length' ? 'Length' : 'Area'}
            </Box>
          );
        })}
      </Box>

      <Box
        sx={{
          borderRadius: 2.5,
          bgcolor: mapChrome.card,
          border: `1px solid ${active ? theme.from : mapChrome.border}`,
          boxShadow: active ? theme.glow : 'none',
          p: 1.5,
          transition: 'border-color 160ms ease, box-shadow 160ms ease',
        }}
      >
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography sx={{ flexGrow: 1, fontSize: 13.5, fontWeight: 600, color: mapChrome.text }}>
            {mode === 'area' ? 'Area tool' : 'Distance tool'}
          </Typography>
          <Switch
            size="small"
            checked={active}
            onChange={(_, checked) => onToggle(checked)}
            inputProps={{ 'aria-label': `Enable ${mode} measurement` }}
          />
        </Stack>
        <Typography variant="caption" sx={{ color: mapChrome.textFaint, fontSize: 11.5 }}>
          {help}
        </Typography>
      </Box>

      {/* Big readout: a gradient border + glow the moment a value exists, so a finished measurement
          announces itself instead of looking like another empty card. */}
      <Box
        sx={{
          position: 'relative',
          borderRadius: 2.5,
          p: 1.75,
          background: value != null ? theme.soft : mapChrome.card,
          // Use a pseudo-element as the gradient border so the fill and the border can differ.
          '&::before': value != null
            ? {
                content: '""',
                position: 'absolute',
                inset: 0,
                borderRadius: 2.5,
                padding: 1,
                background: toolGradientCss('measure'),
                WebkitMask: 'linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0)',
                WebkitMaskComposite: 'xor',
                maskComposite: 'exclude',
                pointerEvents: 'none',
              }
            : undefined,
          boxShadow: value != null ? theme.glow : 'none',
        }}
      >
        <Typography variant="caption" sx={{ color: mapChrome.textFaint, fontSize: 11 }}>
          {label}
        </Typography>
        <Typography sx={{ fontSize: 24, fontWeight: 700, color: mapChrome.text, lineHeight: 1.3 }}>
          {value == null ? '—' : mode === 'area' ? formatArea(value) : formatLength(value)}
        </Typography>
      </Box>

      <Box
        component="button"
        type="button"
        onClick={onClear}
        disabled={value == null}
        sx={{
          border: `1px solid ${mapChrome.border}`,
          borderRadius: 2,
          bgcolor: 'transparent',
          color: value == null ? mapChrome.textFaint : mapChrome.text,
          cursor: value == null ? 'default' : 'pointer',
          opacity: value == null ? 0.5 : 1,
          py: 1,
          fontSize: 13,
          fontWeight: 600,
          fontFamily: 'inherit',
          '&:hover': value == null ? {} : { bgcolor: 'rgba(255,255,255,0.06)', borderColor: mapChrome.borderStrong },
        }}
      >
        Clear
      </Box>
    </Stack>
  );
}

/** Metres below a kilometre, kilometres above — matching how field staff quote distances. */
function formatLength(metres: number): string {
  return metres >= 1000 ? `${(metres / 1000).toFixed(2)} km` : `${metres.toFixed(0)} m`;
}

/**
 * Square metres below 1 ha, hectares below 1 km², square kilometres above — matching how field
 * staff and surveyors quote parcel and DMA sizes.
 */
function formatArea(squareMetres: number): string {
  if (squareMetres < 10_000) return `${squareMetres.toFixed(0)} m²`;
  if (squareMetres < 1_000_000) return `${(squareMetres / 10_000).toFixed(2)} ha`;
  return `${(squareMetres / 1_000_000).toFixed(2)} km²`;
}

// --- Legend ------------------------------------------------------------------------------

/**
 * The legend.
 *
 * Entries come from the server's composed style rather than from a palette this file keeps. That is
 * what lets a classified layer explain itself: a layer coloured by `status` lists Active, Inactive
 * and Faulty with the colours the map is actually painting, because both are derived from the same
 * expression rather than described twice.
 *
 * A layer whose style has not composed yet — or a tenant whose map style has not loaded — falls back
 * to a single row with the layer's title, which is what the legend has always shown.
 */
export function LegendPanel({
  layers,
  composed,
}: {
  layers: LayerSummary[];
  composed?: ComposedMapLayer[];
}) {
  const shown = layers.filter((l) => l.visible);
  if (shown.length === 0) {
    return <EmptyNote>Turn on a layer to see its symbol here.</EmptyNote>;
  }
  const byCode = new Map((composed ?? []).map((c) => [c.code, c]));

  return (
    <Stack spacing={1.25}>
      {shown.map((layer) => {
        const entries = byCode.get(layer.code)?.legend ?? [];
        /*
         * One entry means the style draws every feature the same way, so the layer's own title is
         * the honest caption — repeating it above a single class would be a heading for a list of
         * one. More than one means the style classifies, and then the title is a heading and the
         * classes are the legend.
         */
        const classified = entries.length > 1;
        return (
          <Stack
            key={layer.code}
            spacing={0.75}
            sx={{
              px: 1.5,
              py: 1.25,
              borderRadius: 2,
              bgcolor: mapChrome.card,
              border: `1px solid ${mapChrome.border}`,
            }}
          >
            {classified ? (
              <>
                <Typography sx={{ fontSize: 12, fontWeight: 700, color: mapChrome.text }}>
                  {layer.title}
                </Typography>
                {entries.map((entry) => (
                  <Stack key={entry.label} direction="row" alignItems="center" spacing={1.25}>
                    <SymbolGlyph
                      shape={entry.shape as SymbolShape}
                      colour={entry.colour}
                      stroke={entry.colour}
                      size={14}
                    />
                    <Typography sx={{ fontSize: 12.5, color: mapChrome.textMuted }}>
                      {entry.label}
                    </Typography>
                  </Stack>
                ))}
              </>
            ) : (
              <Stack direction="row" alignItems="center" spacing={1.25}>
                <SymbolSwatch code={layer.code} size={16} />
                <Typography sx={{ fontSize: 13, color: mapChrome.textMuted }}>
                  {layer.title}
                </Typography>
              </Stack>
            )}
          </Stack>
        );
      })}
    </Stack>
  );
}

function EmptyNote({ children }: { children: ReactNode }) {
  return (
    <Typography variant="body2" sx={{ color: mapChrome.textFaint, fontSize: 13, px: 0.5 }}>
      {children}
    </Typography>
  );
}
