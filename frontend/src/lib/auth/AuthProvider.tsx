import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { bootstrapSession, setSessionExpiredHandler } from '@/lib/api/httpClient';
import { tokenStore } from '@/lib/auth/tokenStore';
import { authApi } from '@/features/auth/api/authApi';
import type { AuthenticationResponse, CurrentUser } from '@/features/auth/types';

interface AuthContextValue {
  user: CurrentUser | null;
  /** True until the silent bootstrap finishes — routes must not redirect before then. */
  initialising: boolean;
  isAuthenticated: boolean;
  applyAuthentication: (response: AuthenticationResponse) => void;
  signOut: (options?: { allDevices?: boolean }) => Promise<void>;
  refreshUser: () => Promise<void>;
  hasPermission: (permission: string) => boolean;
  hasAnyPermission: (permissions: string[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** Refresh this many seconds before the token actually expires. */
const REFRESH_SKEW_SECONDS = 60;

/**
 * Sign out after this long with no user interaction.
 *
 * The refresh token keeps a session alive indefinitely as long as it's used at least once
 * within its window — there is otherwise nothing that ever signs out a device left open and
 * unattended. This is that control.
 */
const IDLE_TIMEOUT_MS = 30 * 60 * 1000;

/** Interaction events that count as "the user is still here". Deliberately not `mousemove` —
 *  too frequent to be a meaningful throttle target, and switching tabs shouldn't count either. */
const ACTIVITY_EVENTS = ['mousedown', 'keydown', 'wheel', 'touchstart', 'scroll'] as const;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [initialising, setInitialising] = useState(true);
  const queryClient = useQueryClient();
  const refreshTimer = useRef<number | null>(null);
  const idleTimer = useRef<number | null>(null);
  const isAuthenticated = Boolean(user);

  const clearSession = useCallback(() => {
    tokenStore.clear();
    setUser(null);
    queryClient.clear();
    if (refreshTimer.current) {
      window.clearTimeout(refreshTimer.current);
      refreshTimer.current = null;
    }
  }, [queryClient]);

  /**
   * Schedules a refresh just before expiry.
   *
   * Waiting for a 401 works, but it costs the user a visibly failed request first — and on
   * a slow link that is a spinner that stalls mid-action. Refreshing pre-emptively means
   * the token is never presented after it has expired.
   */
  const scheduleRefresh = useCallback((expiresAt: number | null) => {
    if (refreshTimer.current) {
      window.clearTimeout(refreshTimer.current);
    }
    if (!expiresAt) return;

    const delay = Math.max(5_000, expiresAt - Date.now() - REFRESH_SKEW_SECONDS * 1000);
    refreshTimer.current = window.setTimeout(() => {
      void bootstrapSession().then((token) => {
        if (token) {
          scheduleRefresh(tokenStore.getExpiresAt());
        } else {
          clearSession();
        }
      });
    }, delay);
  }, [clearSession]);

  const applyAuthentication = useCallback(
    (response: AuthenticationResponse) => {
      if (!response.accessToken) return;
      tokenStore.set(response.accessToken, response.expiresAt, response.expiresIn);
      if (response.user) {
        setUser(response.user);
      }
      scheduleRefresh(tokenStore.getExpiresAt());
    },
    [scheduleRefresh],
  );

  const refreshUser = useCallback(async () => {
    const current = await authApi.me();
    setUser(current);
  }, []);

  const signOut = useCallback(
    async (options?: { allDevices?: boolean }) => {
      try {
        await (options?.allDevices ? authApi.logoutAll() : authApi.logout());
      } catch {
        // Sign-out is best-effort on the wire. If the network is down the local session
        // must still be destroyed — otherwise "sign out" visibly does nothing.
      } finally {
        clearSession();
      }
    },
    [clearSession],
  );

  /*
   * Idle auto-logout.
   *
   * Runs only while authenticated: the timer starts (or restarts) on every qualifying
   * interaction and, if none arrives for IDLE_TIMEOUT_MS, signs the user out. This is what
   * closes the gap a purely sliding refresh-token window leaves open — a device that's
   * signed in and left unattended.
   */
  useEffect(() => {
    if (!isAuthenticated) {
      if (idleTimer.current) {
        window.clearTimeout(idleTimer.current);
        idleTimer.current = null;
      }
      return;
    }

    const resetIdleTimer = () => {
      if (idleTimer.current) window.clearTimeout(idleTimer.current);
      idleTimer.current = window.setTimeout(() => {
        void signOut();
      }, IDLE_TIMEOUT_MS);
    };

    resetIdleTimer();
    ACTIVITY_EVENTS.forEach((event) => window.addEventListener(event, resetIdleTimer, { passive: true }));

    return () => {
      ACTIVITY_EVENTS.forEach((event) => window.removeEventListener(event, resetIdleTimer));
      if (idleTimer.current) {
        window.clearTimeout(idleTimer.current);
        idleTimer.current = null;
      }
    };
  }, [isAuthenticated, signOut]);

  /*
   * Silent bootstrap.
   *
   * The access token lives in memory only, so a reload starts with nothing. The HttpOnly
   * refresh cookie survives, so one call restores the session — which is how the user
   * stays signed in without a readable credential ever being stored.
   */
  useEffect(() => {
    let cancelled = false;

    setSessionExpiredHandler(clearSession);

    void (async () => {
      const token = await bootstrapSession();
      if (cancelled) return;
      if (token) {
        try {
          const current = await authApi.me();
          if (!cancelled) {
            setUser(current);
            scheduleRefresh(tokenStore.getExpiresAt());
          }
        } catch {
          if (!cancelled) clearSession();
        }
      }
      if (!cancelled) setInitialising(false);
    })();

    return () => {
      cancelled = true;
      if (refreshTimer.current) window.clearTimeout(refreshTimer.current);
    };
  }, [clearSession, scheduleRefresh]);

  const permissions = useMemo(() => new Set(user?.permissions ?? []), [user]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initialising,
      isAuthenticated,
      applyAuthentication,
      signOut,
      refreshUser,
      /*
       * Client-side permission checks control what is *rendered*, never what is
       * *allowed*. Every endpoint re-checks server-side; hiding a button the user cannot
       * use is a usability feature, not a security control.
       */
      hasPermission: (permission: string) => permissions.has(permission),
      hasAnyPermission: (required: string[]) => required.some((p) => permissions.has(p)),
    }),
    [user, initialising, isAuthenticated, applyAuthentication, signOut, refreshUser, permissions],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
