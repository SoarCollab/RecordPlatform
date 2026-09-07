import { browser } from "$app/environment";
import { goto } from "$app/navigation";
import {
  ApiError,
  getToken,
  getCredentialSnapshot,
  isCurrentCredential,
  subscribeCredentialChanges,
  type CredentialSnapshot,
} from "$api/client";
import * as authApi from "$api/endpoints/auth";
import { getPlatformSession } from "$api/endpoints/platform";
import { ResultCode } from "$api/types/common";
import type {
  AccountVO,
  AuthScope,
  LoginRequest,
  RegisterRequest,
  UpdateUserRequest,
  PlatformSession,
  PlatformCapability,
} from "$api/types";

// ===== State =====
let user = $state<AccountVO | null>(null);
let platformSession = $state<PlatformSession | null>(null);
let validatedCredential = $state<CredentialSnapshot | null>(null);
let isLoading = $state(false);
let error = $state<string | null>(null);
let initialized = $state(false);
let loadGeneration = 0;
let actionGeneration = 0;
let changingSession: {
  generation: number;
  snapshot: CredentialSnapshot;
} | null = null;
let pending: { snapshot: CredentialSnapshot; promise: Promise<void> } | null =
  null;

/** Clear validated identity without issuing a protected request. */
function invalidateIdentity(): void {
  loadGeneration += 1;
  pending = null;
  user = null;
  platformSession = null;
  validatedCredential = null;
  initialized = false;
  error = null;
}

// Importing this module subscribes only; public routes never start profile requests.
subscribeCredentialChanges(invalidateIdentity);

/** Check that displayed identity still belongs to the current browser credential. */
function authenticated(): boolean {
  return (
    !!validatedCredential &&
    isCurrentCredential(validatedCredential) &&
    !!(user || platformSession)
  );
}

/** Return only a validated identity scope, never a persisted routing hint. */
function currentScope(): AuthScope | null {
  if (!authenticated()) return null;
  return platformSession ? "platform" : "tenant";
}

/** Preserve the tenant profile while rejecting platform or malformed role responses. */
function validateTenant(profile: AccountVO): AccountVO {
  if (
    !profile ||
    profile.scope !== "tenant" ||
    !["user", "admin", "monitor"].includes(profile.role) ||
    typeof profile.username !== "string" ||
    !profile.username.trim()
  ) {
    throw new ApiError(
      ResultCode.PERMISSION_UNAUTHORIZED,
      "账号身份与工作台不匹配",
    );
  }
  return profile;
}

/** Identify a superseded asynchronous action without displaying a stale failure. */
function superseded(): DOMException {
  return new DOMException("The session has changed", "AbortError");
}

/** Explicitly restore one credential, sharing an in-flight validation with all callers. */
async function initializeSession(): Promise<void> {
  const snapshot = getCredentialSnapshot();
  if (
    changingSession &&
    changingSession.snapshot.token === snapshot.token &&
    changingSession.snapshot.scope === snapshot.scope
  ) {
    // A layout must not restore the credential an explicit login/logout is replacing.
    throw superseded();
  }
  if (!snapshot.token) {
    invalidateIdentity();
    initialized = true;
    isLoading = false;
    return;
  }
  if (authenticated()) return;
  if (pending && isCurrentCredential(pending.snapshot)) return pending.promise;
  const generation = ++loadGeneration;
  isLoading = true;
  error = null;
  const promise = (async () => {
    try {
      if (snapshot.scope === "platform") {
        const session = await getPlatformSession();
        if (generation !== loadGeneration || !isCurrentCredential(snapshot))
          throw superseded();
        platformSession = session;
        user = null;
      } else {
        const profile = await authApi.getCurrentUser();
        if (generation !== loadGeneration || !isCurrentCredential(snapshot))
          throw superseded();
        user = validateTenant(profile);
        platformSession = null;
      }
      validatedCredential = snapshot;
      initialized = true;
    } catch (failure) {
      if (generation === loadGeneration && isCurrentCredential(snapshot)) {
        user = null;
        platformSession = null;
        validatedCredential = null;
        error = failure instanceof Error ? failure.message : "获取账号信息失败";
        initialized = true;
      }
      throw failure;
    } finally {
      if (generation === loadGeneration) {
        pending = null;
        isLoading = false;
      }
    }
  })();
  pending = { snapshot, promise };
  return promise;
}

interface LoginOptions {
  rememberMe?: boolean;
  mode?: AuthScope;
}

/** Authenticate in one named scope, then validate its authoritative session representation. */
async function login(
  credentials: LoginRequest,
  options: LoginOptions = {},
): Promise<void> {
  const generation = ++actionGeneration;
  let credential = getCredentialSnapshot();
  changingSession = { generation, snapshot: credential };
  isLoading = true;
  error = null;
  try {
    const request = authApi.login(
      credentials,
      options.rememberMe ?? true,
      options.mode ?? "tenant",
    );
    // The endpoint synchronously invalidates the old generation before awaiting transport.
    credential = getCredentialSnapshot();
    const result = await request;
    if (generation !== actionGeneration) throw superseded();
    if (result.token !== getToken()) throw superseded();
    credential = getCredentialSnapshot();
    changingSession = null;
    await initializeSession();
    if (generation !== actionGeneration) throw superseded();
  } catch (failure) {
    if (
      generation === actionGeneration &&
      isCurrentCredential(credential) &&
      !(failure instanceof DOMException && failure.name === "AbortError")
    ) {
      error = failure instanceof Error ? failure.message : "登录失败";
    }
    throw failure;
  } finally {
    if (changingSession?.generation === generation) changingSession = null;
    if (generation === actionGeneration && !pending) isLoading = false;
  }
}

/** Register an ordinary tenant account without replacing a newer concurrent session. */
async function register(
  data: RegisterRequest,
  options: LoginOptions = {},
): Promise<void> {
  const generation = ++actionGeneration;
  const snapshot = getCredentialSnapshot();
  isLoading = true;
  error = null;
  try {
    await authApi.register(data);
    if (generation !== actionGeneration || !isCurrentCredential(snapshot))
      throw superseded();
    await login(
      { username: data.username, password: data.password },
      { rememberMe: options.rememberMe ?? true, mode: "tenant" },
    );
  } catch (failure) {
    if (
      generation === actionGeneration &&
      isCurrentCredential(snapshot) &&
      !(failure instanceof DOMException && failure.name === "AbortError")
    )
      error = failure instanceof Error ? failure.message : "注册失败";
    throw failure;
  } finally {
    if (generation === actionGeneration) isLoading = false;
  }
}

/** End only the selected session, retaining a newer identity that wins an async race. */
async function logout(): Promise<void> {
  const generation = ++actionGeneration;
  changingSession = { generation, snapshot: getCredentialSnapshot() };
  isLoading = true;
  try {
    await authApi.logout();
  } catch {
    // The endpoint still clears its own credential after a failed server logout.
    console.error("Logout request failed");
  } finally {
    if (changingSession?.generation === generation) changingSession = null;
    if (generation === actionGeneration && !getToken()) {
      invalidateIdentity();
      initialized = true;
      try {
        const [{ useDownload }, { useBadges }] = await Promise.all([
          import("$stores/download.svelte"),
          import("$stores/badges.svelte"),
        ]);
        // Local cache cleanup also covers public-route logout before profile initialization.
        if (generation === actionGeneration && !getToken())
          await useDownload().clearAllDownloads();
        if (generation === actionGeneration && !getToken()) useBadges().reset();
      } catch {
        console.error("Session cache cleanup failed");
      }
      if (generation === actionGeneration && !getToken() && browser)
        await goto("/login");
    }
    if (generation === actionGeneration) isLoading = false;
  }
}

/** Refresh the tenant-compatible profile entry point while retaining its legacy error state. */
async function fetchUser(): Promise<void> {
  invalidateIdentity();
  try {
    await initializeSession();
  } catch {
    /* Error state is maintained by initialization. */
  }
}

/** Update only a validated tenant profile and ignore results from a replaced credential. */
async function updateProfile(data: UpdateUserRequest): Promise<void> {
  if (currentScope() !== "tenant")
    throw new ApiError(
      ResultCode.PERMISSION_UNAUTHORIZED,
      "此身份不能修改租户资料",
    );
  const generation = ++actionGeneration;
  const snapshot = getCredentialSnapshot();
  isLoading = true;
  error = null;
  try {
    const profile = await authApi.updateUser(data);
    if (generation !== actionGeneration || !isCurrentCredential(snapshot))
      throw superseded();
    user = validateTenant(profile);
  } catch (failure) {
    if (generation === actionGeneration && isCurrentCredential(snapshot))
      error = failure instanceof Error ? failure.message : "更新失败";
    throw failure;
  } finally {
    if (generation === actionGeneration) isLoading = false;
  }
}

/** Dismiss the current bounded authentication error. */
function clearError(): void {
  error = null;
}

/** Expose reactive identity getters without starting authentication during import. */
export function useAuth() {
  return {
    get user() {
      return currentScope() === "tenant" ? user : null;
    },
    get platformSession() {
      return currentScope() === "platform" ? platformSession : null;
    },
    get scope() {
      return currentScope();
    },
    get isPlatformAdmin() {
      return currentScope() === "platform";
    },
    hasPlatformCapability(capability: PlatformCapability) {
      return (
        currentScope() === "platform" &&
        !!platformSession?.capabilities.includes(capability)
      );
    },
    get isLoading() {
      return isLoading;
    },
    get error() {
      return error;
    },
    get isAuthenticated() {
      return authenticated();
    },
    get isAdmin() {
      return currentScope() === "tenant" && user?.role === "admin";
    },
    get isMonitor() {
      return currentScope() === "tenant" && user?.role === "monitor";
    },
    get isAdminOrMonitor() {
      return (
        currentScope() === "tenant" &&
        (user?.role === "admin" || user?.role === "monitor")
      );
    },
    get username() {
      return authenticated()
        ? (platformSession?.username ?? user?.username ?? "")
        : "";
    },
    get displayName() {
      return authenticated()
        ? (platformSession?.username ??
            (user?.nickname || user?.username || ""))
        : "";
    },
    get initialized() {
      return initialized;
    },
    login,
    register,
    logout,
    fetchUser,
    updateProfile,
    clearError,
    initializeSession,
  };
}
