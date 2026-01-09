import type { PageLoad } from "./$types";

export const load: PageLoad = ({ url }) => {
  return {
    receiverId: url.searchParams.get("to") || "",
  };
};
