import { Box, Button, Card, Chip, Stack, TextField, Tooltip, Typography } from '@mui/material';
import PlayIcon from '@mui/icons-material/PlayArrowRounded';
import PauseIcon from '@mui/icons-material/PauseRounded';
import StepIcon from '@mui/icons-material/FastForwardRounded';
import ReloadIcon from '@mui/icons-material/RefreshRounded';
import { useState } from 'react';
import type { SimulatorStatus } from '../types';
import { formatInterval } from '../labels';
import { formatTimestamp } from '@/features/receiver/labels';

/**
 * Run state and the four controls that change it.
 *
 * `Step` is given equal weight to `Start`, not tucked away, because it is what a validation run
 * actually uses: waiting a real minute per simulated minute makes any assertion about a trend — a
 * leak crossing a night-flow threshold, a cell reaching its replacement voltage — a test measured in
 * hours. Stepping compresses the clock while every packet still travels the full production path.
 */
export function SimulatorControls({
  status,
  busy,
  onStart,
  onPause,
  onStep,
  onReload,
}: {
  status: SimulatorStatus;
  busy: boolean;
  onStart: () => void;
  onPause: () => void;
  onStep: (ticks: number) => void;
  onReload: () => void;
}) {
  const [ticks, setTicks] = useState(10);
  const running = status.state === 'RUNNING';

  return (
    <Card variant="outlined" sx={{ px: 2, py: 1.75 }}>
      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        alignItems={{ xs: 'stretch', md: 'center' }}
        justifyContent="space-between"
      >
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <Chip
            size="small"
            color={running ? 'success' : 'default'}
            variant={running ? 'filled' : 'outlined'}
            label={running ? 'Running' : 'Paused'}
          />
          <Typography variant="body2" color="text.secondary">
            {status.fleetSize.toLocaleString()} meter{status.fleetSize === 1 ? '' : 's'} ·{' '}
            {formatInterval(status.intervalSeconds)} default interval
            {status.organizationCode ? ` · ${status.organizationCode}` : ''}
          </Typography>
          {status.lastTickAt ? (
            <Tooltip title={formatTimestamp(status.lastTickAt)}>
              <Typography variant="caption" color="text.secondary" noWrap>
                last pass took {status.lastTickMillis} ms
              </Typography>
            </Tooltip>
          ) : null}
        </Stack>

        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          {running ? (
            <Button
              size="small"
              variant="outlined"
              startIcon={<PauseIcon />}
              disabled={busy}
              onClick={onPause}
            >
              Pause
            </Button>
          ) : (
            <Button
              size="small"
              variant="contained"
              startIcon={<PlayIcon />}
              disabled={busy}
              onClick={onStart}
            >
              Start
            </Button>
          )}

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <TextField
              size="small"
              type="number"
              value={ticks}
              onChange={(e) => setTicks(Math.max(1, Math.min(60, Number(e.target.value) || 1)))}
              inputProps={{ min: 1, max: 60, 'aria-label': 'Intervals to step' }}
              sx={{ width: 88 }}
            />
            <Tooltip title="Run this many reporting intervals immediately. Every packet still goes through the full receiver pipeline.">
              <span>
                <Button
                  size="small"
                  variant="outlined"
                  startIcon={<StepIcon />}
                  disabled={busy}
                  onClick={() => onStep(ticks)}
                >
                  Step
                </Button>
              </span>
            </Tooltip>
          </Box>

          <Tooltip title="Re-read the fleet from the device registry — picks up devices registered, retired, or switched between SIMULATOR and LIVE.">
            <span>
              <Button
                size="small"
                variant="text"
                startIcon={<ReloadIcon />}
                disabled={busy}
                onClick={onReload}
              >
                Reload fleet
              </Button>
            </span>
          </Tooltip>
        </Stack>
      </Stack>
    </Card>
  );
}
