import {
  Box,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftOutlined';
import ChevronRightIcon from '@mui/icons-material/ChevronRightOutlined';
import { PacketStatusChip } from './PacketStatusChip';
import { ReadingsCell } from './ReadingsCell';
import { formatDuration, formatTimestamp } from '../labels';
import type { ReceiverPacket } from '../types';

/**
 * The packet feed: what arrived, when, and what it carried.
 *
 * **Both timestamps get a column, and they are never collapsed.** `Received` is our clock;
 * `Device clock` is the meter's. The gap between them is shown beside the device clock rather than
 * left for the reader to subtract, because that number answers three different questions at a
 * glance — seconds means a healthy link, hours means a device that buffered through an outage, and
 * a negative value means a device whose clock is wrong and whose readings will land in the wrong
 * place on every chart that uses them.
 *
 * Rejected packets are shown inline with accepted ones rather than filtered into a separate view. A
 * device that is transmitting but being refused looks exactly like a silent one in any list that
 * only shows successes, and those two faults have completely different causes.
 */
export function PacketFeedTable({
  packets,
  showDevice,
  page,
  totalPages,
  onPageChange,
  emptyMessage,
}: {
  packets: ReceiverPacket[];
  showDevice: boolean;
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  emptyMessage: string;
}) {
  if (packets.length === 0) {
    return (
      <Box sx={{ px: 2, py: 6, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          {emptyMessage}
        </Typography>
      </Box>
    );
  }

  return (
    <>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Received</TableCell>
              <TableCell>Device clock</TableCell>
              {showDevice ? <TableCell>Device</TableCell> : null}
              <TableCell>Transport</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Readings</TableCell>
              <TableCell align="right">Size</TableCell>
              <TableCell align="right">Took</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {packets.map((packet) => (
              <TableRow key={packet.packetId} hover>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <Typography variant="body2">{formatTimestamp(packet.receivedAt)}</Typography>
                </TableCell>

                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  {packet.observedAt ? (
                    <Stack spacing={0.25}>
                      <Typography variant="body2">{formatTimestamp(packet.observedAt)}</Typography>
                      <LatencyNote seconds={packet.latencySeconds} />
                    </Stack>
                  ) : (
                    <Tooltip title="The payload carried no timestamp, or was never decoded — the receiver used its own clock.">
                      <Typography variant="body2" color="text.secondary">
                        not reported
                      </Typography>
                    </Tooltip>
                  )}
                </TableCell>

                {showDevice ? (
                  <TableCell>
                    <Typography variant="body2">
                      {packet.deviceCode ?? (
                        <Typography component="span" variant="body2" color="text.secondary">
                          unresolved
                        </Typography>
                      )}
                    </Typography>
                  </TableCell>
                ) : null}

                <TableCell>
                  <Typography variant="body2">{packet.transport}</Typography>
                </TableCell>

                <TableCell>
                  <PacketStatusChip
                    status={packet.status}
                    errorCode={packet.errorCode}
                    errorDetail={packet.errorDetail}
                  />
                </TableCell>

                <TableCell sx={{ minWidth: 240 }}>
                  <ReadingsCell readings={packet.readings} />
                </TableCell>

                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Typography variant="body2" color="text.secondary">
                    {packet.payloadSize} B
                  </Typography>
                </TableCell>

                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Typography variant="body2" color="text.secondary">
                    {packet.processingTimeMs} ms
                  </Typography>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {totalPages > 1 ? (
        <Stack direction="row" alignItems="center" justifyContent="flex-end" spacing={1} sx={{ p: 1.5 }}>
          <Typography variant="body2" color="text.secondary">
            Page {page + 1} of {totalPages}
          </Typography>
          <IconButton size="small" disabled={page === 0} onClick={() => onPageChange(page - 1)}>
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
          <IconButton
            size="small"
            disabled={page >= totalPages - 1}
            onClick={() => onPageChange(page + 1)}
          >
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </Stack>
      ) : null}
    </>
  );
}

/**
 * The device-vs-server clock gap, shown only when it is worth noticing.
 *
 * Under a minute is normal on every transport and printing it on every row would bury the outlier.
 * A device running *ahead* is called out separately: it is not merely late, it is wrong, and its
 * readings sort to the head of every series that uses `observedAt`.
 */
function LatencyNote({ seconds }: { seconds: number | undefined }) {
  if (seconds === undefined || Math.abs(seconds) < 60) {
    return null;
  }
  if (seconds < 0) {
    return (
      <Typography variant="caption" color="error.main">
        clock ahead by {formatDuration(Math.abs(seconds))}
      </Typography>
    );
  }
  return (
    <Typography variant="caption" color="text.secondary">
      delivered {formatDuration(seconds)} later
    </Typography>
  );
}
