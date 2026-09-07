import type { PlatformCapability } from "$api/types";

export const platformNavigation: ReadonlyArray<{
  href: string;
  label: string;
  capability: PlatformCapability;
}> = [
  { href: "/platform", label: "总览", capability: "platform:overview:read" },
  {
    href: "/platform/tenants",
    label: "租户",
    capability: "platform:tenant:read",
  },
  { href: "/platform/users", label: "用户", capability: "platform:user:read" },
  {
    href: "/platform/resources",
    label: "配额与资源",
    capability: "platform:resource:read",
  },
  {
    href: "/platform/configuration",
    label: "全局配置",
    capability: "platform:configuration:read",
  },
  {
    href: "/platform/audit",
    label: "操作审计",
    capability: "platform:audit:read",
  },
];

/** Resolve the exact page family without confusing similarly prefixed paths. */
export function platformCapabilityForPath(
  pathname: string,
): PlatformCapability | null {
  const match = [...platformNavigation]
    .reverse()
    .find(
      ({ href }) =>
        pathname === href ||
        (href !== "/platform" && pathname.startsWith(`${href}/`)),
    );
  return match?.capability ?? null;
}
