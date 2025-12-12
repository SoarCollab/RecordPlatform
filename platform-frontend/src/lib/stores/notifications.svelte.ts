// ===== Notification Types =====

export type NotificationType = 'success' | 'error' | 'warning' | 'info';

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

// ===== Actions =====

function generateId(): string {
	return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function add(notification: Omit<Notification, 'id'>): string {
	const id = generateId();
	const duration = notification.duration ?? DEFAULT_DURATION;

	notifications = [...notifications, { ...notification, id }];

	// Auto dismiss
	if (duration > 0) {
		setTimeout(() => {
			dismiss(id);
		}, duration);
	}

	return id;
}

function dismiss(id: string): void {
	notifications = notifications.filter((n) => n.id !== id);
}

function dismissAll(): void {
	notifications = [];
}

// Convenience methods
function success(title: string, message?: string, duration?: number): string {
	return add({ type: 'success', title, message, duration });
}

function error(title: string, message?: string, duration?: number): string {
	return add({ type: 'error', title, message, duration: duration ?? 8000 });
}

function warning(title: string, message?: string, duration?: number): string {
	return add({ type: 'warning', title, message, duration });
}

function info(title: string, message?: string, duration?: number): string {
	return add({ type: 'info', title, message, duration });
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
		info
	};
}
