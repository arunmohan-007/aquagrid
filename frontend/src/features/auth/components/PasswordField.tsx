import { forwardRef, useId, useState } from 'react';
import { IconButton, InputAdornment, TextField, Typography, type TextFieldProps } from '@mui/material';
import VisibilityIcon from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOffOutlined';
import KeyboardCapslockIcon from '@mui/icons-material/KeyboardCapslock';

type PasswordFieldProps = Omit<TextFieldProps, 'type'>;

/**
 * A password input with the affordances that measurably reduce failed sign-ins.
 *
 * - **Show/hide.** Typing a 16-character passphrase blind on a phone keyboard is the
 *   single largest source of avoidable login failures.
 * - **Caps Lock warning.** The classic invisible cause of "my password stopped working",
 *   and the reason a support call gets raised instead of a retry.
 * - **Correct `autocomplete` tokens** (passed through by the caller) so password managers
 *   fill and save reliably. A field managers cannot use pushes users toward weak,
 *   memorable passwords.
 *
 * `forwardRef` is required for React Hook Form's uncontrolled registration.
 */
export const PasswordField = forwardRef<HTMLInputElement, PasswordFieldProps>(
  function PasswordField({ helperText, InputProps, ...props }, ref) {
    const [visible, setVisible] = useState(false);
    const [capsLock, setCapsLock] = useState(false);
    const capsHintId = useId();

    return (
      <TextField
        {...props}
        inputRef={ref}
        type={visible ? 'text' : 'password'}
        onKeyUp={(event) => setCapsLock(event.getModifierState?.('CapsLock') ?? false)}
        onKeyDown={(event) => setCapsLock(event.getModifierState?.('CapsLock') ?? false)}
        onBlur={(event) => {
          setCapsLock(false);
          props.onBlur?.(event);
        }}
        helperText={
          capsLock ? (
            <Typography
              component="span"
              variant="caption"
              id={capsHintId}
              role="status"
              className="flex items-center gap-1"
              color="warning.main"
            >
              <KeyboardCapslockIcon fontSize="inherit" /> Caps Lock is on
            </Typography>
          ) : (
            helperText
          )
        }
        InputProps={{
          ...InputProps,
          endAdornment: (
            <InputAdornment position="end">
              <IconButton
                onClick={() => setVisible((current) => !current)}
                edge="end"
                size="small"
                /* Announced to screen readers; the icon alone conveys nothing to them. */
                aria-label={visible ? 'Hide password' : 'Show password'}
                aria-pressed={visible}
                tabIndex={-1}
              >
                {visible ? <VisibilityOffIcon fontSize="small" /> : <VisibilityIcon fontSize="small" />}
              </IconButton>
            </InputAdornment>
          ),
        }}
      />
    );
  },
);
