import { Chip, Tooltip } from '@mui/material';
import type { ReceptionStatus } from '../types';
import { ERROR_CODE_LABELS, RECEPTION_STATUS_LABELS } from '../labels';

/**
 * Colour follows the same convention as {@code DeviceStatusChip}.
 *
 * `DUPLICATE` is `info`, not `warning`, and that is the judgement worth stating: a retransmission
 * means the network never received our acknowledgement, which is worth seeing, but the reading
 * itself was ingested exactly once. Marking it amber would put a caution next to a packet that cost
 * nothing, and train the eye to ignore the colour that matters on the row below.
 */
const STATUS_PROPS: Record<ReceptionStatus, { color: 'success' | 'info' | 'error' }> = {
  ACCEPTED: { color: 'success' },
  DUPLICATE: { color: 'info' },
  REJECTED: { color: 'error' },
};

export function PacketStatusChip({
  status,
  errorCode,
  errorDetail,
}: {
  status: ReceptionStatus;
  errorCode?: string | undefined;
  errorDetail?: string | undefined;
}) {
  const props = STATUS_PROPS[status] ?? STATUS_PROPS.REJECTED;

  // The chip stays short so the column does not wrap; the reason lives in the tooltip, where the
  // server's own detail is preferred over our translation because it names the specific value that
  // failed — "Metric pressure value 40000.0 is outside the plausible range" beats "Failed validation".
  const reason = errorCode
    ? errorDetail ?? ERROR_CODE_LABELS[errorCode] ?? errorCode
    : undefined;

  const chip = (
    <Chip size="small" color={props.color} label={RECEPTION_STATUS_LABELS[status] ?? status} />
  );

  return reason ? <Tooltip title={reason}>{chip}</Tooltip> : chip;
}
