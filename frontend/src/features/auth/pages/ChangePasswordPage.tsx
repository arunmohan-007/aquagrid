import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import { AuthLayout } from '@/layouts/AuthLayout';
import { PasswordField } from '../components/PasswordField';
import { PasswordRequirements } from '../components/PasswordRequirements';
import { authApi } from '../api/authApi';
import { queryKeys } from '@/lib/api/queryClient';
import { problemMessage } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthProvider';

/**
 * Forced and voluntary password change.
 *
 * Reached automatically when the token carries `mustChangePassword` — which is the state
 * of every bootstrap administrator and every account an administrator has reset. The user
 * cannot navigate past it, because an account still holding a credential someone else
 * chose is not yet the user's account.
 */
export default function ChangePasswordPage() {
  const navigate = useNavigate();
  const { user, signOut } = useAuth();

  const { data: policy } = useQuery({
    queryKey: queryKeys.auth.passwordPolicy,
    queryFn: authApi.passwordPolicy,
    staleTime: 30 * 60_000,
  });

  const schema = useMemo(
    () =>
      z
        .object({
          currentPassword: z.string().min(1, 'Enter your current password'),
          newPassword: z
            .string()
            .min(policy?.minLength ?? 12, `Use at least ${policy?.minLength ?? 12} characters`)
            .max(policy?.maxLength ?? 128, 'That password is too long'),
          confirmPassword: z.string(),
        })
        .refine((values) => values.newPassword === values.confirmPassword, {
          message: 'The two passwords do not match',
          path: ['confirmPassword'],
        })
        .refine((values) => values.newPassword !== values.currentPassword, {
          message: 'Your new password must be different from your current one',
          path: ['newPassword'],
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
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  const newPassword = useWatch({ control, name: 'newPassword' }) ?? '';

  const change = useMutation({
    mutationFn: (values: FormValues) =>
      authApi.changePassword(values.currentPassword, values.newPassword),
    onSuccess: async () => {
      /*
       * The server revoked every session, including this one, so the access token in
       * memory is about to stop working. Signing out locally and returning to /login is
       * the honest representation of what just happened — anything else leaves the user
       * clicking a dead UI.
       */
      await signOut();
      navigate('/login', { replace: true, state: { passwordChanged: true } });
    },
  });

  const forced = user?.mustChangePassword ?? false;

  return (
    <AuthLayout
      title={forced ? 'Set a new password' : 'Change your password'}
      subtitle={
        forced
          ? 'Your account is using a password that was set for you. Choose your own before continuing.'
          : 'All of your devices will be signed out once the password changes.'
      }
    >
      <Box component="form" noValidate onSubmit={handleSubmit((values) => change.mutate(values))}>
        <Stack spacing={2.5}>
          <Box role="alert" aria-live="assertive">
            {change.isError ? <Alert severity="error">{problemMessage(change.error)}</Alert> : null}
          </Box>

          <PasswordField
            {...register('currentPassword')}
            label="Current password"
            autoComplete="current-password"
            autoFocus
            error={Boolean(errors.currentPassword)}
            helperText={errors.currentPassword?.message}
            fullWidth
            required
          />

          <PasswordField
            {...register('newPassword')}
            label="New password"
            autoComplete="new-password"
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

          <Button type="submit" variant="contained" size="large" fullWidth loading={change.isPending}>
            Update password
          </Button>

          {!forced ? (
            <Button variant="text" onClick={() => navigate(-1)} fullWidth>
              Cancel
            </Button>
          ) : (
            <Typography variant="caption" color="text.secondary" textAlign="center">
              You cannot continue until your password has been changed.
            </Typography>
          )}
        </Stack>
      </Box>
    </AuthLayout>
  );
}
