import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { problemMessage } from '@/lib/api/problem';
import { useSetAttributeActive } from '../hooks/useDataManagement';
import type { LayerAttribute } from '../types';

/**
 * Retire an attribute, or return one to service.
 *
 * <p>The wording is the point. "Delete" would be a lie: nothing is removed, and the values already
 * stored under the field come back intact the moment it is reactivated. Calling it retirement is
 * what makes the reactivate button make sense to someone who has just used it — and it stops an
 * operator from recreating a field they think they destroyed, which would silently adopt every
 * value the original held.
 */
export function RetireAttributeDialog({
  attribute,
  onClose,
}: {
  attribute: LayerAttribute | null;
  onClose: () => void;
}) {
  const setActive = useSetAttributeActive();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const retiring = Boolean(attribute?.active);

  useEffect(() => {
    if (attribute) {
      setReason('');
      setError(null);
    }
  }, [attribute]);

  const confirm = async () => {
    if (!attribute) return;
    setError(null);
    try {
      await setActive.mutateAsync({
        id: attribute.id,
        active: !attribute.active,
        reason: reason.trim() || undefined,
      });
      onClose();
    } catch (cause) {
      setError(problemMessage(cause));
    }
  };

  return (
    <Dialog open={Boolean(attribute)} onClose={setActive.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {retiring ? `Retire ${attribute?.displayName}?` : `Return ${attribute?.displayName} to service?`}
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {error ? (
            <Alert severity="error" variant="outlined">
              {error}
            </Alert>
          ) : null}

          <Alert severity={retiring ? 'warning' : 'info'} variant="outlined">
            {retiring ? (
              <>
                <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
                  Nothing is deleted.
                </Typography>
                <Typography variant="body2">
                  Every value already stored under <code>{attribute?.fieldName}</code> stays exactly
                  where it is. What changes is that the field stops being offered for import mapping,
                  stops appearing in exports and stops being shown on forms. Returning it to service
                  brings all three back, with the historical values.
                </Typography>
              </>
            ) : (
              <Typography variant="body2">
                <code>{attribute?.fieldName}</code> will be offered for import mapping again, appear
                in exports, and show on forms. Values stored before it was retired become readable
                again with it.
              </Typography>
            )}
          </Alert>

          <TextField
            label="Reason"
            size="small"
            fullWidth
            autoFocus
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            helperText={
              retiring
                ? 'Stored on the history entry. This is the only durable record of why a field stopped being imported.'
                : 'Stored on the history entry.'
            }
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} disabled={setActive.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          color={retiring ? 'warning' : 'primary'}
          onClick={confirm}
          disabled={setActive.isPending}
        >
          {retiring ? 'Retire attribute' : 'Return to service'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
