import { useEffect, useState } from 'react';
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
  Tooltip,
  Typography,
} from '@mui/material';
import { PacketFeedTable } from '../components/PacketFeedTable';
import { TransportStatusStrip } from '../components/TransportStatusStrip';
import {
  useDevicePackets,
  usePacketSearch,
  useReportingDevices,
  useTransportStatus,
} from '../hooks/useReceiver';
import { formatDuration, formatTimestamp } from '../labels';
import type { ReportingDevice } from '../types';

const WINDOWS = [
  { value: 1, label: 'Last hour' },
  { value: 24, label: 'Last 24 hours' },
  { value: 24 * 7, label: 'Last 7 days' },
  { value: 24 * 30, label: 'Last 30 days' },
];

/**
 * The receiver console: which devices are sending, and what each one sent.
 *
 * Two panes, and the split is the argument of the screen. The left pane answers "who is reporting"
 * from the hourly rollups, cheaply enough to poll; the right answers "what did *this* one send" from
 * the packet log, which is expensive and therefore only ever queried for one device at a time. That
 * boundary is why the readings are on the right and not the left — attaching values to a fleet-wide
 * list would cost a query per device on the page.
 *
 * Devices are listed **quietest first**. The list exists to find what has stopped talking, and a
 * device that went silent nine hours ago is invisible if it sorts below four hundred healthy ones.
 */
export default function ReceiverPage() {
  const [hours, setHours] = useState(24);
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [devicePage, setDevicePage] = useState(0);
  const [feedPage, setFeedPage] = useState(0);

  const transports = useTransportStatus();
  const devices = useReportingDevices(hours);
  const feed = useDevicePackets(selectedId, feedPage);

  // With no device selected the right pane shows recent traffic across the fleet — including the
  // packets that resolved to no device at all, which are the ones a commissioning engineer needs
  // and which by definition never appear under any device.
  const recent = usePacketSearch(undefined, undefined, devicePage, !selectedId);

  const deviceList = devices.data ?? [];
  const selected = deviceList.find((device) => device.deviceId === selectedId);

  // A selection that no longer exists in the window (the operator narrowed it) would otherwise
  // leave the right pane showing a device the left pane no longer lists.
  useEffect(() => {
    if (selectedId && devices.data && !devices.data.some((d) => d.deviceId === selectedId)) {
      setSelectedId(undefined);
    }
  }, [devices.data, selectedId]);

  const selectDevice = (deviceId: string) => {
    setSelectedId(deviceId === selectedId ? undefined : deviceId);
    setFeedPage(0);
  };

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="h1">Receiver</Typography>
        <Typography variant="body2" color="text.secondary">
          The single entry point for inbound telemetry — every packet, whatever carried it, with the
          time the device sent it and the time we took delivery.
        </Typography>
      </Box>

      {transports.isError ? (
        <Alert severity="error" variant="outlined">
          Could not load transport status. {(transports.error as Error).message}
        </Alert>
      ) : (
        <TransportStatusStrip transports={transports.data ?? []} />
      )}

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2.5} alignItems="stretch">
        {/* ---- Who is reporting ------------------------------------------------------- */}
        <Card variant="outlined" sx={{ width: { xs: '100%', lg: 380 }, flexShrink: 0 }}>
          <Stack
            direction="row"
            alignItems="center"
            justifyContent="space-between"
            spacing={1}
            sx={{ px: 2, py: 1.5 }}
          >
            <Typography variant="subtitle1">Reporting devices</Typography>
            <TextField
              select
              size="small"
              value={hours}
              onChange={(e) => setHours(Number(e.target.value))}
              sx={{ minWidth: 150 }}
            >
              {WINDOWS.map((window) => (
                <MenuItem key={window.value} value={window.value}>
                  {window.label}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          {devices.isFetching ? <LinearProgress /> : <Divider />}

          {devices.isError ? (
            <Box sx={{ p: 2 }}>
              <Alert severity="error" variant="outlined">
                {(devices.error as Error).message}
              </Alert>
            </Box>
          ) : devices.isLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
              <CircularProgress size={24} />
            </Box>
          ) : deviceList.length === 0 ? (
            <Box sx={{ px: 2, py: 6, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                No device has reported in this window.
              </Typography>
            </Box>
          ) : (
            <Box sx={{ maxHeight: 620, overflowY: 'auto' }}>
              {deviceList.map((device) => (
                <DeviceRow
                  key={device.deviceId}
                  device={device}
                  selected={device.deviceId === selectedId}
                  onSelect={() => selectDevice(device.deviceId)}
                />
              ))}
            </Box>
          )}
        </Card>

        {/* ---- What that device sent --------------------------------------------------- */}
        <Card variant="outlined" sx={{ flex: 1, minWidth: 0 }}>
          <Stack
            direction="row"
            alignItems="baseline"
            justifyContent="space-between"
            spacing={1}
            sx={{ px: 2, py: 1.5 }}
          >
            <Box>
              <Typography variant="subtitle1">
                {selected ? `Packets from ${selected.deviceCode ?? selected.name}` : 'Recent packets'}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {selected
                  ? 'Newest first, with the values each packet carried.'
                  : 'Across every device — select one on the left to see its readings.'}
              </Typography>
            </Box>
            {selected?.lastPacketAt ? (
              <Tooltip title={formatTimestamp(selected.lastPacketAt)}>
                <Typography variant="body2" color="text.secondary" noWrap>
                  last heard {formatDuration(selected.silentForSeconds)} ago
                </Typography>
              </Tooltip>
            ) : null}
          </Stack>
          {feed.isFetching || recent.isFetching ? <LinearProgress /> : <Divider />}

          {selectedId ? (
            feed.isError ? (
              <Box sx={{ p: 2 }}>
                <Alert severity="error" variant="outlined">
                  {(feed.error as Error).message}
                </Alert>
              </Box>
            ) : (
              <PacketFeedTable
                packets={feed.data?.content ?? []}
                showDevice={false}
                page={feedPage}
                totalPages={feed.data?.totalPages ?? 0}
                onPageChange={setFeedPage}
                emptyMessage="This device has sent nothing in the retained packet history."
              />
            )
          ) : recent.isError ? (
            <Box sx={{ p: 2 }}>
              <Alert severity="error" variant="outlined">
                {(recent.error as Error).message}
              </Alert>
            </Box>
          ) : (
            <PacketFeedTable
              packets={recent.data?.content ?? []}
              showDevice
              page={devicePage}
              totalPages={recent.data?.totalPages ?? 0}
              onPageChange={setDevicePage}
              emptyMessage="No packets have been received yet."
            />
          )}
        </Card>
      </Stack>
    </Stack>
  );
}

/**
 * One device in the list.
 *
 * The silence duration is the headline number, not the packet count. A device with 400 accepted
 * packets that last spoke nine hours ago is a fault; a device with 4 that spoke a minute ago is
 * fine. Sorting and emphasis both follow that.
 */
function DeviceRow({
  device,
  selected,
  onSelect,
}: {
  device: ReportingDevice;
  selected: boolean;
  onSelect: () => void;
}) {
  const failing = device.successRate !== undefined && device.successRate < 1;

  return (
    <ListItemButton selected={selected} onClick={onSelect} sx={{ display: 'block', py: 1.25 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
        <Typography variant="body2" fontWeight={600} noWrap>
          {device.deviceCode ?? device.deviceId}
        </Typography>
        <Tooltip title={formatTimestamp(device.lastPacketAt)}>
          <Typography variant="caption" color="text.secondary" noWrap>
            {formatDuration(device.silentForSeconds)} ago
          </Typography>
        </Tooltip>
      </Stack>

      <Typography variant="caption" color="text.secondary" display="block" noWrap>
        {device.name ?? '—'}
      </Typography>

      <Stack direction="row" spacing={0.75} sx={{ mt: 0.75 }} flexWrap="wrap" useFlexGap>
        {device.transport ? <Chip size="small" variant="outlined" label={device.transport} /> : null}
        {/* Only the exception is badged. A "Live Device" chip on every row is noise that trains the
            eye to skip exactly the flag it exists to show — same reasoning as the device registry. */}
        {device.deviceSource === 'SIMULATOR' ? (
          <Chip size="small" color="info" variant="outlined" label="Simulator" />
        ) : null}
        <Chip
          size="small"
          variant="outlined"
          color={failing ? 'error' : 'default'}
          label={`${device.accepted.toLocaleString()} ok`}
        />
        {device.rejected > 0 ? (
          <Chip size="small" color="error" label={`${device.rejected.toLocaleString()} rejected`} />
        ) : null}
        {device.duplicates > 0 ? (
          <Tooltip title="Retransmissions — the reading was ingested once, but the network did not receive our acknowledgement.">
            <Chip
              size="small"
              color="info"
              variant="outlined"
              label={`${device.duplicates.toLocaleString()} dup`}
            />
          </Tooltip>
        ) : null}
      </Stack>
    </ListItemButton>
  );
}
