import { useEffect, useRef, useState } from 'react';
import { Stack, TextField } from '@mui/material';

interface OtpInputProps {
  length?: number;
  value: string;
  onChange: (value: string) => void;
  onComplete?: (value: string) => void;
  disabled?: boolean;
  autoFocus?: boolean;
  'aria-label'?: string;
}

/**
 * Six single-character boxes for a TOTP code.
 *
 * The details here are what make the difference between a smooth second factor and an
 * irritating one:
 *
 * - **Paste of the whole code works** in any box. Users copy the code from their
 *   authenticator; a widget that accepts only one character per paste is broken in the
 *   most common real interaction.
 * - **Auto-advance and backspace-to-previous**, so the code can be typed without looking.
 * - **`inputMode="numeric"` and `autoComplete="one-time-code"`**, which makes iOS and
 *   Android offer the code from the SMS/authenticator directly above the keyboard.
 * - **Auto-submit on completion** — there is nothing to review, so an extra click is
 *   friction with no purpose.
 */
export function OtpInput({
  length = 6,
  value,
  onChange,
  onComplete,
  disabled = false,
  autoFocus = false,
  'aria-label': ariaLabel = 'Verification code',
}: OtpInputProps) {
  const inputs = useRef<Array<HTMLInputElement | null>>([]);
  const [completedFor, setCompletedFor] = useState<string | null>(null);

  useEffect(() => {
    if (value.length === length && value !== completedFor) {
      setCompletedFor(value);
      onComplete?.(value);
    }
    if (value.length < length && completedFor) {
      setCompletedFor(null);
    }
  }, [value, length, onComplete, completedFor]);

  const setCharacter = (index: number, character: string) => {
    const digits = value.padEnd(length, ' ').split('');
    digits[index] = character;
    onChange(digits.join('').replace(/\s+$/, '').trimEnd());
  };

  const handleChange = (index: number, raw: string) => {
    const digits = raw.replace(/\D/g, '');
    if (!digits) {
      setCharacter(index, '');
      return;
    }
    if (digits.length > 1) {
      // A full code was pasted or typed quickly: distribute it from this box onward.
      const next = (value.slice(0, index) + digits).slice(0, length);
      onChange(next);
      inputs.current[Math.min(next.length, length - 1)]?.focus();
      return;
    }
    const next = (value.slice(0, index) + digits + value.slice(index + 1)).slice(0, length);
    onChange(next);
    if (index < length - 1) {
      inputs.current[index + 1]?.focus();
    }
  };

  const handleKeyDown = (index: number, event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Backspace' && !value[index] && index > 0) {
      inputs.current[index - 1]?.focus();
    }
    if (event.key === 'ArrowLeft' && index > 0) {
      event.preventDefault();
      inputs.current[index - 1]?.focus();
    }
    if (event.key === 'ArrowRight' && index < length - 1) {
      event.preventDefault();
      inputs.current[index + 1]?.focus();
    }
  };

  return (
    <Stack direction="row" spacing={1} role="group" aria-label={ariaLabel}>
      {Array.from({ length }).map((_, index) => (
        <TextField
          // eslint-disable-next-line react/no-array-index-key -- positional by definition
          key={index}
          value={value[index] ?? ''}
          disabled={disabled}
          autoFocus={autoFocus && index === 0}
          onChange={(event) => handleChange(index, event.target.value)}
          onKeyDown={(event) => handleKeyDown(index, event)}
          onFocus={(event) => event.target.select()}
          inputRef={(element: HTMLInputElement | null) => {
            inputs.current[index] = element;
          }}
          slotProps={{
            htmlInput: {
              inputMode: 'numeric',
              pattern: '[0-9]*',
              maxLength: length,
              autoComplete: index === 0 ? 'one-time-code' : 'off',
              'aria-label': `Digit ${index + 1} of ${length}`,
              className: 'text-center text-xl font-semibold',
              style: { padding: '14px 0' },
            },
          }}
          sx={{ width: 52 }}
        />
      ))}
    </Stack>
  );
}
