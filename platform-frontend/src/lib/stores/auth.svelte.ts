import { browser } from "$app/environment";
import { goto } from "$app/navigation";
import { getToken } from "$api/client";
import * as authApi from "$api/endpoints/auth";
import type {
  AccountVO,
  LoginRequest,
  RegisterRequest,
  UpdateUserRequest,
} from "$api/types";

// ===== State =====

let user = $state<AccountVO | null>(null);
let isLoading = $state(false);
let error = $state<string | null>(null);
let initialized = $state(false);

// Non-reactive flag to prevent duplicate initialization
let initStarted = false;

// ===== Derived State =====

const isAuthenticated = $derived(!!user && !!getToken());
const isAdmin = $derived(user?.role === "admin");
const username = $derived(user?.username ?? "");
const displayName = $derived(user?.nickname || user?.username || "");

// ===== Actions =====

interface LoginOptions {
  rememberMe?: boolean;
}

async function login(
  credentials: LoginRequest,
  options: LoginOptions = {},
): Promise<void> {
  const { rememberMe = true } = options;
  isLoading = true;
  error = null;

  try {
    const result = await authApi.login(credentials, rememberMe);
    user = {
      id: "",
      username: result.username,
      role: result.role,
      status: 1,
      registerTime: "",
    };
    // Fetch full user info
    await fetchUser();
  } catch (err) {
    error = err instanceof Error ? err.message : "登录失败";
    throw err;
  } finally {
    isLoading = false;
  }
}

async function register(
  data: RegisterRequest,
  options: LoginOptions = {},
): Promise<void> {
  const { rememberMe = true } = options;
  isLoading = true;
  error = null;

  try {
    // Step 1: Register account (backend only creates account, doesn't return token)
    await authApi.register(data);

    // Step 2: Auto-login with credentials to get token
    await login(
      {
        username: data.username,
        password: data.password,
      },
      { rememberMe },
    );
  } catch (err) {
    error = err instanceof Error ? err.message : "注册失败";
    throw err;
  } finally {
    isLoading = false;
  }
}

async function logout(): Promise<void> {
  isLoading = true;

  try {
    await authApi.logout();
  } catch {
    // Ignore logout errors
  } finally {
    user = null;
    isLoading = false;
    if (browser) {
      await goto("/login");
    }
  }
}

async function fetchUser(): Promise<void> {
  if (!getToken()) {
    user = null;
    initialized = true;
    return;
  }

  isLoading = true;
  error = null;

  try {
    user = await authApi.getCurrentUser();
  } catch (err) {
    user = null;
    error = err instanceof Error ? err.message : "获取用户信息失败";
  } finally {
    isLoading = false;
    initialized = true;
  }
}

async function updateProfile(data: UpdateUserRequest): Promise<void> {
  isLoading = true;
  error = null;

  try {
    user = await authApi.updateUser(data);
  } catch (err) {
    error = err instanceof Error ? err.message : "更新失败";
    throw err;
  } finally {
    isLoading = false;
  }
}

function clearError(): void {
  error = null;
}

// Initialize on first load (use non-reactive flag to avoid Svelte 5 warning)
if (browser && !initStarted) {
  initStarted = true;
  fetchUser();
}

// ===== Export Hook =====

export function useAuth() {
  return {
    // State getters
    get user() {
      return user;
    },
    get isLoading() {
      return isLoading;
    },
    get error() {
      return error;
    },
    get isAuthenticated() {
      return isAuthenticated;
    },
    get isAdmin() {
      return isAdmin;
    },
    get username() {
      return username;
    },
    get displayName() {
      return displayName;
    },
    get initialized() {
      return initialized;
    },

    // Actions
    login,
    register,
    logout,
    fetchUser,
    updateProfile,
    clearError,
  };
}
