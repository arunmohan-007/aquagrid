import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Box, Skeleton, Stack, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import CloseIcon from '@mui/icons-material/CloseOutlined';
import CopyIcon from '@mui/icons-material/ContentCopyOutlined';
import CheckIcon from '@mui/icons-material/CheckOutlined';
import OpenIcon from '@mui/icons-material/OpenInNewOutlined';
import TargetIcon from '@mui/icons-material/CenterFocusStrongOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useAsset } from '@/features/assets/hooks/useAssets';
import { ASSET_TYPE_LABELS } from '@/features/assets/labels';
import type { AssetStatus, AssetType } from '@/features/assets/types';
import { severity } from '@/app/theme/palette';
import { mapChrome } from '../mapTheme';
import { layerSymbol } from '../layerStyle';
import { usePipelineDetail } from '../hooks/useGis';
import type { PickedFeature } from './MapView';
import type { LayerSummary } from '../api/gisApi';

/**
 * The asset inspection card.
 *
 * Opens on a map click and answers, in one glance, the questions an operator actually asks of a
 * pipe: what is it, is it in service, how long is it, what is it made of, and where am I looking.
 *
 * Three sources feed it, deliberately in that order:
 *  1. the vector tile's own properties, which are already in memory when the click lands, so the
 *     card appears with a real title instantly rather than as an empty box with a spinner;
 *  2. the asset record (`GET /assets/{id}`), which brings status, dates, the imported attribute
 *     bag and the geodesic length computed from the geometry;
 *  3. the pipeline engineering record (`GET /assets/{id}/pipeline`), which most imported networks
 *     simply do not have — its absence is a normal state and is rendered as one, not as an error.
 *
 * Every section is conditional. A card that renders "—" against eight labels looks broken; a card
 * that shows four facts it actually has looks authoritative.
 */
export function InspectionCard({
  feature,
  layers,
  onClose,
  onZoom,
}: {
  feature: PickedFeature;
  layers: LayerSummary[];
  onClose: () => void;
  onZoom: (lngLat: [number, number]) => void;
}) {
  const { hasPermission } = useAuth();
  const canReadAssets = hasPermission('gis:asset:read');

  const assetQuery = useAsset(canReadAssets ? feature.assetId : undefined);
  const asset = assetQuery.data;

  const isPipeline = (asset?.assetType ?? feature.assetType) === 'PIPELINE';
  const pipelineQuery = usePipelineDetail(isPipeline && canReadAssets ? feature.assetId : undefined);
  const pipeline = pipelineQuery.data;

  const symbol = layerSymbol(feature.layerCode);
  const layerTitle = layers.find((l) => l.code === feature.layerCode)?.title ?? feature.layerCode;

  // Esc closes, matching every other dismissible surface in the product.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const title = asset?.name || feature.name || asset?.assetCode || feature.assetCode || 'Asset';
  const code = asset?.assetCode ?? feature.assetCode;
  const status = (asset?.status ?? feature.status) as AssetStatus | null;

  /*
   * Length comes from the asset record, which derives it from the authoritative geometry. The tile
   * geometry is clipped per tile and simplified per zoom, so measuring it in the browser would give
   * a different answer at every zoom level — and a length that changes as you zoom is worse than no
   * length at all. `pipeline.lengthM` (the engineering record's own figure) wins when present,
   * since that is a surveyed value rather than a computed one.
   */
  const lengthM = pipeline?.lengthM ?? asset?.lengthM ?? null;

  const attributes = Object.entries(asset?.attributes ?? {}).filter(
    ([, value]) => value !== null && value !== undefined && value !== '',
  );

  return (
    <Box
      role="dialog"
      aria-label={`Details for ${title}`}
      sx={{
        position: 'absolute',
        top: 16,
        right: 16,
        zIndex: 8,
        width: { xs: 'calc(100% - 32px)', sm: 348 },
        maxHeight: 'calc(100% - 32px)',
        display: 'flex',
        flexDirection: 'column',
        borderRadius: 3,
        overflow: 'hidden',
        bgcolor: mapChrome.floating,
        border: `1px solid ${mapChrome.borderStrong}`,
        backdropFilter: 'blur(14px)',
        boxShadow: mapChrome.shadowLg,
        // Lit top edge in the layer's own colour, so the card is visibly *this* layer's card.
        '&::before': {
          content: '""',
          position: 'absolute',
          insetInline: 0,
          top: 0,
          height: '2px',
          background: `linear-gradient(90deg, transparent, ${symbol.glow}, transparent)`,
          pointerEvents: 'none',
        },
      }}
    >
      {/* ---- Header ------------------------------------------------------------------ */}
      <Stack
        sx={{
          px: 2,
          pt: 1.75,
          pb: 1.5,
          borderBottom: `1px solid ${mapChrome.border}`,
          background: `linear-gradient(160deg, ${symbol.colour}22, transparent 70%)`,
        }}
      >
        <Stack direction="row" alignItems="flex-start" spacing={1}>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Stack direction="row" alignItems="center" spacing={0.75} sx={{ mb: 0.5 }}>
              <Box
                aria-hidden
                sx={{
                  width: 14,
                  height: 3,
                  borderRadius: 2,
                  flexShrink: 0,
                  background: symbol.colour,
                  boxShadow: `0 0 8px ${symbol.glow}`,
                }}
              />
              <Typography
                sx={{
                  fontSize: 10.5,
                  fontWeight: 700,
                  letterSpacing: '0.10em',
                  textTransform: 'uppercase',
                  color: mapChrome.textFaint,
                }}
              >
                {layerTitle}
              </Typography>
            </Stack>
            <Typography
              sx={{
                fontSize: 17,
                fontWeight: 700,
                lineHeight: 1.25,
                color: mapChrome.text,
                overflowWrap: 'anywhere',
              }}
            >
              {title}
            </Typography>
            <Stack direction="row" alignItems="center" spacing={1} sx={{ mt: 0.75 }} flexWrap="wrap" useFlexGap>
              {code ? (
                <Typography
                  sx={{
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                    fontSize: 11.5,
                    color: mapChrome.textMuted,
                  }}
                >
                  {code}
                </Typography>
              ) : null}
              {status ? <StatusPill status={status} /> : null}
            </Stack>
          </Box>
          <IconAction label="Close" onClick={onClose}>
            <CloseIcon sx={{ fontSize: 17 }} />
          </IconAction>
        </Stack>
      </Stack>

      {/* ---- Body -------------------------------------------------------------------- */}
      <Box sx={{ overflowY: 'auto', px: 2, py: 1.75, flexGrow: 1 }}>
        {assetQuery.isPending && canReadAssets ? (
          <MetricsSkeleton />
        ) : (
          <Stack spacing={2}>
            <MetricGrid>
              {lengthM != null ? <Metric label="Length" value={formatLength(lengthM)} lead /> : null}
              {pipeline?.diameterMm != null ? (
                <Metric label="Diameter" value={`${formatNumber(pipeline.diameterMm)} mm`} lead />
              ) : null}
              {pipeline?.material ? <Metric label="Material" value={pipeline.material} /> : null}
              {pipeline?.pressureClass != null ? (
                <Metric label="Pressure class" value={formatNumber(pipeline.pressureClass)} />
              ) : null}
              {pipeline?.flowDirection ? (
                <Metric label="Flow" value={titleCase(pipeline.flowDirection)} />
              ) : null}
              {pipeline?.roughness != null ? (
                <Metric label="Roughness" value={formatNumber(pipeline.roughness)} />
              ) : null}
              {asset?.installDate ? <Metric label="Installed" value={asset.installDate} /> : null}
              {asset?.assetType ? (
                <Metric label="Type" value={ASSET_TYPE_LABELS[asset.assetType as AssetType] ?? asset.assetType} />
              ) : null}
            </MetricGrid>

            {attributes.length > 0 ? (
              <Section title="Recorded attributes">
                <Stack spacing={0.25}>
                  {attributes.map(([key, value]) => (
                    <AttributeRow key={key} label={humanise(key)} value={formatValue(value)} />
                  ))}
                </Stack>
              </Section>
            ) : null}

            <Section title="Location">
              <CoordinateRow lngLat={feature.lngLat} />
              {asset?.geometryType ? (
                <Typography sx={{ mt: 0.75, fontSize: 11.5, color: mapChrome.textFaint }}>
                  {asset.geometryType} geometry · clicked point
                </Typography>
              ) : null}
            </Section>

            {!canReadAssets ? (
              <Note>
                Your role can view the map but not asset records, so only what the map itself
                carries is shown here.
              </Note>
            ) : assetQuery.isError ? (
              <Note>Full record could not be loaded. The map's own details are shown above.</Note>
            ) : null}
          </Stack>
        )}
      </Box>

      {/* ---- Footer ------------------------------------------------------------------ */}
      <Stack
        direction="row"
        spacing={1}
        sx={{ px: 2, py: 1.5, borderTop: `1px solid ${mapChrome.border}` }}
      >
        <CardButton onClick={() => onZoom(feature.lngLat)} icon={<TargetIcon sx={{ fontSize: 16 }} />}>
          Zoom here
        </CardButton>
        {canReadAssets ? (
          <CardButton
            to={`/assets/${feature.assetId}`}
            icon={<OpenIcon sx={{ fontSize: 15 }} />}
            primary
          >
            Open record
          </CardButton>
        ) : null}
      </Stack>
    </Box>
  );
}

// --- Pieces ------------------------------------------------------------------------------

const STATUS_COLOURS: Record<AssetStatus, { colour: string; label: string }> = {
  IN_SERVICE: { colour: severity.ok, label: 'In service' },
  PLANNED: { colour: severity.info, label: 'Planned' },
  OUT_OF_SERVICE: { colour: severity.minor, label: 'Out of service' },
  DAMAGED: { colour: severity.critical, label: 'Damaged' },
  DECOMMISSIONED: { colour: severity.unknown, label: 'Decommissioned' },
};

/**
 * Status as a tinted pill rather than a MUI Chip.
 *
 * The card floats on dark, translucent chrome over live cartography; a filled Chip in a theme
 * colour is sized and weighted for a light form surface and reads as a button here. The colours
 * are the reserved severity hues, so "damaged" on the map means what "damaged" means everywhere.
 */
function StatusPill({ status }: { status: AssetStatus }) {
  const spec = STATUS_COLOURS[status] ?? { colour: severity.unknown, label: titleCase(status) };
  return (
    <Stack
      direction="row"
      alignItems="center"
      spacing={0.625}
      sx={{
        px: 0.875,
        py: 0.25,
        borderRadius: 10,
        border: `1px solid ${spec.colour}55`,
        bgcolor: `${spec.colour}1F`,
      }}
    >
      <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: spec.colour, boxShadow: `0 0 6px ${spec.colour}` }} />
      <Typography sx={{ fontSize: 11, fontWeight: 600, color: mapChrome.text }}>
        {spec.label}
      </Typography>
    </Stack>
  );
}

function MetricGrid({ children }: { children: ReactNode }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>{children}</Box>
  );
}

/**
 * One fact, in a tile.
 *
 * `lead` marks the numbers an operator came for — length and diameter — which get the larger type
 * and the accent border. Everything else is supporting detail and is styled to stay quiet.
 */
function Metric({ label, value, lead }: { label: string; value: string; lead?: boolean }) {
  return (
    <Box
      sx={{
        px: 1.25,
        py: 1,
        borderRadius: 2,
        bgcolor: mapChrome.card,
        border: `1px solid ${lead ? mapChrome.accentSoft : mapChrome.border}`,
      }}
    >
      <Typography
        sx={{
          fontSize: 10,
          fontWeight: 700,
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
          color: mapChrome.textFaint,
        }}
      >
        {label}
      </Typography>
      <Typography
        sx={{
          mt: 0.25,
          fontSize: lead ? 16 : 13,
          fontWeight: lead ? 700 : 600,
          lineHeight: 1.3,
          color: lead ? mapChrome.accent : mapChrome.text,
          overflowWrap: 'anywhere',
        }}
      >
        {value}
      </Typography>
    </Box>
  );
}

function MetricsSkeleton() {
  return (
    <MetricGrid>
      {[0, 1, 2, 3].map((i) => (
        <Skeleton
          key={i}
          variant="rounded"
          height={54}
          sx={{ bgcolor: 'rgba(255,255,255,0.06)', borderRadius: 2 }}
        />
      ))}
    </MetricGrid>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box>
      <Typography
        sx={{
          mb: 0.75,
          fontSize: 10.5,
          fontWeight: 700,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
          color: mapChrome.textFaint,
        }}
      >
        {title}
      </Typography>
      {children}
    </Box>
  );
}

function AttributeRow({ label, value }: { label: string; value: string }) {
  return (
    <Stack
      direction="row"
      alignItems="baseline"
      spacing={1}
      sx={{
        px: 1,
        py: 0.625,
        borderRadius: 1.5,
        '&:nth-of-type(odd)': { bgcolor: 'rgba(255,255,255,0.03)' },
      }}
    >
      <Typography sx={{ fontSize: 12, color: mapChrome.textFaint, flexShrink: 0 }}>
        {label}
      </Typography>
      <Box sx={{ flexGrow: 1, borderBottom: `1px dotted ${mapChrome.border}`, transform: 'translateY(-2px)' }} />
      <Typography
        sx={{ fontSize: 12.5, fontWeight: 600, color: mapChrome.text, textAlign: 'right', overflowWrap: 'anywhere' }}
      >
        {value}
      </Typography>
    </Stack>
  );
}

/**
 * The clicked coordinate, with copy-to-clipboard.
 *
 * Six decimals is about 10 cm — enough to hand to a field crew's handset and no more, since the
 * digits beyond that are noise from the click, not precision in the data.
 */
function CoordinateRow({ lngLat }: { lngLat: [number, number] }) {
  const [copied, setCopied] = useState(false);
  const text = `${lngLat[1].toFixed(6)}, ${lngLat[0].toFixed(6)}`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      // A denied clipboard permission is not worth an error state; the value is on screen and
      // selectable either way.
    }
  };

  return (
    <Stack
      direction="row"
      alignItems="center"
      spacing={1}
      sx={{
        px: 1.25,
        py: 0.875,
        borderRadius: 2,
        bgcolor: mapChrome.card,
        border: `1px solid ${mapChrome.border}`,
      }}
    >
      <Typography
        sx={{
          flexGrow: 1,
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          fontSize: 12,
          color: mapChrome.text,
        }}
      >
        {text}
      </Typography>
      <IconAction label={copied ? 'Copied' : 'Copy latitude, longitude'} onClick={copy}>
        {copied ? (
          <CheckIcon sx={{ fontSize: 16, color: severity.ok }} />
        ) : (
          <CopyIcon sx={{ fontSize: 15 }} />
        )}
      </IconAction>
    </Stack>
  );
}

function Note({ children }: { children: ReactNode }) {
  return (
    <Typography
      sx={{
        px: 1.25,
        py: 1,
        borderRadius: 2,
        fontSize: 12,
        lineHeight: 1.5,
        color: mapChrome.textMuted,
        bgcolor: 'rgba(255,255,255,0.04)',
        border: `1px dashed ${mapChrome.border}`,
      }}
    >
      {children}
    </Typography>
  );
}

function IconAction({
  label,
  onClick,
  children,
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <Tooltip title={label}>
      <Box
        component="button"
        type="button"
        onClick={onClick}
        aria-label={label}
        sx={{
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
          width: 28,
          height: 28,
          borderRadius: 1.5,
          border: `1px solid ${mapChrome.border}`,
          background: 'none',
          color: mapChrome.textFaint,
          cursor: 'pointer',
          '&:hover': { color: mapChrome.text, bgcolor: 'rgba(255,255,255,0.08)' },
        }}
      >
        {children}
      </Box>
    </Tooltip>
  );
}

/** Footer action. Renders as a router link when `to` is given, so "open record" is a real link. */
function CardButton({
  children,
  icon,
  onClick,
  to,
  primary,
}: {
  children: ReactNode;
  icon: ReactNode;
  onClick?: () => void;
  to?: string;
  primary?: boolean;
}) {
  return (
    <Box
      component={to ? RouterLink : 'button'}
      {...(to ? { to } : { type: 'button' as const, onClick })}
      sx={{
        flex: 1,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 0.75,
        px: 1.25,
        py: 0.875,
        borderRadius: 2,
        fontSize: 12.5,
        fontWeight: 600,
        textDecoration: 'none',
        cursor: 'pointer',
        color: primary ? '#04121B' : mapChrome.textMuted,
        background: primary
          ? `linear-gradient(180deg, ${mapChrome.accent}, ${mapChrome.accentStrong})`
          : 'none',
        border: `1px solid ${primary ? 'transparent' : mapChrome.border}`,
        boxShadow: primary ? mapChrome.glow : 'none',
        transition: 'background-color 160ms ease, color 160ms ease, box-shadow 160ms ease',
        '&:hover': primary
          ? { boxShadow: mapChrome.glowStrong }
          : { color: mapChrome.text, bgcolor: 'rgba(255,255,255,0.06)' },
      }}
    >
      {icon}
      {children}
    </Box>
  );
}

// --- Formatting ---------------------------------------------------------------------------

/** Metres below a kilometre, kilometres above it — the units a network is actually discussed in. */
function formatLength(metres: number): string {
  return metres >= 1000 ? `${(metres / 1000).toFixed(2)} km` : `${metres.toFixed(1)} m`;
}

/** Trims the trailing zeros a `BigDecimal` arrives with, without rounding a real value away. */
function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)));
}

/**
 * Attribute keys come from whatever the utility's shapefile called them (`digital_length`,
 * `start_date`), so they are humanised rather than mapped: a lookup table would cover one
 * utility's field names and leave the next one's raw.
 */
function humanise(key: string): string {
  const spaced = key.replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1).toLowerCase();
}

function titleCase(value: string): string {
  return humanise(value);
}

function formatValue(value: unknown): string {
  if (typeof value === 'number') return formatNumber(value);
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'string') return value;
  return JSON.stringify(value);
}
