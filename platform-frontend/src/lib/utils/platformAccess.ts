import { error, redirect } from "@sveltejs/kit";
import { useAuth } from "$stores/auth.svelte";
import type { PlatformCapability, PlatformSession } from "$api/types";

/** Validate the platform identity before a loader or component requests protected data. */
export async function requirePlatformSession(): Promise<PlatformSession> {
  const auth = useAuth();
  await auth.initializeSession();
  if (!auth.isAuthenticated) redirect(303, "/login?mode=platform");
  if (auth.scope !== "platform") redirect(303, "/dashboard");
  const session = auth.platformSession;
  if (!session) error(403, "平台身份尚未通过验证");
  return session;
}

/** Enforce the requested server-provided capability before the page's first request. */
export async function requirePlatformCapability(
  capability: PlatformCapability,
): Promise<PlatformSession> {
  const session = await requirePlatformSession();
  if (!session.capabilities.includes(capability))
    error(403, "当前账号没有此平台管理权限");
  return session;
}
