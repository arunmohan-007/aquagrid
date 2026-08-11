import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CircularProgress,
  Divider,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/DownloadOutlined';
import dayjs from 'dayjs';
import { useDeviceList } from '@/features/devices/hooks/useDevices';
import { useCommunicationTypes } from '@/features/devices/hooks/useDevices';
import { DEVICE_TYPES, DEVICE_TYPE_LABELS } from '@/features/devices/labels';
import { useDownloadReadings } from '../hooks/useReadingExport';
import type { ExportFormat } from '../types';

const WINDOWS = [
  { hours: 24, label: 'Last 24 hours' },
  { hours: 24 * 7, label: 'Last 7 days' },
  { hours: 24 * 30, label: 'Last 30 days' },
  { hours: 24 * 90, label: 'Last 90 days' },
];

/**
 * Reports: timestamped readings, downloaded as Excel or PDF.
 *
 * Three filters and they are independent axes, not a drill-down: which single device (if any),
 * which *kind* of instrument (meter, pH sensor, level sensor — what {@code DeviceType} already
 * distinguishes), and which network it reports on (LoRaWAN, NB-IoT, 4G Cellular — what
 * {@code CommunicationType} already distinguishes). "Every pH sensor" and "everything on LoRaWAN"
 * are different questions a report may ask at once, so this reuses the device registry's own
 * vocabulary for both rather than inventing a report-specific one that could drift from it.
 *
 * Excel and PDF exist for different jobs, not as a preference toggle: the spreadsheet is a working
 * artefact meant to be filtered and pivoted, the PDF is a fixed document meant to be attached or
 * signed. Both come from the same query on the server, so they cannot disagree about what they
 * contain.
 */
export default function ReportsPage() {
  const [format, setFormat] = useState<ExportFormat>('XLSX');
  const [deviceId, setDeviceId] = useState('');
  const [deviceType, setDeviceType] = useState('');
  const [transport, setTransport] = useState('');
  const [hours, setHours] = useState(24 * 7);

  const devices = useDeviceList('', undefined, undefined, undefined, undefined, 0);
  const communicationTypes = useCommunicationTypes();
  const download = useDownloadReadings();

  const deviceList = devices.data?.content ?? [];

  const handleDownload = () => {
    const to = dayjs();
    const from = to.subtract(hours, 'hour');
    download.mutate({
      format,
      deviceId: deviceId || undefined,
      deviceType: deviceType || undefined,
      transport: transport || undefined,
      from: from.toISOString(),
      to: to.toISOString(),
    });
  };

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="h1">Reports</Typography>
        <Typography variant="body2" color="text.secondary">
          Timestamped device readings, downloaded as a spreadsheet or a PDF — one device, one kind
          of instrument, or one network at a time.
        </Typography>
      </Box>

      <Card variant="outlined" sx={{ maxWidth: 640 }}>
        <Box sx={{ px: 2.5, py: 2 }}>
          <Typography variant="subtitle1" sx={{ mb: 2 }}>
            Format
          </Typography>
          <ToggleButtonGroup
            exclusive
            value={format}
            onChange={(_, value: ExportFormat | null) => value && setFormat(value)}
            size="small"
          >
            <ToggleButton value="XLSX">Excel (.xlsx)</ToggleButton>
            <ToggleButton value="PDF">PDF</ToggleButton>
          </ToggleButtonGroup>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
            {format === 'XLSX'
              ? 'A working sheet — values are numbers, ready to filter, pivot or chart.'
              : 'A fixed, paginated document for attaching or signing off. Rows beyond a few thousand are exported to Excel instead — a PDF that long is not one anybody reads.'}
          </Typography>
        </Box>

        <Divider />

        <Box sx={{ px: 2.5, py: 2 }}>
          <Typography variant="subtitle1" sx={{ mb: 1.5 }}>
            What to include
          </Typography>
          <Stack spacing={2}>
            <TextField
              select
              size="small"
              label="Device"
              value={deviceId}
              onChange={(e) => setDeviceId(e.target.value)}
              helperText="Leave as “All devices” to combine with the filters below."
            >
              <MenuItem value="">All devices</MenuItem>
              {deviceList.map((device) => (
                <MenuItem key={device.id} value={device.id}>
                  {device.deviceCode} — {device.name}
                </MenuItem>
              ))}
            </TextField>

            <Stack direction="row" spacing={2}>
              <TextField
                select
                fullWidth
                size="small"
                label="Device type"
                value={deviceType}
                onChange={(e) => setDeviceType(e.target.value)}
                disabled={Boolean(deviceId)}
              >
                <MenuItem value="">Any type</MenuItem>
                {DEVICE_TYPES.map((type) => (
                  <MenuItem key={type} value={type}>
                    {DEVICE_TYPE_LABELS[type]}
                  </MenuItem>
                ))}
              </TextField>

              <TextField
                select
                fullWidth
                size="small"
                label="Network"
                value={transport}
                onChange={(e) => setTransport(e.target.value)}
                disabled={Boolean(deviceId)}
              >
                <MenuItem value="">Any network</MenuItem>
                {(communicationTypes.data ?? []).map((type) => (
                  <MenuItem key={type.id} value={type.id}>
                    {type.id}
                  </MenuItem>
                ))}
              </TextField>
            </Stack>
            {deviceId ? (
              <Typography variant="caption" color="text.secondary">
                Device type and network only narrow "all devices" — clear the device above to use
                them.
              </Typography>
            ) : null}

            <TextField
              select
              size="small"
              label="Period"
              value={hours}
              onChange={(e) => setHours(Number(e.target.value))}
              sx={{ maxWidth: 220 }}
            >
              {WINDOWS.map((window) => (
                <MenuItem key={window.hours} value={window.hours}>
                  {window.label}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </Box>

        <Divider />

        <Box sx={{ px: 2.5, py: 2 }}>
          {download.isError ? (
            <Alert severity="error" variant="outlined" sx={{ mb: 2 }}>
              {(download.error as Error).message}
            </Alert>
          ) : null}
          {download.isSuccess ? (
            <Alert severity="success" variant="outlined" sx={{ mb: 2 }}>
              Download started.
            </Alert>
          ) : null}
          <Button
            variant="contained"
            startIcon={download.isPending ? <CircularProgress size={16} color="inherit" /> : <DownloadIcon />}
            disabled={download.isPending}
            onClick={handleDownload}
          >
            {download.isPending ? 'Preparing export…' : `Download ${format === 'XLSX' ? 'Excel' : 'PDF'}`}
          </Button>
        </Box>
      </Card>
    </Stack>
  );
}
