import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useInviteUser, useRoles } from '../hooks/useUsers';
import { userManagementError } from '../api/usersApi';
import { problemMessage } from '@/lib/api/problem';
import type { InviteUserPayload } from '../types';

/**
 * Invite-user dialog.
 *
 * The form is uncontrolled-ish via local state and submits a typed payload — React Hook Form is
 * the project's choice for the heavier 60-field asset forms; this small form does not need it and
 * the dependency cost would be paid on this chunk for no benefit.
 *
 * On success the one-time invitation token is shown in a copyable box. Until Module 20 (notification
 * centre) delivers invitations by email, the inviter is responsible for conveying it securely.
 */
export function InviteUserDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data: roles } = useRoles();
  const invite = useInviteUser();

  const [form, setForm] = useState<InviteUserPayload>({
    email: '',
    fullName: '',
    username: '',
    jobTitle: '',
    phone: '',
    roleCodes: [],
  });
  const [issuedToken, setIssuedToken] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      setForm({ email: '', fullName: '', username: '', jobTitle: '', phone: '', roleCodes: [] });
      setIssuedToken(null);
      setErrorMessage(null);
      invite.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const update = <K extends keyof InviteUserPayload>(key: K, value: InviteUserPayload[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const handleSubmit = async () => {
    setErrorMessage(null);
    try {
      const result = await invite.mutateAsync(form);
      setIssuedToken(result.token);
    } catch (error) {
      setErrorMessage(problemMessage(error));
    }
  };

  const tenantRoles = roles ?? [];

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Invite a user</DialogTitle>
      <DialogContent>
        {issuedToken ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="success" variant="outlined">
              Invitation created. Share this link with the invitee — it is shown once and cannot be retrieved again.
            </Alert>
            <Typography variant="caption" color="text.secondary">
              Invitation token
            </Typography>
            <TextField
              value={issuedToken}
              size="small"
              InputProps={{ readOnly: true }}
              onFocus={(e) => e.target.select()}
            />
            <Typography variant="caption" color="text.secondary">
              The notification centre (Module 20) will email this automatically. Until then, copy it to the
              invitee over a secure channel.
            </Typography>
          </Stack>
        ) : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errorMessage ? (
              <Alert severity="error" variant="outlined">
                {errorMessage}
              </Alert>
            ) : null}
            <Stack direction="row" spacing={2}>
              <TextField
                label="Full name"
                value={form.fullName}
                onChange={(e) => update('fullName', e.target.value)}
                required
                fullWidth
              />
              <TextField
                label="Username"
                value={form.username}
                onChange={(e) => update('username', e.target.value)}
                required
                helperText="3–60 chars: letters, digits, . _ -"
                fullWidth
              />
            </Stack>
            <TextField
              label="Email"
              type="email"
              value={form.email}
              onChange={(e) => update('email', e.target.value)}
              required
              fullWidth
            />
            <Stack direction="row" spacing={2}>
              <TextField
                label="Job title"
                value={form.jobTitle}
                onChange={(e) => update('jobTitle', e.target.value)}
                fullWidth
              />
              <TextField
                label="Phone"
                value={form.phone}
                onChange={(e) => update('phone', e.target.value)}
                fullWidth
              />
            </Stack>
            <FormControl fullWidth>
              <InputLabel>Roles</InputLabel>
              <Select
                multiple
                value={form.roleCodes ?? []}
                label="Roles"
                onChange={(e) => {
                  const value = typeof e.target.value === 'string' ? e.target.value.split(',') : e.target.value;
                  update('roleCodes', value as string[]);
                }}
                renderValue={(selected) => (
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                    {(selected as string[]).map((code) => (
                      <Chip key={code} size="small" label={code} sx={{ mr: 0.5 }} />
                    ))}
                  </Stack>
                )}
              >
                {tenantRoles.map((role) => (
                  <MenuItem key={role.code} value={role.code}>
                    {role.name} ({role.code})
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {issuedToken ? (
          <Button onClick={onClose}>Done</Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              onClick={handleSubmit}
              variant="contained"
              disabled={invite.isPending || !form.email || !form.fullName || !form.username}
            >
              {invite.isPending ? 'Sending…' : 'Send invitation'}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}

export { userManagementError };
