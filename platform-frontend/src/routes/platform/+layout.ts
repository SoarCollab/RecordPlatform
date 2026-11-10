import { browser } from "$app/environment";
import {
  requirePlatformCapability,
  requirePlatformSession,
} from "$utils/platformAccess";
import { platformCapabilityForPath } from "$lib/config/platformNavigation";
import type { LayoutLoad } from "./$types";

/** Guard every platform navigation before the independent shell renders. */
export const load: LayoutLoad = async ({ url }) => {
  if (browser) {
    const capability = platformCapabilityForPath(url.pathname);
    if (capability) await requirePlatformCapability(capability);
    else await requirePlatformSession();
  }
  return {};
};
