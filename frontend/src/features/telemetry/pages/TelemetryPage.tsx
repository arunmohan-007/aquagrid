import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Card,
  Chip,
  CircularProgress,
  Divider,
  LinearProgress,
  ListItemButton,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useDeviceList } from '@/features/devices/hooks/useDevices';
import type { Device } from '@/features/devices/types';
import { DeviceInfoPanel } from '../components/DeviceInfoPanel';
import { ReadingGroups } from '../components/ReadingGroups';
import { MetricChart } from '../components/MetricChart';
import { useDeviceTelemetry, useMetricSeries } from '../hooks/useTelemetry';
import { SERIES_WINDOWS, isPlottable } from '../labels';

/**
 * Device telemetry: what each meter is reading.
 *
 * This is the question the platform is opened with, and until now nothing answered it. The device
 * register says what is registered; the receiver console says what arrived on the wire, organised
 * by packet. A packet is the right unit for diagnosing ingestion and the wrong one for reading a
 * meter — an operator wants the latest value of each metric, grouped by what it describes,
 * whichever packets happened to carry them.
 *
 * Three panes, left to right in the order the question is asked: which device, what it is, what it
 * is reading. The chart sits under the readings because it answers the follow-up — *is that value
 * normal for this meter* — which only makes sense once there is a value to ask it about.
 */
export default function TelemetryPage() {
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [metric, setMetric] = useState<string | undefined>(undefined);
  const [hours, setHours] = useState(24);

  const devices = useDeviceList(search, undefined, undefined, undefined, undefined, 0);
  const telemetry = useDeviceTelemetry(selectedId);
  const series = useMetricSeries(selectedId, metric, hours);

  const deviceList = useMemo(() => devices.data?.content ?? [], [devices.data]);

  // Land on the first device rather than an empty right-hand pane. A screen that opens blank asks
  // the operator to do setup before it shows them anything.
  useEffect(() => {
    const first = deviceList[0];
    if (!selectedId && first) {
      setSelectedId(first.id);
    }
  }, [deviceList, selectedId]);

  /*
   * Pick a sensible metric when the device changes, rather than leaving the chart blank or —
   * worse — keeping the previous device's metric, which may be one this device never reports.
   * Meter reading first, because that is what this screen is for; otherwise the first plottable
   * thing it has.
   */
  useEffect(() => {
    if (!telemetry.data) return;
    const plottable = telemetry.data.groups
      .flatMap((group) => group.readings)
      .filter((reading) => isPlottable(reading.kind));
    if (plottable.length === 0) {
      setMetric(undefined);
      return;
    }
    if (!metric || !plottable.some((reading) => reading.metric === metric)) {
      const preferred = plottable.find((reading) => reading.metric === 'volume') ?? plottable[0];
      if (preferred) {
        setMetric(preferred.metric);
      }
    }
  }, [telemetry.data, metric]);

  const selectDevice = (deviceId: string) => {
    setSelectedId(deviceId);
  };

  const plottableMetrics = (telemetry.data?.groups ?? [])
    .flatMap((group) => group.readings)
    .filter((reading) => isPlottable(reading.kind));

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="h1">Device Telemetry</Typography>
        <Typography variant="body2" color="text.secondary">
          What each meter is reading — the latest value of every metric, grouped by what it
          describes, with the device it came from and how it has moved over time.
        </Typography>
      </Box>

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2.5} alignItems="stretch">
        {/* ---- Which device ------------------------------------------------------------- */}
        <Card variant="outlined" sx={{ width: { xs: '100%', lg: 300 }, flexShrink: 0 }}>
          <Box sx={{ px: 2, py: 1.5 }}>
            <Typography variant="subtitle1" sx={{ mb: 1 }}>
              Devices
            </Typography>
            <TextField
              size="small"
              fullWidth
              placeholder="Search code, name, address…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </Box>
          {devices.isFetching ? <LinearProgress /> : <Divider />}

          {devices.isError ? (
            <Box sx={{ p: 2 }}>
              <Alert severity="error" variant="outlined">
                {(devices.error as Error).message}
              </Alert>
            </Box>
          ) : deviceList.length === 0 ? (
            <Box sx={{ px: 2, py: 6, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                No devices match.
              </Typography>
            </Box>
          ) : (
            <Box sx={{ maxHeight: 640, overflowY: 'auto' }}>
              {deviceList.map((device) => (
                <DeviceRow
                  key={device.id}
                  device={device}
                  selected={device.id === selectedId}
                  onSelect={() => selectDevice(device.id)}
                />
              ))}
            </Box>
          )}
        </Card>

        {/* ---- What it is -------------------------------------------------------------- */}
        <Box sx={{ width: { xs: '100%', lg: 300 }, flexShrink: 0 }}>
          {telemetry.isLoading ? (
            <Card variant="outlined" sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
              <CircularProgress size={24} />
            </Card>
          ) : telemetry.isError ? (
            <Alert severity="error" variant="outlined">
              {(telemetry.error as Error).message}
            </Alert>
          ) : telemetry.data ? (
            <DeviceInfoPanel device={telemetry.data} />
          ) : null}
        </Box>

        {/* ---- What it is reading ------------------------------------------------------- */}
        <Stack spacing={2.5} sx={{ flex: 1, minWidth: 0 }}>
          {telemetry.data ? <ReadingGroups
            groups={telemetry.data.groups}
            selectedMetric={metric}
            onSelectMetric={setMetric}
          /> : null}

          {metric && plottableMetrics.length > 0 ? (
            <Card variant="outlined">
              <Stack
                direction="row"
                alignItems="center"
                justifyContent="space-between"
                spacing={1}
                sx={{ px: 2, py: 1.5 }}
                flexWrap="wrap"
                useFlexGap
              >
                <Typography variant="subtitle1">History</Typography>
                <Stack direction="row" spacing={1}>
                  <TextField
                    select
                    size="small"
                    value={metric}
                    onChange={(e) => setMetric(e.target.value)}
                    sx={{ minWidth: 160 }}
                  >
                    {plottableMetrics.map((reading) => (
                      <MenuItem key={reading.metric} value={reading.metric}>
                        {reading.label}
                      </MenuItem>
                    ))}
                  </TextField>
                  <TextField
                    select
                    size="small"
                    value={hours}
                    onChange={(e) => setHours(Number(e.target.value))}
                    sx={{ minWidth: 150 }}
                  >
                    {SERIES_WINDOWS.map((window) => (
                      <MenuItem key={window.hours} value={window.hours}>
                        {window.label}
                      </MenuItem>
                    ))}
                  </TextField>
                </Stack>
              </Stack>
              {series.isFetching ? <LinearProgress /> : <Divider />}

              {series.isError ? (
                <Box sx={{ p: 2 }}>
                  <Alert severity="error" variant="outlined">
                    {(series.error as Error).message}
                  </Alert>
                </Box>
              ) : series.data ? (
                <MetricChart series={series.data} />
              ) : (
                <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
                  <CircularProgress size={24} />
                </Box>
              )}
            </Card>
          ) : null}
        </Stack>
      </Stack>
    </Stack>
  );
}

function DeviceRow({
  device,
  selected,
  onSelect,
}: {
  device: Device;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <ListItemButton selected={selected} onClick={onSelect} sx={{ display: 'block', py: 1.1 }}>
      <Typography variant="body2" fontWeight={600} noWrap>
        {device.deviceCode}
      </Typography>
      <Typography variant="caption" color="text.secondary" display="block" noWrap>
        {device.name}
      </Typography>
      <Stack direction="row" spacing={0.5} sx={{ mt: 0.5 }} flexWrap="wrap" useFlexGap>
        <Chip size="small" variant="outlined" label={device.communicationType} />
        {/* Only the exception is badged — same reasoning as the receiver console. */}
        {device.deviceSource === 'SIMULATOR' ? (
          <Chip size="small" color="info" variant="outlined" label="Simulated" />
        ) : null}
      </Stack>
    </ListItemButton>
  );
}
