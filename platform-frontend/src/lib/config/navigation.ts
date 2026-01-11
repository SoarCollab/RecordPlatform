import type { AppIconName } from "$components/ui/appIcon";

export type BadgeKey =
  | "messages"
  | "announcements"
  | "tickets"
  | "friends"
  | null;

export type MenuItem = {
  href: string;
  icon: AppIconName;
  label: string;
  badgeKey: BadgeKey;
};

export type AdminItem = {
  href: string;
  icon: AppIconName;
  label: string;
};

export const menuItems: MenuItem[] = [
  { href: "/dashboard", icon: "home", label: "仪表盘", badgeKey: null },
  { href: "/files", icon: "folder", label: "文件管理", badgeKey: null },
  { href: "/shares", icon: "share", label: "分享管理", badgeKey: null },
  { href: "/upload", icon: "upload", label: "上传文件", badgeKey: null },
  { href: "/friends", icon: "users", label: "好友", badgeKey: "friends" },
  {
    href: "/messages",
    icon: "message",
    label: "消息中心",
    badgeKey: "messages",
  },
  {
    href: "/announcements",
    icon: "megaphone",
    label: "系统公告",
    badgeKey: "announcements",
  },
  { href: "/tickets", icon: "ticket", label: "工单系统", badgeKey: "tickets" },
  { href: "/settings", icon: "settings", label: "个人设置", badgeKey: null },
];

export const adminItems: AdminItem[] = [
  { href: "/admin/files", icon: "file-search", label: "文件审计" },
  { href: "/admin/tickets", icon: "ticket", label: "工单管理" },
  { href: "/admin/monitor", icon: "activity", label: "系统监控" },
  { href: "/admin/audit", icon: "shield", label: "审计日志" },
  { href: "/admin/permissions", icon: "users", label: "权限管理" },
];
