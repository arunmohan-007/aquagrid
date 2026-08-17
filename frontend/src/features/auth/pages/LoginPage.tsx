import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation } from '@tanstack/react-query';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Link,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { AuthLayout } from '@/layouts/AuthLayout';
import { PasswordField } from '../components/PasswordField';
import { authApi } from '../api/authApi';
import { useAuth } from '@/lib/auth/AuthProvider';
import { toProblem } from '@/lib/api/problem';
import type { ProblemDetail } from '@/lib/api/problem';

const schema = z.object({
  identifier: z.string().trim().min(1, 'Enter your username or email'),
  /*
   * No pattern or length rule on the password here. Enforcing the strength policy at
   * sign-in would tell an attacker what the policy is and would lock out any user whose
   * password predates a policy change. Policy applies when a password is *set*.
   */
  password: z.string().min(1, 'Enter your password'),
});

type LoginFormValues = z.infer<typeof schema>;

export default function LoginPage() {
  const navigate = useNavigate();
  const { applyAuthentication, isAuthenticated } = useAuth();
  const [problem, setProblem] = useState<ProblemDetail | null>(null);
  const [retryIn, setRetryIn] = useState(0);

  /*
   * Always the module launcher — never a remembered deep link. Signing in is the moment the
   * operator chooses what to work on, so the board is the landing page every time.
   */
  const redirectTo = '/home';

  const {
    register,
    handleSubmit,
    setFocus,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { identifier: '', password: '' },
    mode: 'onBlur',
  });

  useEffect(() => {
    setFocus('identifier');
  }, [setFocus]);

  useEffect(() => {
    if (isAuthenticated) {
      navigate(redirectTo, { replace: true });
    }
  }, [isAuthenticated, navigate, redirectTo]);

  /* Live countdown for a lockout or rate limit, so the wait is explained rather than mysterious. */
  useEffect(() => {
    if (retryIn <= 0) return;
    const timer = window.setInterval(() => setRetryIn((seconds) => Math.max(0, seconds - 1)), 1000);
    return () => window.clearInterval(timer);
  }, [retryIn]);

  const login = useMutation({
    mutationFn: (values: LoginFormValues) =>
      authApi.login({
        identifier: values.identifier.trim(),
        password: values.password,
      }),
    onSuccess: (response) => {
      setProblem(null);
      if (response.mfaRequired && response.mfaToken) {
        // The MFA token is route state, never a URL parameter: query strings end up in
        // browser history, server logs and Referer headers.
        navigate('/mfa', {
          replace: true,
          state: { mfaToken: response.mfaToken },
        });
        return;
      }
      applyAuthentication(response);
      navigate(response.mustChangePassword ? '/change-password' : redirectTo, { replace: true });
    },
    onError: (error) => {
      const detail = toProblem(error);
      setProblem(detail);
      if (detail.retryAfterSeconds) {
        setRetryIn(detail.retryAfterSeconds);
      }
    },
  });

  const blocked = retryIn > 0;

  return (
    <AuthLayout
      title="Sign in"
      subtitle="Access your organisation's water network operations."
      footer={
        <Typography variant="caption" color="text.secondary">
          AquaGrid · Enterprise Smart Water Management Platform
        </Typography>
      }
    >
      <Box
        component="form"
        noValidate
        onSubmit={handleSubmit((values) => login.mutate(values))}
      >
        <Stack spacing={2.5}>
          {/* One live region for async status; screen readers announce failures as they arrive. */}
          <Box role="alert" aria-live="assertive">
            {problem ? (
              <Alert severity={problem.status === 429 || problem.status === 423 ? 'warning' : 'error'}>
                <AlertTitle sx={{ mb: 0.25 }}>{problem.title ?? 'Sign-in failed'}</AlertTitle>
                <Typography variant="body2">{problem.detail}</Typography>
                {blocked ? (
                  <Typography variant="caption" color="text.secondary">
                    You can try again in {formatCountdown(retryIn)}.
                  </Typography>
                ) : null}
              </Alert>
            ) : null}
          </Box>

          <TextField
            {...register('identifier')}
            label="Username or email"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            error={Boolean(errors.identifier)}
            helperText={errors.identifier?.message}
            fullWidth
            required
          />

          <PasswordField
            {...register('password')}
            label="Password"
            autoComplete="current-password"
            error={Boolean(errors.password)}
            helperText={errors.password?.message}
            fullWidth
            required
          />

          <Button
            type="submit"
            variant="contained"
            size="large"
            fullWidth
            /*
             * Disabled only while the request is in flight or while genuinely locked out —
             * never merely because the form is invalid. A disabled submit button hides the
             * reason the form will not go through.
             */
            loading={login.isPending}
            disabled={blocked}
          >
            {blocked ? `Try again in ${formatCountdown(retryIn)}` : 'Sign in'}
          </Button>

          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Link component={RouterLink} to="/forgot-password" variant="body2" underline="hover">
              Forgot your password?
            </Link>
            <Typography variant="caption" color="text.secondary">
              Need access? Contact your administrator.
            </Typography>
          </Stack>
        </Stack>
      </Box>
    </AuthLayout>
  );
}

function formatCountdown(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes === 1 ? '' : 's'}`;
}
