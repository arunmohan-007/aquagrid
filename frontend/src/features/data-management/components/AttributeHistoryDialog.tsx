import {
  Alert,
  Box,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import { useAttributeHistory } from '../hooks/useDataManagement';
import { CHANGE_TYPE_LABELS, formatTimestamp, shortActor } from '../labels';
import type { AttributeHistoryEntry, LayerAttribute } from '../types';

/**
 * An attribute's definition history.
 *
 * <p>Distinct from the platform audit trail, which answers who changed what and when. This answers
 * what the field <em>meant</em> at the time a given value was written — the question that arises
 * when a field was widened, retyped or retired and revived, and there is data on every side of the
 * change with nothing else to interpret it by.
 *
 * <p>Only the fields that actually changed are listed per entry. Rendering the whole definition on
 * every row would bury the one line that matters under seventeen that did not move.
 */
export function AttributeHistoryDialog({
  attribute,
  onClose,
}: {
  attribute: LayerAttribute | null;
  onClose: () => void;
}) {
  const { data, isLoading, error } = useAttributeHistory(attribute?.id);
  const entries = data?.content ?? [];

  return (
    <Dialog open={Boolean(attribute)} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        History — {attribute?.displayName}
        <Typography variant="body2" color="text.secondary">
          What this field&apos;s definition was, at every change.
        </Typography>
      </DialogTitle>
      <DialogContent dividers>
        {error ? (
          <Alert severity="error" variant="outlined">
            Could not load the history. {(error as Error).message}
          </Alert>
        ) : isLoading ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : entries.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No changes recorded since this field was created.
          </Typography>
        ) : (
          <Stack spacing={2}>
            {entries.map((entry) => (
              <HistoryRow key={entry.id} entry={entry} />
            ))}
          </Stack>
        )}
      </DialogContent>
    </Dialog>
  );
}

function HistoryRow({ entry }: { entry: AttributeHistoryEntry }) {
  const changes = diff(entry.previousState, entry.newState);

  return (
    <Box sx={{ borderLeft: 2, borderColor: 'divider', pl: 2 }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Chip
          size="small"
          variant="outlined"
          label={CHANGE_TYPE_LABELS[entry.changeType] ?? entry.changeType}
          sx={{ height: 20, fontSize: 11 }}
        />
        <Typography variant="caption" color="text.secondary">
          {formatTimestamp(entry.changedAt)} · {shortActor(entry.changedBy)}
        </Typography>
      </Stack>

      {entry.changeReason ? (
        <Typography variant="body2" sx={{ mt: 0.5, fontStyle: 'italic' }}>
          “{entry.changeReason}”
        </Typography>
      ) : null}

      {changes.length > 0 ? (
        <Stack sx={{ mt: 0.75 }} spacing={0.25}>
          {changes.map((change) => (
            <Typography key={change.key} variant="caption" color="text.secondary">
              <Box component="span" sx={{ fontWeight: 600 }}>
                {change.key}
              </Box>
              : {change.from} → {change.to}
            </Typography>
          ))}
        </Stack>
      ) : null}
    </Box>
  );
}

interface Change {
  key: string;
  from: string;
  to: string;
}

/** The keys whose values differ between two snapshots, rendered readably. */
function diff(
  previous: Record<string, unknown> | null,
  next: Record<string, unknown> | null,
): Change[] {
  if (!previous || !next) return [];
  const keys = new Set([...Object.keys(previous), ...Object.keys(next)]);
  const changes: Change[] = [];
  keys.forEach((key) => {
    const from = previous[key];
    const to = next[key];
    if (JSON.stringify(from) !== JSON.stringify(to)) {
      changes.push({ key, from: render(from), to: render(to) });
    }
  });
  return changes;
}

function render(value: unknown): string {
  if (value === null || value === undefined || value === '') return '(none)';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return String(value);
}
