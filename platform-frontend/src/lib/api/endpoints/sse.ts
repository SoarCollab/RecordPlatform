import { getToken } from "../client";
import { getSseToken } from "./auth";
import { env } from "$env/dynamic/public";

export type SSEEventType =
  | "notification"
  | "message-received"
  | "file-record-success"
  | "file-record-failed"
  | "heartbeat"
  | "announcement-published"
  | "ticket-updated"
  | "badge-update"
  | "friend-request"
  | "friend-accepted"
  | "friend-share";

export interface SSEMessage<T = unknown> {
  type: SSEEventType;
  data: T;
  timestamp: string;
}

export interface SSEConnectionOptions {
  connectionId?: string;
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
  options: SSEConnectionOptions,
): Promise<EventSource | null> {
  // 检查是否已登录
  if (!getToken()) {
    console.warn("SSE: No auth token available");
    return null;
  }

  try {
    // 步骤1：获取短期 SSE Token
    const { sseToken } = await getSseToken();

    // 步骤2：使用短期 Token 建立 SSE 连接
    const params = new URLSearchParams({
      token: sseToken,
      "x-tenant-id": env.PUBLIC_TENANT_ID || "",
    });

    // 如果提供了 connectionId，添加到参数中
    if (options.connectionId) {
      params.set("connectionId", options.connectionId);
    }

    const apiBase = import.meta.env.DEV
      ? "/record-platform/api/v1"
      : `${env.PUBLIC_API_BASE_URL || "/record-platform"}/api/v1`;

    const url = `${apiBase}/sse/connect?${params.toString()}`;
    const eventSource = new EventSource(url);

    eventSource.onopen = () => {
      console.log("SSE: Connection opened");
      options.onOpen?.();
    };

    eventSource.onmessage = (event) => {
      try {
        const message: SSEMessage = JSON.parse(event.data);
        options.onMessage?.(message);
      } catch (err) {
        console.error("SSE: Failed to parse message", err);
      }
    };

    eventSource.onerror = (event) => {
      console.error("SSE: Connection error", event);
      options.onError?.(event);

      // 强制关闭链接，阻止浏览器原生 EventSource 的自动重连机制（因为 url 携带的 token 是一次性的）
      // 这会触发 onClose 回调，进而触发外层的 scheduleReconnect 获取新 token 重连
      eventSource.close();
      options.onClose?.();
    };

    // 监听特定事件类型
    const eventTypes: SSEEventType[] = [
      "notification",
      "message-received",
      "file-record-success",
      "file-record-failed",
      "announcement-published",
      "ticket-updated",
      "badge-update",
      "friend-request",
      "friend-accepted",
      "friend-share",
    ];

    eventTypes.forEach((eventType) => {
      eventSource.addEventListener(eventType, (event) => {
        try {
          const data = JSON.parse((event as MessageEvent).data);
          options.onMessage?.({
            type: eventType,
            data,
            timestamp: new Date().toISOString(),
          });
        } catch (err) {
          console.error(`SSE: Failed to parse ${eventType}`, err);
        }
      });
    });

    return eventSource;
  } catch (err) {
    console.error("SSE: Failed to establish connection", err);
    options.onError?.(new Event("connection-failed"));
    return null;
  }
}

/**
 * 关闭 SSE 连接
 */
export function closeSSEConnection(eventSource: EventSource | null): void {
  if (eventSource) {
    eventSource.close();
    console.log("SSE: Connection closed");
  }
}
