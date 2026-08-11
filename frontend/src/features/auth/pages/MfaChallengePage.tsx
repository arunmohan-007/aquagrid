import { useEffect, useState } from 'react';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { Alert, AlertTitle, Box, Button, Link, Stack, TextField, Typography } from '@mui/material';
import ShieldIcon from '@mui/icons-material/VerifiedUserOutlined';
import { AuthLayout } from '@/layouts/AuthLayout';
import { OtpInput } from '../components/OtpInput';
import { authApi } from '../api/authApi';
import { useAuth } from '@/lib/auth/AuthProvider';
import { toProblem, type ProblemDetail } from '@/lib/api/problem';

/** The `mfaToken` lives here for five minutes. */
interface MfaRouteState {
  mfaToken?: string;
  from?: string;
}

export default function MfaChallengePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { applyAuthentication } = useAuth();
  const state = (location.state ?? {}) as MfaRouteState;

  const [code, setCode] = useState('');
  const [recoveryCode, setRecoveryCode] = useState('');
  const [useRecovery, setUseRecovery] = useState(false);
  const [problem, setProblem] = useState<ProblemDetail | null>(null);

  /*
   * Reaching this screen without a token means the browser was refreshed or the URL was
   * opened directly. There is nothing to verify, so return to the start rather than
   * rendering a form that can only fail.
   */
  useEffect(() => {
    if (!state.mfaToken) {
      navigate('/login', { replace: true });
    }
  }, [state.mfaToken, navigate]);

  const challenge = useMutation({
    mutationFn: (submitted: string) =>
      authApi.mfaChallenge({ mfaToken: state.mfaToken ?? '', code: submitted }),
    onSuccess: (response) => {
      applyAuthentication(response);
      // Same rule as the password step: verification completes onto the launcher, not a deep link.
      navigate(response.mustChangePassword ? '/change-password' : '/home', { replace: true });
    },
    onError: (error) => {
      const detail = toProblem(error);
      setProblem(detail);
      setCode('');
      // The verification session itself has expired — a new code cannot help.
      if (detail.code === 'AUTH_TOKEN_INVALID' || detail.code === 'AUTH_TOKEN_EXPIRED') {
        window.setTimeout(() => navigate('/login', { replace: true }), 2500);
      }
    },
  });

  const submit = (submitted: string) => {
    if (!submitted || challenge.isPending) return;
    setProblem(null);
    challenge.mutate(submitted);
  };

  return (
    <AuthLayout
      title="Two-step verification"
      subtitle={
        useRecovery
          ? 'Enter one of the recovery codes you saved when you set up verification.'
          : 'Enter the 6-digit code from your authenticator app.'
      }
    >
      <Stack spacing={3}>
        <Box role="alert" aria-live="assertive">
          {problem ? (
            <Alert severity="error">
              <AlertTitle sx={{ mb: 0.25 }}>Verification failed</AlertTitle>
              <Typography variant="body2">{problem.detail}</Typography>
            </Alert>
          ) : null}
        </Box>

        {useRecovery ? (
          <Box
            component="form"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              submit(recoveryCode.trim().toUpperCase());
            }}
          >
            <Stack spacing={2.5}>
              <TextField
                label="Recovery code"
                placeholder="XXXXX-XXXXX"
                value={recoveryCode}
                onChange={(event) => setRecoveryCode(event.target.value.toUpperCase())}
                autoComplete="one-time-code"
                autoCapitalize="characters"
                spellCheck={false}
                autoFocus
                fullWidth
                helperText="Each recovery code can be used only once."
              />
              <Button
                type="submit"
                variant="contained"
                size="large"
                fullWidth
                loading={challenge.isPending}
              >
                Verify
              </Button>
            </Stack>
          </Box>
        ) : (
          <Stack spacing={2.5} alignItems="center">
            <OtpInput
              value={code}
              onChange={setCode}
              /* Six digits with nothing to review — submitting automatically removes a
                 pointless click from a step every user performs daily. */
              onComplete={submit}
              disabled={challenge.isPending}
              autoFocus
            />
            <Button
              variant="contained"
              size="large"
              fullWidth
              onClick={() => submit(code)}
              loading={challenge.isPending}
              disabled={code.length !== 6}
              startIcon={<ShieldIcon />}
            >
              Verify
            </Button>
          </Stack>
        )}

        <Stack spacing={1} alignItems="center">
          <Link
            component="button"
            type="button"
            variant="body2"
            underline="hover"
            onClick={() => {
              setUseRecovery((current) => !current);
              setProblem(null);
            }}
          >
            {useRecovery ? 'Use your authenticator app instead' : 'Use a recovery code instead'}
          </Link>
          <Link component={RouterLink} to="/login" variant="caption" underline="hover">
            Back to sign in
          </Link>
        </Stack>
      </Stack>
    </AuthLayout>
  );
}
