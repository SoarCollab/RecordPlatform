// ===== Notification Types =====

export type NotificationType = "success" | "error" | "warning" | "info";

export interface Notification {
  id: string;
  type: NotificationType;
  title: string;
  message?: string;
  duration?: number; // ms, 0 for persistent
}

// ===== State =====

let notifications = $state<Notification[]>([]);
const DEFAULT_DURATION = 5000;
const timeoutMap = new Map<string, ReturnType<typeof setTimeout>>();

// ===== Actions =====

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function add(notification: Omit<Notification, "id">): string {
  const id = generateId();
  const duration = notification.duration ?? DEFAULT_DURATION;

  notifications = [...notifications, { ...notification, id }];

  if (duration > 0) {
    const timer = setTimeout(() => {
      timeoutMap.delete(id);
      dismiss(id);
    }, duration);
    timeoutMap.set(id, timer);
  }

  return id;
}

function dismiss(id: string): void {
  const timer = timeoutMap.get(id);
  if (timer) {
    clearTimeout(timer);
    timeoutMap.delete(id);
  }
  notifications = notifications.filter((n) => n.id !== id);
}

function dismissAll(): void {
  for (const timer of timeoutMap.values()) {
    clearTimeout(timer);
  }
  timeoutMap.clear();
  notifications = [];
}

// Convenience methods
function success(title: string, message?: string, duration?: number): string {
  return add({ type: "success", title, message, duration });
}

function error(title: string, message?: string, duration?: number): string {
  return add({ type: "error", title, message, duration: duration ?? 8000 });
}

function warning(title: string, message?: string, duration?: number): string {
  return add({ type: "warning", title, message, duration });
}

function info(title: string, message?: string, duration?: number): string {
  return add({ type: "info", title, message, duration });
}

// ===== Export Hook =====

export function useNotifications() {
  return {
    get notifications() {
      return notifications;
    },
    add,
    dismiss,
    dismissAll,
    success,
    error,
    warning,
    info,
  };
}
