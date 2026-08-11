import { Box, Stack, Typography } from '@mui/material';

/**
 * The left-hand panel of the authentication screens.
 *
 * The motif is an abstracted distribution network — nodes, mains and flow — drawn as inline
 * SVG rather than a raster image. Three reasons: it is a few hundred bytes instead of a few
 * hundred kilobytes on a field technician's mobile link, it stays sharp on every display
 * density, and it reads its colours from the MUI CSS variables so it themes itself in dark
 * mode instead of needing a second asset.
 *
 * The whole panel is `aria-hidden`: it is decoration, and a screen reader that announces it
 * puts noise between the user and the sign-in form.
 */
export function BrandPanel() {
  return (
    <Box
      aria-hidden
      className="relative hidden overflow-hidden md:flex md:flex-col md:justify-between"
      sx={{
        p: { md: 5, lg: 7 },
        color: 'common.white',
        background: (t) =>
          `linear-gradient(150deg, ${t.palette.primary.dark} 0%, ${t.palette.primary.main} 45%, ${t.palette.secondary.dark} 100%)`,
      }}
    >
      <NetworkMotif />

      <Stack spacing={1.5} className="relative z-10">
        <Stack direction="row" spacing={1.5} alignItems="center">
          <DropletMark />
          <Typography variant="h3" component="span" sx={{ fontWeight: 700, letterSpacing: '-0.02em' }}>
            AquaGrid
          </Typography>
        </Stack>
        <Typography variant="body2" sx={{ opacity: 0.85, maxWidth: 340 }}>
          Enterprise Smart Water Management Platform
        </Typography>
      </Stack>

      <Stack spacing={3} className="relative z-10 animate-fade-up">
        <Typography variant="h2" sx={{ maxWidth: 420, lineHeight: 1.25 }}>
          One operating picture for the whole distribution network.
        </Typography>
        <Stack direction="row" spacing={4} flexWrap="wrap" useFlexGap>
          <Metric value="24/7" label="Live telemetry" />
          <Metric value="GIS" label="Spatial asset register" />
          <Metric value="NRW" label="Loss analytics" />
        </Stack>
      </Stack>

      <Typography variant="caption" className="relative z-10" sx={{ opacity: 0.7 }}>
        Municipalities · Water authorities · Smart cities · Industry
      </Typography>
    </Box>
  );
}

function Metric({ value, label }: { value: string; label: string }) {
  return (
    <Stack spacing={0.25}>
      <Typography variant="h4" sx={{ fontWeight: 700 }}>
        {value}
      </Typography>
      <Typography variant="caption" sx={{ opacity: 0.75 }}>
        {label}
      </Typography>
    </Stack>
  );
}

function DropletMark() {
  return (
    <svg width="34" height="34" viewBox="0 0 24 24" fill="none" role="presentation">
      <path
        d="M12 2.6c3.6 4.3 6.2 7.7 6.2 10.8a6.2 6.2 0 1 1-12.4 0C5.8 10.3 8.4 6.9 12 2.6Z"
        fill="currentColor"
        opacity="0.92"
      />
      <path
        d="M9.3 13.4a2.7 2.7 0 0 0 2.7 2.7"
        stroke="rgba(255,255,255,0.85)"
        strokeWidth="1.4"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** Animated network schematic. The dashed strokes read as flow along the mains. */
function NetworkMotif() {
  return (
    <svg
      className="pointer-events-none absolute inset-0 h-full w-full opacity-[0.32]"
      viewBox="0 0 600 800"
      preserveAspectRatio="xMidYMid slice"
      role="presentation"
    >
      <defs>
        <radialGradient id="ag-glow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.6" />
          <stop offset="100%" stopColor="#FFFFFF" stopOpacity="0" />
        </radialGradient>
        {/* A second, cooler glow in the aqua accent that sits over the brand gradient. */}
        <radialGradient id="ag-glow-cyan" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#67E8F9" stopOpacity="0.45" />
          <stop offset="100%" stopColor="#67E8F9" stopOpacity="0" />
        </radialGradient>
      </defs>

      <circle cx="470" cy="180" r="240" fill="url(#ag-glow)" />
      <circle cx="120" cy="620" r="200" fill="url(#ag-glow-cyan)" />

      <g stroke="#FFFFFF" strokeWidth="1.6" fill="none" opacity="0.6">
        <path d="M40 640 L180 640 L180 430 L360 430 L360 250 L560 250" />
        <path d="M180 640 L180 760" />
        <path d="M360 430 L520 430 L520 560" />
        <path d="M40 320 L240 320 L240 430" />
        <path d="M240 320 L240 120 L440 120" />
      </g>

      {/* Flow indication: the cyan accent crawls along the trunk mains — water in motion. */}
      <g
        stroke="#67E8F9"
        strokeWidth="2.6"
        fill="none"
        strokeLinecap="round"
        strokeDasharray="14 26"
        className="animate-flow-dash"
      >
        <path d="M40 640 L180 640 L180 430 L360 430 L360 250 L560 250" />
        <path d="M240 320 L240 120 L440 120" />
      </g>

      {/* Nodes: reservoirs, pump stations, DMA meters. */}
      <g fill="#FFFFFF">
        {[
          [180, 640],
          [360, 430],
          [240, 320],
          [520, 560],
          [440, 120],
        ].map(([cx, cy]) => (
          <g key={`${cx}-${cy}`}>
            <circle cx={cx} cy={cy} r="5.5" />
            <circle
              cx={cx}
              cy={cy}
              r="5.5"
              fill="none"
              stroke="#67E8F9"
              strokeWidth="1.2"
              className="animate-ripple"
              style={{ transformOrigin: `${cx}px ${cy}px` }}
            />
          </g>
        ))}
      </g>
    </svg>
  );
}
