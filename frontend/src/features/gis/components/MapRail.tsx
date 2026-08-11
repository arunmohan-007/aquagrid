import type { ReactNode } from 'react';
import { Box, Stack, Tooltip, Typography } from '@mui/material';
import LayersIcon from '@mui/icons-material/LayersOutlined';
import BaseMapIcon from '@mui/icons-material/MapOutlined';
import MeasureIcon from '@mui/icons-material/StraightenOutlined';
import LegendIcon from '@mui/icons-material/FormatListBulletedOutlined';
import { mapChrome, toolTheme, toolGradientCss, RAIL_WIDTH } from '../mapTheme';

/**
 * The tools the rail exposes.
 *
 * Every entry here opens a panel that does something real. Rails on comparable consoles often
 * carry placeholder tools; an icon that opens an empty drawer teaches the operator to distrust
 * the rail, so tools arrive here as their capability lands, not before.
 *
 * Length and area measurement share one entry ("Measure"): an operator measuring a parcel needs
 * both, and the distinction is a mode inside the panel, not a separate tool.
 */
export type RailTool = 'layers' | 'basemap' | 'measure' | 'legend';

interface RailItem {
  id: RailTool;
  label: string;
  icon: ReactNode;
}

const ITEMS: RailItem[] = [
  { id: 'layers', label: 'Layers', icon: <LayersIcon /> },
  { id: 'basemap', label: 'Base map', icon: <BaseMapIcon /> },
  { id: 'measure', label: 'Measure', icon: <MeasureIcon /> },
  { id: 'legend', label: 'Legend', icon: <LegendIcon /> },
];

export function MapRail({
  active,
  onSelect,
}: {
  active: RailTool | null;
  onSelect: (tool: RailTool) => void;
}) {
  return (
    <Stack
      component="nav"
      aria-label="Map tools"
      sx={{
        position: 'relative',
        width: RAIL_WIDTH,
        flexShrink: 0,
        height: '100%',
        bgcolor: mapChrome.surface,
        borderRight: `1px solid ${mapChrome.border}`,
        py: 1.5,
        zIndex: 3,
        // Accent gradient hairline on the leading edge, so the rail joins the panel/map with a
        // lit seam rather than a flat dark butt-joint.
        '&::after': {
          content: '""',
          position: 'absolute',
          right: -1,
          top: 0,
          bottom: 0,
          // '1px': a bare 1 is a fraction in `sx`, i.e. the rail's full width.
          width: '1px',
          background: 'linear-gradient(180deg, rgba(103,232,249,0.35), rgba(59,130,246,0.06) 40%, transparent)',
          pointerEvents: 'none',
        },
      }}
    >
      {ITEMS.map((item) => (
        <RailButton
          key={item.id}
          item={item}
          active={active === item.id}
          onSelect={() => onSelect(item.id)}
        />
      ))}
    </Stack>
  );
}

function RailButton({
  item,
  active,
  onSelect,
}: {
  item: RailItem;
  active: boolean;
  onSelect: () => void;
}) {
  const theme = toolTheme(item.id);
  return (
    <Tooltip title={item.label} placement="right">
      <Box
        component="button"
        type="button"
        onClick={onSelect}
        aria-pressed={active}
        sx={{
          position: 'relative',
          width: '100%',
          border: 0,
          background: 'none',
          cursor: 'pointer',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 0.5,
          py: 1.5,
          color: active ? theme.from : mapChrome.textFaint,
          transition: 'color 180ms ease',
          // Active marker: a gradient bar on the leading edge, so selection survives colour-blindness.
          '&::before': {
            content: '""',
            position: 'absolute',
            left: 0,
            top: 10,
            bottom: 10,
            width: 3,
            borderRadius: '0 3px 3px 0',
            background: active ? toolGradientCss(item.id) : 'transparent',
            boxShadow: active ? theme.glow : 'none',
          },
          '&:hover': {
            color: active ? theme.from : mapChrome.text,
            bgcolor: 'rgba(255,255,255,0.04)',
            '& .ag-rail-icon': active
              ? {}
              : { color: mapChrome.text, borderColor: mapChrome.borderStrong },
          },
          '& .MuiSvgIcon-root': { fontSize: 22 },
        }}
      >
        {/* The icon sits in a rounded tile that lights up with the tool's gradient when active. */}
        <Box
          className="ag-rail-icon"
          aria-hidden
          sx={{
            width: 38,
            height: 38,
            borderRadius: 2,
            display: 'grid',
            placeItems: 'center',
            transition:
              'background 180ms ease, box-shadow 180ms ease, color 180ms ease, border-color 180ms ease',
            background: active ? toolGradientCss(item.id) : 'rgba(255,255,255,0.03)',
            border: `1px solid ${active ? 'rgba(255,255,255,0.18)' : mapChrome.border}`,
            boxShadow: active ? theme.glow : 'none',
            color: active ? '#0B1220' : mapChrome.textFaint,
          }}
        >
          {item.icon}
        </Box>
        <Typography
          variant="caption"
          sx={{
            fontSize: 10.5,
            fontWeight: 600,
            lineHeight: 1.2,
            color: active ? theme.from : 'inherit',
          }}
        >
          {item.label}
        </Typography>
      </Box>
    </Tooltip>
  );
}
