import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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
  Divider,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { authApi } from '../api/authApi';
import { OtpInput } from '../components/OtpInput';
import { queryKeys } from '@/lib/api/queryClient';
import { problemMessage } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthProvider';
import type { MfaSetup } from '../types';

/**
 * Security self-service: two-step verification and active device sessions.
 *
 * These two live together because they answer the same question — "is anyone else in my
 * account?" — and because giving users a way to see and end their own sessions removes the
 * support path where an administrator revokes sessions on request, which is itself a
 * social-engineering target.
 */
export default function SecurityPage() {
  const { user, refreshUser, signOut } = useAuth();
  const queryClient = useQueryClient();
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [activationCode, setActivationCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [disableDialogOpen, setDisableDialogOpen] = useState(false);

  const sessions = useQuery({
    queryKey: queryKeys.auth.sessions,
    queryFn: authApi.sessions,
    staleTime: 15_000,
  });

  const beginEnrolment = useMutation({
    mutationFn: authApi.beginMfaEnrolment,
    onSuccess: setSetup,
  });

  const activate = useMutation({
    mutationFn: (code: string) => authApi.activateMfa(code),
    onSuccess: async (result) => {
      setSetup(null);
      setActivationCode('');
      // Shown once and only once: the server stores hashes, so these cannot be re-issued.
      setRecoveryCodes(result.recoveryCodes);
      await refreshUser();
      await queryClient.invalidateQueries({ queryKey: queryKeys.auth.sessions });
    },
  });

  const revoke = useMutation({
    mutationFn: (sessionId: string) => authApi.revokeSession(sessionId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.auth.sessions }),
  });

  return (
    <Stack spacing={3} sx={{ maxWidth: 880 }}>
      <Typography variant="h1">Security &amp; sessions</Typography>

      {/* ---- Two-step verification ------------------------------------------------- */}
      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2.5}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
              <Box>
                <Typography variant="h3">Two-step verification</Typography>
                <Typography variant="body2" color="text.secondary">
                  A code from your authenticator app, in addition to your password.
                </Typography>
              </Box>
              <Chip
                label={user?.mfaEnabled ? 'Enabled' : 'Not enabled'}
                color={user?.mfaEnabled ? 'success' : 'default'}
                variant={user?.mfaEnabled ? 'filled' : 'outlined'}
              />
            </Stack>

            {beginEnrolment.isError ? (
              <Alert severity="error">{problemMessage(beginEnrolment.error)}</Alert>
            ) : null}
            {activate.isError ? (
              <Alert severity="error">{problemMessage(activate.error)}</Alert>
            ) : null}

            {recoveryCodes ? (
              <Alert severity="warning">
                <Typography variant="subtitle1" gutterBottom>
                  Save your recovery codes now
                </Typography>
                <Typography variant="body2" gutterBottom>
                  These are shown once. Each works a single time, and they are the only way back
                  into your account if you lose your phone.
                </Typography>
                <Box className="grid grid-cols-2 gap-1 font-mono text-sm sm:grid-cols-3" sx={{ my: 1.5 }}>
                  {recoveryCodes.map((code) => (
                    <span key={code}>{code}</span>
                  ))}
                </Box>
                <Stack direction="row" spacing={1}>
                  <Button
                    size="small"
                    startIcon={<ContentCopyIcon />}
                    onClick={() => navigator.clipboard.writeText(recoveryCodes.join('\n'))}
                  >
                    Copy all
                  </Button>
                  <Button size="small" onClick={() => setRecoveryCodes(null)}>
                    I have saved them
                  </Button>
                </Stack>
              </Alert>
            ) : null}

            {setup ? (
              <Stack spacing={2}>
                <Typography variant="body2">
                  Scan this in your authenticator app, or enter the key manually, then type the
                  6-digit code it shows.
                </Typography>
                <Box className="flex flex-col gap-3 sm:flex-row sm:items-center">
                  <QrCode value={setup.provisioningUri} />
                  <Stack spacing={1}>
                    <Typography variant="caption" color="text.secondary">
                      Manual entry key
                    </Typography>
                    <TextField
                      value={setup.secret}
                      size="small"
                      slotProps={{ input: { readOnly: true, className: 'font-mono text-sm' } }}
                      sx={{ maxWidth: 340 }}
                    />
                  </Stack>
                </Box>
                <OtpInput value={activationCode} onChange={setActivationCode} autoFocus />
                <Stack direction="row" spacing={1}>
                  <Button
                    variant="contained"
                    disabled={activationCode.length !== 6}
                    loading={activate.isPending}
                    onClick={() => activate.mutate(activationCode)}
                  >
                    Activate
                  </Button>
                  <Button onClick={() => setSetup(null)}>Cancel</Button>
                </Stack>
              </Stack>
            ) : user?.mfaEnabled ? (
              <Box>
                <Button color="error" variant="outlined" onClick={() => setDisableDialogOpen(true)}>
                  Turn off two-step verification
                </Button>
              </Box>
            ) : (
              <Box>
                <Button
                  variant="contained"
                  loading={beginEnrolment.isPending}
                  onClick={() => beginEnrolment.mutate()}
                >
                  Set up two-step verification
                </Button>
              </Box>
            )}
          </Stack>
        </CardContent>
      </Card>

      {/* ---- Sessions ---------------------------------------------------------------- */}
      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Box>
                <Typography variant="h3">Where you are signed in</Typography>
                <Typography variant="body2" color="text.secondary">
                  End any session you do not recognise.
                </Typography>
              </Box>
              <Button color="error" variant="outlined" onClick={() => void signOut({ allDevices: true })}>
                Sign out everywhere
              </Button>
            </Stack>

            {revoke.isError ? <Alert severity="error">{problemMessage(revoke.error)}</Alert> : null}

            <List disablePadding>
              {(sessions.data ?? []).map((session, index) => (
                <Box key={session.id}>
                  {index > 0 ? <Divider component="li" /> : null}
                  <ListItem
                    disableGutters
                    secondaryAction={
                      session.current ? (
                        <Chip label="This device" size="small" color="primary" />
                      ) : (
                        <IconButton
                          edge="end"
                          aria-label={`Sign out ${session.deviceLabel ?? 'this device'}`}
                          onClick={() => revoke.mutate(session.id)}
                          disabled={revoke.isPending}
                        >
                          <DeleteIcon />
                        </IconButton>
                      )
                    }
                  >
                    <ListItemText
                      primary={session.deviceLabel ?? 'Unknown device'}
                      secondary={
                        <>
                          {session.clientIp ?? 'Unknown address'} · signed in{' '}
                          {new Date(session.issuedAt).toLocaleString()}
                          {session.lastUsedAt
                            ? ` · last active ${new Date(session.lastUsedAt).toLocaleString()}`
                            : ''}
                        </>
                      }
                    />
                  </ListItem>
                </Box>
              ))}
              {sessions.isSuccess && sessions.data.length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  No other active sessions.
                </Typography>
              ) : null}
            </List>
          </Stack>
        </CardContent>
      </Card>

      <DisableMfaDialog open={disableDialogOpen} onClose={() => setDisableDialogOpen(false)} />
    </Stack>
  );
}

function DisableMfaDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { refreshUser } = useAuth();
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');

  const disable = useMutation({
    mutationFn: () => authApi.disableMfa(password, code),
    onSuccess: async () => {
      await refreshUser();
      setPassword('');
      setCode('');
      onClose();
    },
  });

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Turn off two-step verification</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">
            Your account will be protected by your password alone.
          </Alert>
          {disable.isError ? <Alert severity="error">{problemMessage(disable.error)}</Alert> : null}
          {/* Both factors are required to remove a factor: a stolen session must not be
              able to weaken the account on its own. */}
          <TextField
            label="Password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            fullWidth
          />
          <TextField
            label="Verification code"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            placeholder="6-digit code or recovery code"
            fullWidth
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          color="error"
          variant="contained"
          loading={disable.isPending}
          disabled={!password || !code}
          onClick={() => disable.mutate()}
        >
          Turn off
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * QR rendering for the `otpauth://` URI.
 *
 * Module 1 ships the manual-entry key alongside a link, rather than pulling in a QR
 * library for one screen. The generator is added with the shared component library in
 * Module 2; until then the manual key is a complete, working path for every authenticator
 * app, so nothing is blocked.
 */
function QrCode({ value }: { value: string }) {
  return (
    <Box
      className="flex h-40 w-40 shrink-0 flex-col items-center justify-center gap-2 rounded-lg border border-dashed p-3 text-center"
      sx={{ borderColor: 'divider' }}
    >
      <Typography variant="caption" color="text.secondary">
        Open in your authenticator
      </Typography>
      <Button href={value} size="small" variant="outlined">
        Add account
      </Button>
    </Box>
  );
}
