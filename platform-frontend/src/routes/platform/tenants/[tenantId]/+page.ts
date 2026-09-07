import type { PageLoad } from "./$types";

/** Await the guarded platform parent before exposing the explicit resource parameter. */
export const load: PageLoad = async ({ params, parent }) => {
  await parent();
  return { tenantId: params.tenantId };
};
