import { Box, Card, Chip, Stack, Tooltip, Typography } from '@mui/material';
import type { MetricGroup, MetricReading } from '../types';
import { formatAge, formatUnit, formatValue, isRaised } from '../labels';
import { formatTimestamp } from '@/features/receiver/labels';

/**
 * The device's current readings, grouped by what they describe.
 *
 * The grouping comes from the server and so does the order — meter reading first, because that is
 * the question this screen exists to answer. A device reports a dozen numbers and they are not of
 * equal kind: consumption is what the bill is built from, pressure is how the network is judged,
 * battery and radio say whether the device will still be reporting next month, and the flags are
 * conditions rather than quantities. A flat name-value list makes the reader do that sorting every
 * time they look.
 *
 * Every tile shows its age. That is not decoration: a number with no age cannot be judged, because
 * 3.1 V from an hour ago and 3.1 V from last March are the same number and completely different
 * facts.
 */
export function ReadingGroups({
  groups,
  selectedMetric,
  onSelectMetric,
}: {
  groups: MetricGroup[];
  // Explicit `| undefined` — see DeviceInfoPanel: exactOptionalPropertyTypes distinguishes an
  // omitted prop from one passed as undefined, and "no metric selected yet" is the latter.
  selectedMetric: string | undefined;
  onSelectMetric: (metric: string) => void;
}) {
  if (groups.length === 0) {
    return (
      <Card variant="outlined" sx={{ px: 2, py: 5, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          This device has not reported any readings in the last 30 days.
        </Typography>
        <Typography variant="caption" color="text.secondary">
          It may be newly registered, silent, or having its packets refused — the Receiver console
          shows which.
        </Typography>
      </Card>
    );
  }

  return (
    <Stack spacing={2}>
      {groups.map((group) => (
        <Box key={group.category}>
          <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 0.75 }}>
            {group.label}
          </Typography>
          <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
            {group.readings.map((reading) => (
              <ReadingTile
                key={reading.metric}
                reading={reading}
                selected={reading.metric === selectedMetric}
                onSelect={() => onSelectMetric(reading.metric)}
              />
            ))}
          </Stack>
        </Box>
      ))}
    </Stack>
  );
}

function ReadingTile({
  reading,
  selected,
  onSelect,
}: {
  reading: MetricReading;
  selected: boolean;
  onSelect: () => void;
}) {
  const raised = isRaised(reading);
  const plottable = reading.kind !== 'FLAG';

  return (
    <Tooltip title={`Measured ${formatTimestamp(reading.observedAt)}`}>
      <Card
        variant="outlined"
        onClick={plottable ? onSelect : undefined}
        sx={{
          px: 2,
          py: 1.25,
          minWidth: 172,
          flex: '1 1 172px',
          cursor: plottable ? 'pointer' : 'default',
          borderColor: selected ? 'primary.main' : undefined,
        }}
      >
        <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
          <Typography variant="caption" color="text.secondary" noWrap>
            {reading.label}
          </Typography>
          {reading.kind === 'COUNTER' ? (
            <Tooltip title="A cumulative register. The consumption is the difference between two readings, not this number.">
              <Chip size="small" variant="outlined" label="total" sx={{ height: 18 }} />
            </Tooltip>
          ) : null}
        </Stack>

        <Stack direction="row" spacing={0.5} alignItems="baseline">
          {/* Saturated colour only for a raised condition — these are the alarm-severity states,
              and the one thing on this screen that should pull the eye. */}
          <Typography variant="h6" color={raised ? 'error.main' : 'text.primary'}>
            {formatValue(reading)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {formatUnit(reading)}
          </Typography>
        </Stack>

        <Typography variant="caption" color="text.secondary" noWrap>
          {formatAge(reading.ageSeconds)}
        </Typography>
      </Card>
    </Tooltip>
  );
}
