import { Stack, Typography } from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircleOutlined';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import type { PasswordPolicy } from '../types';

interface PasswordRequirementsProps {
  policy: PasswordPolicy | undefined;
  value: string;
}

/**
 * A live checklist of the server's password policy.
 *
 * The rules come from `GET /auth/password/policy` rather than being duplicated in the
 * client, so a tenant-specific policy in Module 3 changes this list without a frontend
 * release — and the client can never disagree with the server about what is acceptable.
 *
 * Shown as satisfied/unsatisfied rather than as a strength "score": a percentage bar tells
 * the user they are failing but not what to change, and it is the single most common
 * reason people give up on a password form.
 */
export function PasswordRequirements({ policy, value }: PasswordRequirementsProps) {
  if (!policy) return null;

  const checks: Array<{ label: string; met: boolean }> = [
    { label: `At least ${policy.minLength} characters`, met: value.length >= policy.minLength },
  ];
  if (policy.requireUppercase) {
    checks.push({ label: 'An uppercase letter', met: /[A-Z]/.test(value) });
  }
  if (policy.requireLowercase) {
    checks.push({ label: 'A lowercase letter', met: /[a-z]/.test(value) });
  }
  if (policy.requireDigit) {
    checks.push({ label: 'A number', met: /\d/.test(value) });
  }
  if (policy.requireSpecial) {
    checks.push({ label: 'A special character', met: /[^A-Za-z0-9]/.test(value) });
  }

  return (
    <Stack
      spacing={0.5}
      component="ul"
      sx={{ listStyle: 'none', p: 0, m: 0 }}
      /* Polite, not assertive: this updates on every keystroke and must not interrupt. */
      aria-live="polite"
    >
      {checks.map((check) => (
        <Stack
          key={check.label}
          component="li"
          direction="row"
          spacing={1}
          alignItems="center"
          sx={{ color: check.met ? 'success.main' : 'text.secondary' }}
        >
          {check.met ? (
            <CheckCircleIcon fontSize="small" />
          ) : (
            <RadioButtonUncheckedIcon fontSize="small" />
          )}
          <Typography variant="caption">
            {check.label}
            <span className="sr-only">{check.met ? ' — met' : ' — not yet met'}</span>
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}
