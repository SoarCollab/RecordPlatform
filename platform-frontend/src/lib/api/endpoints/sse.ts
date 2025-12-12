import { getToken } from '../client';
import { getSseToken } from './auth';

export type SSEEventType =
	| 'notification'
	| 'message-received'
	| 'file-processed'
	| 'heartbeat'
	| 'announcement-published'
	| 'ticket-updated'
	| 'badge-update';

export interface SSEMessage<T = unknown> {
	type: SSEEventType;
	data: T;
	timestamp: string;
}

export interface SSEConnectionOptions {
	onMessage?: (event: SSEMessage) => void;
	onError?: (error: Event) => void;
	onOpen?: () => void;
	onClose?: () => void;
}

/**
 * 创建 SSE 连接（使用短期令牌握手）
 *
 * 流程：
 * 1. 使用主 JWT 获取短期 SSE Token（30秒有效，一次性）
 * 2. 使用短期 Token 建立 SSE 连接
 *
 * 优点：主 JWT 永不暴露在 URL 中，安全性更高
 */
export async function createSSEConnection(
	options: SSEConnectionOptions
): Promise<EventSource | null> {
	// 检查是否已登录
	if (!getToken()) {
		console.warn('SSE: No auth token available');
		return null;
	}

	try {
		// 步骤1：获取短期 SSE Token
		const { sseToken } = await getSseToken();

		// 步骤2：使用短期 Token 建立 SSE 连接
		const url = `/record-platform/api/v1/sse/connect?token=${encodeURIComponent(sseToken)}`;
		const eventSource = new EventSource(url);

		eventSource.onopen = () => {
			console.log('SSE: Connection opened');
			options.onOpen?.();
		};

		eventSource.onmessage = (event) => {
			try {
				const message: SSEMessage = JSON.parse(event.data);
				options.onMessage?.(message);
			} catch (err) {
				console.error('SSE: Failed to parse message', err);
			}
		};

		eventSource.onerror = (event) => {
			console.error('SSE: Connection error', event);
			options.onError?.(event);

			// EventSource 会自动重连，但如果连接关闭需要通知上层
			if (eventSource.readyState === EventSource.CLOSED) {
				options.onClose?.();
			}
		};

		// 监听特定事件类型
		const eventTypes: SSEEventType[] = [
			'notification',
			'message-received',
			'file-processed',
			'announcement-published',
			'ticket-updated',
			'badge-update'
		];

		eventTypes.forEach((eventType) => {
			eventSource.addEventListener(eventType, (event) => {
				try {
					const data = JSON.parse((event as MessageEvent).data);
					options.onMessage?.({ type: eventType, data, timestamp: new Date().toISOString() });
				} catch (err) {
					console.error(`SSE: Failed to parse ${eventType}`, err);
				}
			});
		});

		return eventSource;
	} catch (err) {
		console.error('SSE: Failed to establish connection', err);
		options.onError?.(new Event('connection-failed'));
		return null;
	}
}

/**
 * 关闭 SSE 连接
 */
export function closeSSEConnection(eventSource: EventSource | null): void {
	if (eventSource) {
		eventSource.close();
		console.log('SSE: Connection closed');
	}
}
