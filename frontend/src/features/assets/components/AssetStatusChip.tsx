import { Chip } from '@mui/material';
import type { AssetStatus } from '../types';

const STATUS_PROPS: Record<AssetStatus, { color: 'success' | 'warning' | 'default' | 'error' | 'info'; label: string }> = {
  IN_SERVICE: { color: 'success', label: 'In service' },
  PLANNED: { color: 'info', label: 'Planned' },
  OUT_OF_SERVICE: { color: 'warning', label: 'Out of service' },
  DECOMMISSIONED: { color: 'default', label: 'Decommissioned' },
  DAMAGED: { color: 'error', label: 'Damaged' },
};

export function AssetStatusChip({ status }: { status: AssetStatus }) {
  const props = STATUS_PROPS[status] ?? STATUS_PROPS.OUT_OF_SERVICE;
  return (
    <Chip
      size="small"
      color={props.color}
      label={props.label}
      variant={status === 'DECOMMISSIONED' ? 'outlined' : 'filled'}
    />
  );
}
