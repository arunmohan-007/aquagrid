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
import { useCreateUser, useRoles } from '../hooks/useUsers';
import { problemMessage } from '@/lib/api/problem';
import type { CreateUserPayload, UserDetail } from '../types';

const DEFAULT_PASSWORD = '123456';

/**
 * Create-user dialog.
 *
 * Unlike {@link InviteUserDialog}, this creates an active account immediately with the platform
 * default password rather than an invitation link — the account is usable the moment it is
 * created. `mustChangePassword` is enforced by the backend, so the default password is only ever
 * valid for the one sign-in that replaces it.
 */
export function CreateUserDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data: roles } = useRoles();
  const createUser = useCreateUser();

  const [form, setForm] = useState<CreateUserPayload>({
    email: '',
    fullName: '',
    username: '',
    jobTitle: '',
    phone: '',
    roleCodes: [],
  });
  const [created, setCreated] = useState<UserDetail | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      setForm({ email: '', fullName: '', username: '', jobTitle: '', phone: '', roleCodes: [] });
      setCreated(null);
      setErrorMessage(null);
      createUser.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const update = <K extends keyof CreateUserPayload>(key: K, value: CreateUserPayload[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const handleSubmit = async () => {
    setErrorMessage(null);
    try {
      const result = await createUser.mutateAsync(form);
      setCreated(result);
    } catch (error) {
      setErrorMessage(problemMessage(error));
    }
  };

  const tenantRoles = roles ?? [];

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Create a user</DialogTitle>
      <DialogContent>
        {created ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="success" variant="outlined">
              Account created and active. Share the sign-in details with {created.fullName} over a
              secure channel — they will not be shown again here.
            </Alert>
            <Stack spacing={0.5}>
              <Typography variant="caption" color="text.secondary">
                Username
              </Typography>
              <TextField value={created.username} size="small" InputProps={{ readOnly: true }} onFocus={(e) => e.target.select()} />
            </Stack>
            <Stack spacing={0.5}>
              <Typography variant="caption" color="text.secondary">
                Default password
              </Typography>
              <TextField value={DEFAULT_PASSWORD} size="small" InputProps={{ readOnly: true }} onFocus={(e) => e.target.select()} />
            </Stack>
            <Typography variant="caption" color="text.secondary">
              The user must change this password the first time they sign in.
            </Typography>
          </Stack>
        ) : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errorMessage ? (
              <Alert severity="error" variant="outlined">
                {errorMessage}
              </Alert>
            ) : null}
            <Alert severity="info" variant="outlined">
              The account is created with the default password <strong>{DEFAULT_PASSWORD}</strong>,
              active immediately, and must be changed at first sign-in.
            </Alert>
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
        {created ? (
          <Button onClick={onClose}>Done</Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              onClick={handleSubmit}
              variant="contained"
              disabled={createUser.isPending || !form.email || !form.fullName || !form.username}
            >
              {createUser.isPending ? 'Creating…' : 'Create user'}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}
