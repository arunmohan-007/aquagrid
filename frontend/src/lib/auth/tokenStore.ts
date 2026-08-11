/**
 * Holds the access token — in memory, and nowhere else.
 *
 * This is a deliberate security decision, not an oversight.
 *
 * `localStorage` and `sessionStorage` are readable by any JavaScript running on the
 * origin, which means a single XSS payload — in our code or in any dependency in the
 * tree — exfiltrates a valid API credential. A module-scoped variable is not reachable
 * from injected script, and it is destroyed when the tab closes.
 *
 * The cost is that a page reload loses the token. That is paid by the silent bootstrap in
 * `AuthProvider`: on startup the app calls `/auth/refresh`, which authenticates using the
 * `HttpOnly` cookie that JavaScript — ours or an attacker's — cannot read. The user stays
 * signed in across reloads without a readable credential ever existing.
 */

let accessToken: string | null = null;
let expiresAt: number | null = null;

type Listener = (token: string | null) => void;
const listeners = new Set<Listener>();

export const tokenStore = {
  get(): string | null {
    return accessToken;
  },

  /** Absolute expiry in epoch milliseconds, used to schedule a pre-emptive refresh. */
  getExpiresAt(): number | null {
    return expiresAt;
  },

  set(token: string, expiresAtIso?: string, expiresInSeconds?: number): void {
    accessToken = token;
    if (expiresAtIso) {
      expiresAt = Date.parse(expiresAtIso);
    } else if (typeof expiresInSeconds === 'number') {
      expiresAt = Date.now() + expiresInSeconds * 1000;
    } else {
      expiresAt = null;
    }
    listeners.forEach((listener) => listener(accessToken));
  },

  clear(): void {
    accessToken = null;
    expiresAt = null;
    listeners.forEach((listener) => listener(null));
  },

  /** True when the token is absent or within `skewSeconds` of expiry. */
  isExpiring(skewSeconds = 60): boolean {
    if (!accessToken) return true;
    if (!expiresAt) return false;
    return Date.now() >= expiresAt - skewSeconds * 1000;
  },

  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
