import {
  Alert,
  Box,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  Typography,
} from '@mui/material';
import { formatTime } from '../labels';
import type { RawTelemetry } from '../types';

/**
 * The complete payload of one or more packets, exactly as the devices sent them.
 *
 * The screen that makes the module's central promise inspectable. "Every parameter is preserved" is
 * a claim; this is where an operator checks it, sees the field their vendor documented sitting in
 * the payload, and decides whether to configure it.
 *
 * Rendered as formatted JSON rather than a key/value table on purpose: a table flattens nesting, and
 * the whole reason the payload is kept unmodified is that its structure is part of what the device
 * said. A table would present a tidied version of the one thing that must not be tidied.
 */

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  payloads: RawTelemetry[];
  /** Highlighted in each payload — the parameter the operator opened this to look at. */
  highlightKey?: string | undefined;
  loading?: boolean;
}

export function RawPayloadDialog({
  open,
  onClose,
  title,
  payloads,
  highlightKey,
  loading,
}: Props) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        {title}
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          Stored exactly as received — no normalisation, no renamed keys, nothing dropped. This is
          what the device actually sent.
        </Typography>
      </DialogTitle>
      <DialogContent dividers>
        {loading ? <Typography variant="body2">Loading…</Typography> : null}

        {!loading && payloads.length === 0 ? (
          <Alert severity="info">
            No stored payloads carry this field yet. Payloads are written for every packet, so this
            usually means the device has not reported since the field appeared.
          </Alert>
        ) : null}

        <Stack spacing={2} divider={<Divider flexItem />}>
          {payloads.map((entry) => (
            <Box key={entry.id}>
              <Stack
                direction="row"
                spacing={1}
                alignItems="center"
                flexWrap="wrap"
                sx={{ mb: 1 }}
                useFlexGap
              >
                <Chip
                  size="small"
                  label={entry.processingStatus}
                  color={entry.processingStatus === 'REJECTED' ? 'warning' : 'default'}
                  variant="outlined"
                />
                <Typography variant="caption" color="text.secondary">
                  received {formatTime(entry.receivedAt)}
                </Typography>
                {entry.deviceTimestamp ? (
                  <Typography variant="caption" color="text.secondary">
                    · device clock {formatTime(entry.deviceTimestamp)}
                  </Typography>
                ) : null}
                <Typography variant="caption" color="text.secondary">
                  · {entry.communicationType ?? 'unresolved'} over {entry.connectionMode}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  · {entry.payloadSize} bytes
                </Typography>
              </Stack>

              {entry.processingError ? (
                <Alert severity="warning" sx={{ mb: 1 }}>
                  {entry.processingError}
                  {/* The payload is here despite the refusal, which is the point of storing it
                      outside the pipeline: a packet refused because its device is not registered is
                      the packet somebody most needs to read. */}
                </Alert>
              ) : null}

              {entry.payloadEncoding !== 'JSON' ? (
                <Alert severity="info" sx={{ mb: 1 }}>
                  This device sends a binary frame. The bytes are preserved{' '}
                  {entry.payloadEncoding === 'BASE64' ? 'base64-encoded' : 'as text'} and are
                  recoverable exactly; what a binary frame never allows is querying inside it.
                </Alert>
              ) : null}

              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.5,
                  borderRadius: 1,
                  border: 1,
                  borderColor: 'divider',
                  bgcolor: 'action.hover',
                  fontSize: 12,
                  lineHeight: 1.6,
                  overflowX: 'auto',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                }}
              >
                {highlight(JSON.stringify(entry.payload, null, 2), highlightKey)}
              </Box>
            </Box>
          ))}
        </Stack>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Marks the line carrying the key of interest.
 *
 * Text-level rather than a JSON walk: the payload may nest arbitrarily and the operator is looking
 * for one string. Returning an array of nodes keeps the surrounding formatting exactly as
 * `JSON.stringify` produced it.
 */
function highlight(json: string, key: string | undefined) {
  if (!key) return json;
  return json.split('\n').map((line, index) =>
    line.includes(`"${key}"`) ? (
      <Box
        // The list is a rendering of immutable text with no identity of its own; the index is the
        // only stable key there is, and nothing here reorders.
        // eslint-disable-next-line react/no-array-index-key
        key={index}
        component="span"
        sx={{ display: 'block', bgcolor: 'primary.main', color: 'primary.contrastText', px: 0.5 }}
      >
        {line}
      </Box>
    ) : (
      // eslint-disable-next-line react/no-array-index-key
      <Box key={index} component="span" sx={{ display: 'block', px: 0.5 }}>
        {line}
      </Box>
    ),
  );
}
