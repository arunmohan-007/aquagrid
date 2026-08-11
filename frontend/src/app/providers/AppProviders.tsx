import type { ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { CssBaseline, GlobalStyles } from '@mui/material';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/app/theme/theme';
import { queryClient } from '@/lib/api/queryClient';
import { AuthProvider } from '@/lib/auth/AuthProvider';

/**
 * Provider composition, in dependency order.
 *
 * `AuthProvider` sits inside `QueryClientProvider` because signing out clears the query
 * cache — a signed-out user must not see the previous user's data flash on screen when the
 * next person signs in on a shared control-room workstation.
 *
 * The product is dark-only. `defaultMode="dark"` matches the hard pin in `index.html`, which
 * sets `data-mui-color-scheme="dark"` before first paint — no flash, no scheme storage, no
 * toggle to drift out of sync with.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme} defaultMode="dark" disableTransitionOnChange>
        <CssBaseline enableColorScheme />
        <GlobalStyles
          styles={{
            /* Visually hidden but available to assistive technology. */
            '.sr-only': {
              position: 'absolute',
              width: 1,
              height: 1,
              padding: 0,
              margin: -1,
              overflow: 'hidden',
              clip: 'rect(0, 0, 0, 0)',
              whiteSpace: 'nowrap',
              border: 0,
            },
          }}
        />
        <AuthProvider>{children}</AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
