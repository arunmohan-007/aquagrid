import { Chip, Stack, Typography } from '@mui/material';
import { FLAG_METRICS, metricLabel, metricUnit } from '../labels';

/**
 * The values one packet carried.
 *
 * Measurements and flags are rendered differently on purpose. A flag is a 0/1 condition, and
 * showing "Tamper 1" makes an operator translate a number into a state on every row; showing a
 * "Tamper" chip only when it is set puts the exception where the eye already goes. A cleared flag
 * is deliberately not rendered at all — a row listing "Tamper 0, Leak 0, Reverse flow 0" for every
 * healthy packet is three columns of noise hiding the one that matters.
 *
 * Volume is not abbreviated. A cumulative meter reading is what a bill is computed from, and
 * "1.2k L" is not a number anyone can reconcile against an invoice.
 */
export function ReadingsCell({ readings }: { readings: Record<string, number> }) {
  const entries = Object.entries(readings ?? {});
  if (entries.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        —
      </Typography>
    );
  }

  const flags = entries.filter(([metric, value]) => FLAG_METRICS.has(metric) && value !== 0);
  const measurements = entries.filter(([metric]) => !FLAG_METRICS.has(metric));

  if (measurements.length === 0 && flags.length === 0) {
    // Every metric present was a cleared flag: the device reported, and reported nothing wrong.
    return (
      <Typography variant="body2" color="text.secondary">
        No alarms
      </Typography>
    );
  }

  return (
    <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap alignItems="center">
      {measurements.map(([metric, value]) => (
        <Stack key={metric} direction="row" spacing={0.5} alignItems="baseline">
          <Typography variant="caption" color="text.secondary">
            {metricLabel(metric)}
          </Typography>
          <Typography variant="body2" fontWeight={600}>
            {formatValue(value)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {metricUnit(metric)}
          </Typography>
        </Stack>
      ))}
      {flags.map(([metric]) => (
        <Chip key={metric} size="small" color="error" label={metricLabel(metric)} />
      ))}
    </Stack>
  );
}

/**
 * Trailing zeros are dropped but significant digits are not.
 *
 * `toFixed(3)` then trimming, rather than `toLocaleString`, because grouping separators in a
 * cumulative volume are the difference between reading 1234.5 and 1,234.5 at a glance — and the
 * separator varies by locale in a way that makes a screenshot ambiguous.
 */
function formatValue(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return Number.parseFloat(value.toFixed(3)).toString();
}
