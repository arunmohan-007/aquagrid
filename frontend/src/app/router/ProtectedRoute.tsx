import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { Box, CircularProgress, Typography } from '@mui/material';
import { useAuth } from '@/lib/auth/AuthProvider';

interface ProtectedRouteProps {
  /** Permission codes; the route renders if the user holds any of them. */
  anyOf?: string[];
}

/**
 * Route guard.
 *
 * <p>Three behaviours matter here:
 *
 * 1. **It waits for the silent bootstrap.** On a page reload the access token is gone (it
 *    lives in memory by design) and is being restored from the refresh cookie. Redirecting
 *    during that window would bounce every signed-in user to the login screen on every
 *    refresh — the most common bug in this pattern.
 * 2. **It does not remember where the user was going.** Sign-in always lands on the module
 *    launcher, by product decision: an operator opening the platform picks their task from
 *    the board rather than being dropped back into whichever screen their session happened
 *    to expire on.
 * 3. **A forced password change outranks everything else.** An account still holding an
 *    administrator-chosen credential does not get to browse the product first.
 *
 * This guard controls rendering, not authorisation. Every endpoint re-checks server-side.
 */
export function ProtectedRoute({ anyOf }: ProtectedRouteProps) {
  const { isAuthenticated, initialising, user, hasAnyPermission } = useAuth();
  const location = useLocation();

  if (initialising) {
    return (
      <Box className="flex h-screen flex-col items-center justify-center gap-4">
        <CircularProgress size={28} />
        <Typography variant="body2" color="text.secondary">
          Restoring your session&hellip;
        </Typography>
      </Box>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }

  if (anyOf?.length && !hasAnyPermission(anyOf)) {
    return <Navigate to="/forbidden" replace />;
  }

  return <Outlet />;
}
