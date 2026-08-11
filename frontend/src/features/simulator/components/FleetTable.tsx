import { useState } from 'react';
import {
  Box,
  Button,
  Chip,
  Divider,
  IconButton,
  ListItemText,
  Menu,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import BoltIcon from '@mui/icons-material/OfflineBoltOutlined';
import type { SimulatedDevice, SimulatedFault } from '../types';
import { FAULT_LABELS, INJECTABLE_FAULTS, faultLabel, formatInterval, formatLitres } from '../labels';
import { errorLabel, formatDuration, formatTimestamp } from '@/features/receiver/labels';

/**
 * The fleet, one row per registered device the simulator is driving.
 *
 * The column that matters most is the last one: what the *receiver* did with the device's most
 * recent uplink. A simulator that reports only what it sent is measuring itself; the number an
 * operator needs is what the platform accepted, and the gap between the two is where a registration
 * or configuration fault shows up. A rejected simulated packet is never stray traffic — nothing
 * spoofs the simulator — so it always means a device row is wrong, and it is the same refusal the
 * physical device would get at that address.
 */
export function FleetTable({
  devices,
  busy,
  onInject,
  onSuspend,
}: {
  devices: SimulatedDevice[];
  busy: boolean;
  onInject: (deviceId: string, fault: SimulatedFault) => void;
  onSuspend: (deviceId: string, suspended: boolean) => void;
}) {
  if (devices.length === 0) {
    return (
      <Box sx={{ px: 2, py: 6, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No devices are being simulated.
        </Typography>
        <Typography variant="caption" color="text.secondary">
          Register a device with source <code>SIMULATOR</code> and the identity field its transport
          requires, then reload the fleet.
        </Typography>
      </Box>
    );
  }

  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Device</TableCell>
          <TableCell>Network</TableCell>
          <TableCell>Profile</TableCell>
          <TableCell>Condition</TableCell>
          <TableCell align="right">Uplinks</TableCell>
          <TableCell>Last uplink</TableCell>
          <TableCell align="right">Actions</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {devices.map((device) => (
          <DeviceRow
            key={device.deviceId}
            device={device}
            busy={busy}
            onInject={onInject}
            onSuspend={onSuspend}
          />
        ))}
      </TableBody>
    </Table>
  );
}

function DeviceRow({
  device,
  busy,
  onInject,
  onSuspend,
}: {
  device: SimulatedDevice;
  busy: boolean;
  onInject: (deviceId: string, fault: SimulatedFault) => void;
  onSuspend: (deviceId: string, suspended: boolean) => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const rejected = device.lastStatus === 'REJECTED';

  return (
    <TableRow hover sx={{ opacity: device.suspended ? 0.55 : 1 }}>
      <TableCell>
        <Typography variant="body2" fontWeight={600} noWrap>
          {device.deviceCode ?? device.deviceId}
        </Typography>
        {device.suspended ? (
          <Tooltip title="Silenced for cutover. The device is still registered as SIMULATOR — set its source to LIVE to release it permanently.">
            <Chip size="small" variant="outlined" label="Silenced" sx={{ mt: 0.5 }} />
          </Tooltip>
        ) : null}
      </TableCell>

      <TableCell>
        <Typography variant="caption" fontFamily="monospace" noWrap>
          {device.networkAddress ?? '—'}
        </Typography>
      </TableCell>

      <TableCell>
        <Stack direction="row" spacing={0.5} alignItems="center">
          {device.transport ? (
            <Chip size="small" variant="outlined" label={device.transport} />
          ) : null}
          <Tooltip
            title={`${formatLitres(device.baselineDailyLitres)} baseline demand, reporting every ${formatInterval(device.reportingIntervalSeconds)}`}
          >
            <Typography variant="caption" color="text.secondary" noWrap>
              {formatInterval(device.reportingIntervalSeconds)}
            </Typography>
          </Tooltip>
        </Stack>
      </TableCell>

      <TableCell>
        {device.activeFaults.length === 0 ? (
          <Typography variant="caption" color="text.secondary">
            Healthy
          </Typography>
        ) : (
          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
            {device.activeFaults.map((fault) => (
              <Tooltip key={fault} title={FAULT_LABELS[fault]?.description ?? ''}>
                {/* Saturated colour here is correct rather than decorative: these are the
                    conditions the alarm engine exists to raise, so they carry alarm severity. */}
                <Chip size="small" color="warning" variant="outlined" label={faultLabel(fault)} />
              </Tooltip>
            ))}
          </Stack>
        )}
      </TableCell>

      <TableCell align="right">
        <Typography variant="body2">{device.uplinksEmitted.toLocaleString()}</Typography>
        {device.uplinksSuppressed > 0 ? (
          <Tooltip title="Ticks this meter stayed silent for under a comms-loss fault. A simulated outage, not a failure.">
            <Typography variant="caption" color="text.secondary" noWrap>
              {device.uplinksSuppressed.toLocaleString()} silent
            </Typography>
          </Tooltip>
        ) : null}
      </TableCell>

      <TableCell>
        {device.lastEmittedAt ? (
          <Tooltip title={formatTimestamp(device.lastEmittedAt)}>
            <Typography variant="caption" noWrap>
              {formatDuration((Date.now() - new Date(device.lastEmittedAt).getTime()) / 1000)} ago
            </Typography>
          </Tooltip>
        ) : (
          <Typography variant="caption" color="text.secondary">
            never
          </Typography>
        )}
        {rejected ? (
          <Tooltip title={device.lastErrorDetail ?? ''}>
            <Chip
              size="small"
              color="error"
              label={errorLabel(device.lastErrorCode, 'Rejected')}
              sx={{ mt: 0.5, maxWidth: 220 }}
            />
          </Tooltip>
        ) : null}
      </TableCell>

      <TableCell align="right">
        <Stack direction="row" spacing={0.5} justifyContent="flex-end">
          <Tooltip title="Inject a fault">
            <span>
              <IconButton size="small" disabled={busy} onClick={(e) => setAnchor(e.currentTarget)}>
                <BoltIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
          <Tooltip
            title={
              device.suspended
                ? 'Resume simulating this meter'
                : 'Silence this meter now, for cutover to a physical device at the same address'
            }
          >
            <span>
              <Button
                size="small"
                variant="text"
                disabled={busy}
                onClick={() => onSuspend(device.deviceId, !device.suspended)}
              >
                {device.suspended ? 'Resume' : 'Silence'}
              </Button>
            </span>
          </Tooltip>
        </Stack>

        <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
          {INJECTABLE_FAULTS.map((fault) => (
            <MenuItem
              key={fault}
              onClick={() => {
                onInject(device.deviceId, fault);
                setAnchor(null);
              }}
              sx={{ maxWidth: 360, whiteSpace: 'normal' }}
            >
              <ListItemText
                primary={faultLabel(fault)}
                secondary={FAULT_LABELS[fault].description}
                secondaryTypographyProps={{ variant: 'caption' }}
              />
            </MenuItem>
          ))}
          <Divider />
          <MenuItem
            onClick={() => {
              onInject(device.deviceId, 'HEALTHY');
              setAnchor(null);
            }}
          >
            <ListItemText
              primary={faultLabel('HEALTHY')}
              secondary={FAULT_LABELS.HEALTHY.description}
              secondaryTypographyProps={{ variant: 'caption' }}
            />
          </MenuItem>
        </Menu>
      </TableCell>
    </TableRow>
  );
}
