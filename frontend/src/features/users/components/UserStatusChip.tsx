import { Chip } from '@mui/material';
import type { UserStatus } from '../types';

const STATUS_PROPS: Record<UserStatus, { color: 'success' | 'warning' | 'default' | 'error'; label: string }> = {
  ACTIVE: { color: 'success', label: 'Active' },
  PENDING: { color: 'warning', label: 'Pending' },
  DISABLED: { color: 'default', label: 'Disabled' },
  LOCKED: { color: 'error', label: 'Locked' },
};

/**
 * A consistent colour-coded badge for a user's lifecycle state.
 *
 * Colours carry meaning that must be identical wherever a status appears (list, detail, audit),
 * so it is centralised here rather than re-chosen per page.
 */
export function UserStatusChip({ status }: { status: UserStatus }) {
  const props = STATUS_PROPS[status] ?? STATUS_PROPS.DISABLED;
  return <Chip size="small" color={props.color} label={props.label} variant={status === 'DISABLED' ? 'outlined' : 'filled'} />;
}
