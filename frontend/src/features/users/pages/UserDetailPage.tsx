import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBackOutlined';
import DeleteIcon from '@mui/icons-material/DeleteOutlined';
import KeyIcon from '@mui/icons-material/VpnKeyOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { problemMessage } from '@/lib/api/problem';
import {
  useAdminResetPassword,
  useAssignRoles,
  useChangeUserStatus,
  useDeleteUser,
  useRoles,
  useUser,
} from '../hooks/useUsers';
import { UserStatusChip } from '../components/UserStatusChip';
import type { UserStatus } from '../types';

const STATUS_OPTIONS: UserStatus[] = ['ACTIVE', 'PENDING', 'DISABLED', 'LOCKED'];

/**
 * Single-user administration view.
 *
 * Status changes, role assignment, password reset and deletion all flow through dedicated
 * mutations that update the cached detail on success, so the page reflects the new state without
 * a refetch. The self-modification guards live server-side; here we only hide actions that the
 * signed-in user could not complete on themselves, as a UX measure.
 */
export default function UserDetailPage() {
  const { userId = '' } = useParams();
  const navigate = useNavigate();
  const { user: currentUser } = useAuth();

  const { data: user, isLoading, error } = useUser(userId);
  const { data: roles } = useRoles();
  const changeStatus = useChangeUserStatus();
  const assignRoles = useAssignRoles();
  const resetPassword = useAdminResetPassword();
  const deleteUser = useDeleteUser();

  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [resetOpen, setResetOpen] = useState(false);

  if (isLoading) {
    return (
      <Typography variant="body2" color="text.secondary">
        Loading…
      </Typography>
    );
  }
  if (error || !user) {
    return (
      <Alert severity="error" variant="outlined">
        Could not load this user. {problemMessage(error)}
      </Alert>
    );
  }

  const isSelf = currentUser?.id === user.id;
  const tenantRoles = roles ?? [];

  const handleStatus = async (status: UserStatus) => {
    setStatusMessage(null);
    try {
      await changeStatus.mutateAsync({ userId, status });
    } catch (err) {
      setStatusMessage(problemMessage(err));
    }
  };

  const handleRoles = async (roleCodes: string[]) => {
    setStatusMessage(null);
    try {
      await assignRoles.mutateAsync({ userId, roleCodes });
    } catch (err) {
      setStatusMessage(problemMessage(err));
    }
  };

  const handleDelete = async () => {
    try {
      await deleteUser.mutateAsync(userId);
      navigate('/users');
    } catch (err) {
      setStatusMessage(problemMessage(err));
    }
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" alignItems="center" spacing={1}>
        <IconButton onClick={() => navigate('/users')} aria-label="Back to users">
          <ArrowBackIcon />
        </IconButton>
        <Box>
          <Typography variant="h1">{user.fullName}</Typography>
          <Typography variant="body2" color="text.secondary">
            {user.email} · @{user.username}
          </Typography>
        </Box>
      </Stack>

      {statusMessage ? (
        <Alert severity="error" variant="outlined">
          {statusMessage}
        </Alert>
      ) : null}

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2.5}>
        <Card variant="outlined" sx={{ flex: 2 }}>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h3">Profile</Typography>
              <DetailRow label="Status">
                <UserStatusChip status={user.status} />
              </DetailRow>
              <DetailRow label="Job title">{user.jobTitle || '—'}</DetailRow>
              <DetailRow label="Phone">{user.phone || '—'}</DetailRow>
              <DetailRow label="Two-step verification">
                {user.mfaEnabled ? 'Enabled' : 'Not enabled'}
              </DetailRow>
              <DetailRow label="Last sign-in">
                {user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : 'Never'}
                {user.lastLoginIp ? ` from ${user.lastLoginIp}` : ''}
              </DetailRow>
              <DetailRow label="Created">{new Date(user.createdAt).toLocaleString()}</DetailRow>
            </Stack>
          </CardContent>
        </Card>

        <Card variant="outlined" sx={{ flex: 3 }}>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h3">Access</Typography>

              <Box>
                <Typography variant="caption" color="text.secondary">
                  Roles
                </Typography>
                {isSelf ? (
                  <Typography variant="body2" sx={{ mt: 0.5 }}>
                    Ask another administrator to change your roles.
                  </Typography>
                ) : (
                  <FormControl fullWidth size="small" sx={{ mt: 0.75 }}>
                    <InputLabel>Assigned roles</InputLabel>
                    <Select
                      multiple
                      value={user.roles}
                      label="Assigned roles"
                      onChange={(e) => {
                        const value = typeof e.target.value === 'string' ? e.target.value.split(',') : e.target.value;
                        void handleRoles(value as string[]);
                      }}
                      renderValue={(selected) => (
                        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                          {(selected as string[]).map((code) => (
                            <Chip key={code} size="small" label={code} />
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
                )}
              </Box>

              <Box>
                <Typography variant="caption" color="text.secondary">
                  Effective permissions ({user.permissions.length})
                </Typography>
                <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>
                  {user.permissions.map((p) => (
                    <Chip key={p} label={p} size="small" variant="outlined" sx={{ fontFamily: 'monospace', fontSize: 11 }} />
                  ))}
                </Stack>
              </Box>
            </Stack>
          </CardContent>
        </Card>
      </Stack>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2} flexWrap="wrap" useFlexGap>
            <Box>
              <Typography variant="h3">Account controls</Typography>
              <Typography variant="caption" color="text.secondary">
                Disabling or locking the account revokes every active session immediately.
              </Typography>
            </Box>
            <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
              {isSelf ? null : (
                <>
                  <Button
                    variant="outlined"
                    startIcon={<KeyIcon />}
                    onClick={() => setResetOpen(true)}
                    disabled={user.status === 'DISABLED'}
                  >
                    Reset password
                  </Button>
                  <Button
                    variant="outlined"
                    color="error"
                    startIcon={<DeleteIcon />}
                    onClick={handleDelete}
                    disabled={deleteUser.isPending}
                  >
                    Delete user
                  </Button>
                </>
              )}
            </Stack>
          </Stack>
          {!isSelf ? (
            <Box sx={{ mt: 2 }}>
              <FormControl size="small" sx={{ minWidth: 200 }}>
                <InputLabel>Set status</InputLabel>
                <Select
                  value={user.status}
                  label="Set status"
                  onChange={(e) => void handleStatus(e.target.value as UserStatus)}
                  renderValue={(value) => <UserStatusChip status={value as UserStatus} />}
                >
                  {STATUS_OPTIONS.map((status) => (
                    <MenuItem key={status} value={status}>
                      {status.charAt(0) + status.slice(1).toLowerCase()}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Box>
          ) : null}
        </CardContent>
      </Card>

      <ResetPasswordDialog
        open={resetOpen}
        onClose={() => setResetOpen(false)}
        onSubmit={async (password, mustChange) => {
          try {
            await resetPassword.mutateAsync({ userId, newPassword: password, mustChangePassword: mustChange });
            setResetOpen(false);
            setStatusMessage('Password set. The user must sign in with the new password.');
          } catch (err) {
            setStatusMessage(problemMessage(err));
            setResetOpen(false);
          }
        }}
        pending={resetPassword.isPending}
      />
    </Stack>
  );
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="center">
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Box>{children}</Box>
    </Stack>
  );
}

function ResetPasswordDialog({
  open,
  onClose,
  onSubmit,
  pending,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (password: string, mustChange: boolean) => void;
  pending: boolean;
}) {
  const [password, setPassword] = useState('');
  const [mustChange, setMustChange] = useState(true);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Reset password</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="warning" variant="outlined">
            All of the user&apos;s sessions will be revoked.
          </Alert>
          <TextField
            label="New password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            fullWidth
            required
          />
          <FormControl fullWidth>
            <InputLabel>Require change on next sign-in</InputLabel>
            <Select
              value={mustChange ? 'yes' : 'no'}
              label="Require change on next sign-in"
              onChange={(e) => setMustChange(e.target.value === 'yes')}
            >
              <MenuItem value="yes">Yes</MenuItem>
              <MenuItem value="no">No</MenuItem>
            </Select>
          </FormControl>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={pending || !password} onClick={() => onSubmit(password, mustChange)}>
          {pending ? 'Setting…' : 'Set password'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
