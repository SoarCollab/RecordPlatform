import { browser } from "$app/environment";
import type { AuthScope } from "$api/types/auth";

export const TOKEN_KEY = "auth_token";
export const TOKEN_EXPIRE_KEY = "auth_token_expire";
export const REMEMBER_ME_KEY = "auth_remember_me";
export const SCOPE_KEY = "auth_scope";

/** Choose a destination without validating or initializing a protected session. */
export function landingForScope(
  scope: AuthScope | null,
): "/platform" | "/dashboard" {
  return scope === "platform" ? "/platform" : "/dashboard";
}

/** Read unexpired credential metadata without notifying listeners or changing storage. */
function getStoredCredential(): {
  token: string;
  scope: AuthScope | null;
} | null {
  if (!browser) return null;

  const storage = localStorage.getItem(TOKEN_KEY)
    ? localStorage
    : sessionStorage;
  const token = storage.getItem(TOKEN_KEY);
  const expire = storage.getItem(TOKEN_EXPIRE_KEY);
  const expiresAt = expire ? Date.parse(expire) : NaN;
  if (!token || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    return null;
  }

  const scope = storage.getItem(SCOPE_KEY);
  return {
    token,
    scope: scope === "tenant" || scope === "platform" ? scope : null,
  };
}

/** Read an unexpired token safely inside reactive identity getters. */
export function getStoredToken(): string | null {
  return getStoredCredential()?.token ?? null;
}

/** Read an unexpired credential's routing hint without changing browser storage. */
export function getStoredScopeHint(): AuthScope | null {
  return getStoredCredential()?.scope ?? null;
}
