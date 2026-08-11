import { Chip } from '@mui/material';
import type { DeviceStatus } from '../types';
import { DEVICE_STATUS_LABELS } from '../labels';

const STATUS_PROPS: Record<DeviceStatus, { color: 'success' | 'warning' | 'default' | 'error' | 'info' }> = {
  ACTIVE: { color: 'success' },
  // Registered but not yet reporting — the state every device passes through on its way to ACTIVE.
  PROVISIONED: { color: 'info' },
  INACTIVE: { color: 'warning' },
  FAULTY: { color: 'error' },
  DECOMMISSIONED: { color: 'default' },
};

export function DeviceStatusChip({ status }: { status: DeviceStatus }) {
  const props = STATUS_PROPS[status] ?? STATUS_PROPS.INACTIVE;
  return (
    <Chip
      size="small"
      color={props.color}
      label={DEVICE_STATUS_LABELS[status] ?? status}
      variant={status === 'DECOMMISSIONED' ? 'outlined' : 'filled'}
    />
  );
}
