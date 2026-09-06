/** Reads an invitation token only from the URL fragment so it never reaches HTTP access logs. */
export function readInvitationTokenFromFragment(url: URL): string {
  const fragment = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
  return new URLSearchParams(fragment).get("token") ?? "";
}
