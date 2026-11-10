import { redirect } from "@sveltejs/kit";
import { browser } from "$app/environment";
import { getToken } from "$api/client";
import { useAuth } from "$stores/auth.svelte";
import type { LayoutLoad } from "./$types";

/** Validate tenant scope before admitting a credential to the ordinary workspace. */
export const load: LayoutLoad = async () => {
  if (browser) {
    if (!getToken()) redirect(302, "/login");
    const auth = useAuth();
    await auth.initializeSession();
    if (auth.scope === "platform") redirect(303, "/platform");
    if (auth.scope !== "tenant") redirect(303, "/login");
  }
  return {};
};
