import { useMemo } from 'react';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Link, Stack, Typography } from '@mui/material';
import { AuthLayout } from '@/layouts/AuthLayout';
import { PasswordField } from '../components/PasswordField';
import { PasswordRequirements } from '../components/PasswordRequirements';
import { authApi } from '../api/authApi';
import { queryKeys } from '@/lib/api/queryClient';
import { problemMessage, toProblem } from '@/lib/api/problem';

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token') ?? '';

  const { data: policy } = useQuery({
    queryKey: queryKeys.auth.passwordPolicy,
    queryFn: authApi.passwordPolicy,
    staleTime: 30 * 60_000,
  });

  /* The schema is built from the server's policy, so the two cannot drift apart. */
  const schema = useMemo(
    () =>
      z
        .object({
          newPassword: z
            .string()
            .min(policy?.minLength ?? 12, `Use at least ${policy?.minLength ?? 12} characters`)
            .max(policy?.maxLength ?? 128, 'That password is too long'),
          confirmPassword: z.string(),
        })
        .refine((values) => values.newPassword === values.confirmPassword, {
          message: 'The two passwords do not match',
          path: ['confirmPassword'],
        }),
    [policy],
  );

  type FormValues = z.infer<typeof schema>;

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { newPassword: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  const newPassword = useWatch({ control, name: 'newPassword' }) ?? '';

  const reset = useMutation({
    mutationFn: (values: FormValues) => authApi.resetPassword(token, values.newPassword),
    onSuccess: () => {
      window.setTimeout(() => navigate('/login', { replace: true }), 2000);
    },
  });

  if (!token) {
    return (
      <AuthLayout title="Invalid reset link">
        <Stack spacing={3}>
          <Alert severity="error">
            This password reset link is incomplete. Request a new one and use the most recent email.
          </Alert>
          <Button component={RouterLink} to="/forgot-password" variant="contained" size="large" fullWidth>
            Request a new link
          </Button>
        </Stack>
      </AuthLayout>
    );
  }

  if (reset.isSuccess) {
    return (
      <AuthLayout title="Password updated">
        <Stack spacing={3}>
          <Alert severity="success">
            Your password has been changed and every existing session has been signed out. Taking
            you to sign in&hellip;
          </Alert>
          <Button component={RouterLink} to="/login" variant="contained" size="large" fullWidth>
            Sign in
          </Button>
        </Stack>
      </AuthLayout>
    );
  }

  const expiredLink = reset.isError && toProblem(reset.error).code === 'PASSWORD_RESET_TOKEN_INVALID';

  return (
    <AuthLayout
      title="Choose a new password"
      subtitle="For your security, all devices will be signed out once the password changes."
    >
      <Box component="form" noValidate onSubmit={handleSubmit((values) => reset.mutate(values))}>
        <Stack spacing={2.5}>
          <Box role="alert" aria-live="assertive">
            {reset.isError ? (
              <Alert
                severity="error"
                action={
                  expiredLink ? (
                    <Button component={RouterLink} to="/forgot-password" size="small" color="inherit">
                      New link
                    </Button>
                  ) : undefined
                }
              >
                {problemMessage(reset.error)}
              </Alert>
            ) : null}
          </Box>

          <PasswordField
            {...register('newPassword')}
            label="New password"
            autoComplete="new-password"
            autoFocus
            error={Boolean(errors.newPassword)}
            helperText={errors.newPassword?.message}
            fullWidth
            required
          />

          <PasswordRequirements policy={policy} value={newPassword} />

          <PasswordField
            {...register('confirmPassword')}
            label="Confirm new password"
            autoComplete="new-password"
            error={Boolean(errors.confirmPassword)}
            helperText={errors.confirmPassword?.message}
            fullWidth
            required
          />

          <Button type="submit" variant="contained" size="large" fullWidth loading={reset.isPending}>
            Update password
          </Button>

          <Typography variant="caption" color="text.secondary" textAlign="center">
            Remembered it? <Link component={RouterLink} to="/login" underline="hover">Sign in</Link>
          </Typography>
        </Stack>
      </Box>
    </AuthLayout>
  );
}
