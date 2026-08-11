import { Link as RouterLink } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation } from '@tanstack/react-query';
import { Alert, Box, Button, Link, Stack, TextField, Typography } from '@mui/material';
import MarkEmailReadIcon from '@mui/icons-material/MarkEmailReadOutlined';
import { AuthLayout } from '@/layouts/AuthLayout';
import { authApi } from '../api/authApi';
import { problemMessage } from '@/lib/api/problem';

const schema = z.object({
  email: z.string().trim().min(1, 'Enter your email address').email('Enter a valid email address'),
});

type FormValues = z.infer<typeof schema>;

export default function ForgotPasswordPage() {
  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { email: '' } });

  const request = useMutation({
    mutationFn: (values: FormValues) => authApi.forgotPassword(values.email.trim()),
  });

  /*
   * The confirmation is deliberately non-committal — "if an account exists" — and is shown
   * on success regardless of whether the address is registered. The server behaves the same
   * way. Anything else turns this screen into a free tool for discovering which addresses
   * have accounts, which is the first step of every credential-stuffing campaign.
   */
  if (request.isSuccess) {
    return (
      <AuthLayout title="Check your email">
        <Stack spacing={3}>
          <Alert severity="success" icon={<MarkEmailReadIcon />}>
            If an account exists for <strong>{getValues('email')}</strong>, we have sent a link to
            reset the password. The link expires in 30 minutes and can be used once.
          </Alert>
          <Typography variant="body2" color="text.secondary">
            Nothing arrived? Check the spam folder, or ask your administrator to confirm the address
            on your account.
          </Typography>
          <Button component={RouterLink} to="/login" variant="contained" size="large" fullWidth>
            Back to sign in
          </Button>
        </Stack>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="Enter the email address on your AquaGrid account and we will send you a reset link."
    >
      <Box component="form" noValidate onSubmit={handleSubmit((values) => request.mutate(values))}>
        <Stack spacing={2.5}>
          <Box role="alert" aria-live="assertive">
            {request.isError ? (
              <Alert severity="error">{problemMessage(request.error)}</Alert>
            ) : null}
          </Box>

          <TextField
            {...register('email')}
            label="Email address"
            type="email"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            autoFocus
            error={Boolean(errors.email)}
            helperText={errors.email?.message}
            fullWidth
            required
          />

          <Button
            type="submit"
            variant="contained"
            size="large"
            fullWidth
            loading={request.isPending}
          >
            Send reset link
          </Button>

          <Link component={RouterLink} to="/login" variant="body2" underline="hover" textAlign="center">
            Back to sign in
          </Link>
        </Stack>
      </Box>
    </AuthLayout>
  );
}
