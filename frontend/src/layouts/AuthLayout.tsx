import { Box, Paper, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { BrandPanel } from '@/features/auth/components/BrandPanel';

interface AuthLayoutProps {
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}

/**
 * Shell for every unauthenticated screen.
 *
 * A two-column split above `md`, collapsing to a single column below it. The brand panel is
 * the part that disappears on mobile — a field technician on a phone gets the form above
 * the fold, because on a 360 px screen a decorative hero is what pushes the password box
 * off-screen.
 *
 * The card is capped at 420 px even on a 4K display: a sign-in form stretched across a wide
 * monitor is measurably slower to complete than one in a comfortable reading measure. It uses
 * frosted glass with an aqua gradient hairline border so it reads as a layered surface on the
 * deep base. (The colour-scheme toggle is gone — the product is dark-only.)
 */
export function AuthLayout({ title, subtitle, children, footer }: AuthLayoutProps) {
  return (
    <Box className="grid min-h-screen grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)] lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
      <BrandPanel />

      <Box
        component="main"
        id="main"
        className="relative flex items-center justify-center px-5 py-10 sm:px-8"
        sx={{ bgcolor: 'background.default' }}
      >
        <Paper
          elevation={0}
          className="ag-glass w-full animate-fade-up"
          sx={{
            maxWidth: 420,
            p: { xs: 3, sm: 4 },
            borderRadius: 3,
            // Accent-tinted elevation: the card lifts off the base with a faint brand glow.
            boxShadow: '0 24px 60px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.06)',
            position: 'relative',
            // Gradient hairline border via a masked pseudo-element.
            '::before': {
              content: '""',
              position: 'absolute',
              inset: 0,
              borderRadius: 3,
              padding: 1,
              background:
                'linear-gradient(135deg, rgba(59,130,246,0.5), rgba(34,211,238,0.25), rgba(45,212,191,0.4))',
              WebkitMask:
                'linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0)',
              WebkitMaskComposite: 'xor',
              maskComposite: 'exclude',
              pointerEvents: 'none',
            },
          }}
        >
          <Stack spacing={3}>
            <Stack spacing={1}>
              {/* One h1 per document, and it names the task. */}
              <Typography variant="h1" component="h1" sx={{ fontSize: '1.65rem' }}>
                {title}
              </Typography>
              {subtitle ? (
                <Typography variant="body2" color="text.secondary">
                  {subtitle}
                </Typography>
              ) : null}
            </Stack>

            {children}
          </Stack>
        </Paper>

        {footer ? (
          <Box className="absolute bottom-5 left-0 right-0 px-6 text-center">{footer}</Box>
        ) : null}
      </Box>
    </Box>
  );
}
