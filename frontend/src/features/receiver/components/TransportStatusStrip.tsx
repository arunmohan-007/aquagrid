import { Box, Card, Stack, Tooltip, Typography } from '@mui/material';
import CircleIcon from '@mui/icons-material/Circle';
import type { TransportStatus } from '../types';
import { formatDuration, formatTimestamp } from '../labels';

/**
 * The listeners, and whether each is actually hearing anything.
 *
 * Two independent facts per card, because they fail independently and the difference decides who
 * gets called. A listener that is *running* but has heard nothing for an hour looks identical, in
 * the database, to one that never started — only the transport can tell those apart, and the
 * distinction is whether an engineer restarts a service or phones the network operator.
 *
 * So "running" is the dot, and "last packet" is the number underneath it. A single green light
 * would have hidden the more common fault.
 */
export function TransportStatusStrip({ transports }: { transports: TransportStatus[] }) {
  if (transports.length === 0) {
    return (
      <Card variant="outlined" sx={{ p: 2 }}>
        <Typography variant="body2" color="text.secondary">
          No transports are enabled — the platform is accepting no telemetry. Enable one under{' '}
          <code>aquagrid.iot.transports</code>.
        </Typography>
      </Card>
    );
  }

  return (
    <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
      {transports.map((transport) => (
        <Card
          key={transport.transport}
          variant="outlined"
          sx={{ px: 2, py: 1.25, minWidth: 200, flex: '1 1 200px' }}
        >
          <Stack direction="row" spacing={1} alignItems="center">
            <Tooltip title={transport.running ? 'Listener running' : 'Listener not running'}>
              <CircleIcon
                sx={{ fontSize: 10 }}
                color={transport.running ? 'success' : 'error'}
              />
            </Tooltip>
            <Typography variant="subtitle2" noWrap>
              {transport.displayName}
            </Typography>
          </Stack>

          <Box sx={{ mt: 0.75 }}>
            <Typography variant="caption" color="text.secondary" display="block" noWrap>
              {transport.endpoint}
            </Typography>
            <Stack direction="row" spacing={2} sx={{ mt: 0.5 }}>
              <Stat label="Packets" value={transport.packetsReceived.toLocaleString()} />
              {transport.stateful ? (
                <Stat label="Connections" value={String(transport.activeConnections)} />
              ) : null}
              <Tooltip title={formatTimestamp(transport.lastPacketAt)}>
                <span>
                  <Stat
                    label="Last"
                    value={
                      transport.lastPacketAt
                        ? formatDuration(
                            (Date.now() - new Date(transport.lastPacketAt).getTime()) / 1000,
                          )
                        : 'never'
                    }
                  />
                </span>
              </Tooltip>
            </Stack>
          </Box>
        </Card>
      ))}
    </Stack>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" display="block">
        {label}
      </Typography>
      <Typography variant="body2" fontWeight={600}>
        {value}
      </Typography>
    </Box>
  );
}
