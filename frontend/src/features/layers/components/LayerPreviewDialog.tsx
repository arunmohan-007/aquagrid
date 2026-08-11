import { useMemo } from 'react';
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import { useMapStyle, useLayerStatistics } from '../hooks/useLayers';
import { LayerPreviewMap } from './LayerPreviewMap';
import { FAMILY_LABELS, formatCount, formatExtent } from '../labels';
import type { GisLayer } from '../types';

/**
 * The layer preview: the layer drawn with its current style, beside the facts about it.
 *
 * It draws from the same composed specification the console's map uses — not a re-rendering of the
 * layer with preview-specific styling — so what is on screen here is what an operator will see.
 * Feature count and extent come from PostGIS aggregates rather than from the features themselves,
 * which is what makes opening this on a layer of two million service connections cost nothing.
 */
export function LayerPreviewDialog({
  layer,
  open,
  onClose,
}: {
  layer: GisLayer | null;
  open: boolean;
  onClose: () => void;
}) {
  const { data: mapStyle } = useMapStyle();
  const { data: stats } = useLayerStatistics(open && layer ? layer.id : undefined);

  const composed = useMemo(
    () => (layer ? (mapStyle ?? []).find((c) => c.code === layer.code) ?? null : null),
    [mapStyle, layer],
  );

  if (!layer) return null;

  const extent = stats?.extent ?? layer.extent;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        <Stack direction="row" alignItems="center" spacing={1.5}>
          <span>{layer.title}</span>
          <Chip size="small" variant="outlined" label={layer.code} />
        </Stack>
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <LayerPreviewMap composed={composed} extent={extent} height={320} />

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
              gap: 1.5,
            }}
          >
            <Fact label="Features" value={formatCount(stats?.featureCount ?? layer.featureCount)} />
            <Fact
              label="Geometry"
              value={`${layer.geometryType} (${FAMILY_LABELS[layer.geometryFamily]})`}
            />
            <Fact label="CRS" value={layer.crs} />
            <Fact label="Style" value={composed?.styleName ?? 'Built-in symbology'} />
            <Fact label="Zoom range" value={`${layer.minZoom} – ${layer.maxZoom}`} sx={{ gridColumn: '1 / -1' }} />
            <Fact label="Extent (EPSG:4326)" value={formatExtent(extent ?? null)} sx={{ gridColumn: '1 / -1' }} />
          </Box>

          {composed && composed.legend.length > 1 ? (
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
                LEGEND
              </Typography>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                {composed.legend.map((entry) => (
                  <Chip
                    key={entry.label}
                    size="small"
                    variant="outlined"
                    label={entry.label}
                    icon={
                      <Box
                        sx={{
                          width: 10,
                          height: 10,
                          borderRadius: '50%',
                          bgcolor: entry.colour,
                          ml: 1,
                        }}
                      />
                    }
                  />
                ))}
              </Stack>
            </Box>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

function Fact({
  label,
  value,
  sx,
}: {
  label: string;
  value: string;
  sx?: Record<string, unknown>;
}) {
  return (
    <Box sx={sx}>
      <Typography variant="caption" sx={{ opacity: 0.7, display: 'block' }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {value}
      </Typography>
    </Box>
  );
}
