import { Avatar, Box, Card, CardContent, Chip, Grid, Stack, Typography } from '@mui/material';
import PersonIcon from '@mui/icons-material/PersonOutlineOutlined';
import ShieldIcon from '@mui/icons-material/VerifiedUserOutlined';
import KeyIcon from '@mui/icons-material/VpnKeyOutlined';
import BusinessIcon from '@mui/icons-material/ApartmentOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import type { ReactNode } from 'react';

/**
 * The Profile module — who you are signed in as and what that account can do.
 *
 * This content used to live on the Dashboard. It was never operational data: it answers
 * "who am I, which tenant am I in, what am I allowed to do", which is an account question.
 * The Dashboard is now free to carry the operational picture (alarms, ingest lag, NRW).
 *
 * Everything here is read-only and comes from the session (`/auth/me`); editing a profile
 * lands with the account self-service endpoints.
 */
export default function ProfilePage() {
  const { user } = useAuth();
  if (!user) return null;

  const org = user.organization;

  const initials = user.fullName
    .split(' ')
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={1.5} alignItems="center">
        <PersonIcon color="primary" sx={{ fontSize: 30 }} />
        <Box>
          <Typography variant="h1">Profile</Typography>
          <Typography variant="body2" color="text.secondary">
            {org.name} · {org.type.replaceAll('_', ' ').toLowerCase()}
          </Typography>
        </Box>
      </Stack>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" spacing={2} alignItems="center">
            <Avatar
              src={user.avatarUrl}
              sx={{ width: 56, height: 56, bgcolor: 'primary.main', fontSize: 18 }}
            >
              {initials}
            </Avatar>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="h3" noWrap>
                {user.fullName}
              </Typography>
              <Typography variant="body2" color="text.secondary" noWrap>
                {user.jobTitle ? `${user.jobTitle} · ` : ''}
                {user.username} · {user.email}
              </Typography>
            </Box>
          </Stack>
        </CardContent>
      </Card>

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 6, md: 4 }}>
          <StatCard
            icon={<BusinessIcon />}
            label="Organisation"
            value={org.name}
            caption={org.code}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4 }}>
          <StatCard
            icon={<KeyIcon />}
            label="Permissions"
            value={String(user.permissions.length)}
            caption={`across ${user.roles.length} role${user.roles.length === 1 ? '' : 's'}`}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4 }}>
          <StatCard
            icon={<ShieldIcon />}
            label="Two-step verification"
            value={user.mfaEnabled ? 'Enabled' : 'Not enabled'}
            caption={user.mfaEnabled ? 'Your account is protected' : 'Enable it under Security'}
            tone={user.mfaEnabled ? 'success.main' : 'warning.main'}
          />
        </Grid>
      </Grid>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Typography variant="h3">Account details</Typography>
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, sm: 6 }}>
                <Field label="Phone" value={user.phone ?? '—'} />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <Field label="Status" value={user.status.toLowerCase()} />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <Field label="Time zone" value={user.timezone} />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <Field label="Locale" value={user.locale} />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <Field
                  label="Last sign-in"
                  value={
                    user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : 'This session'
                  }
                />
              </Grid>
            </Grid>
          </Stack>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Stack spacing={2}>
            <Typography variant="h3">Your access</Typography>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Roles
              </Typography>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>
                {user.roles.map((role) => (
                  <Chip key={role} label={role.replaceAll('_', ' ')} color="primary" size="small" />
                ))}
              </Stack>
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" fontWeight={600} noWrap>
        {value}
      </Typography>
    </Box>
  );
}

function StatCard({
  icon,
  label,
  value,
  caption,
  tone,
}: {
  icon: ReactNode;
  label: string;
  value: string;
  caption?: string;
  tone?: string;
}) {
  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="flex-start">
          <Box sx={{ color: 'primary.main', '& .MuiSvgIcon-root': { fontSize: 26 }, mt: 0.25 }}>
            {icon}
          </Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary">
              {label}
            </Typography>
            <Typography variant="h4" sx={{ color: tone ?? 'text.primary' }} noWrap>
              {value}
            </Typography>
            {caption ? (
              <Typography variant="caption" color="text.secondary">
                {caption}
              </Typography>
            ) : null}
          </Box>
        </Stack>
      </CardContent>
    </Card>
  );
}
