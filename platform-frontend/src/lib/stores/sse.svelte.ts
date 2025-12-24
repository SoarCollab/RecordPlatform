import { browser } from "$app/environment";
import { getToken } from "$api/client";
import {
  createSSEConnection,
  closeSSEConnection,
  type SSEMessage,
} from "$api/endpoints/sse";

// ===== Types =====

export type SSEStatus = "disconnected" | "connecting" | "connected" | "error";

// ===== Configuration =====

const RECONNECT_BASE_DELAY = 1000; // 1 second
const RECONNECT_MAX_DELAY = 30000; // 30 seconds
const MAX_RECONNECT_ATTEMPTS = 10;

// ===== State =====

let status = $state<SSEStatus>("disconnected");
let lastMessage = $state<SSEMessage | null>(null);

// Non-reactive: internal tracking only, not displayed in UI
let reconnectAttempts = 0;
// Non-reactive: EventSource is a complex object that shouldn't be in $state
let eventSource: EventSource | null = null;

// ===== Event Handlers =====

type MessageHandler = (message: SSEMessage) => void;
const messageHandlers = new Set<MessageHandler>();

function notifyHandlers(message: SSEMessage): void {
  lastMessage = message;
  messageHandlers.forEach((handler) => handler(message));
}

// ===== Connection Logic =====

let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;

function clearReconnectTimeout(): void {
  if (reconnectTimeout) {
    clearTimeout(reconnectTimeout);
    reconnectTimeout = null;
  }
}

function scheduleReconnect(): void {
  if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    console.error("SSE: Max reconnect attempts reached");
    status = "error";
    return;
  }

  const delay = Math.min(
    RECONNECT_BASE_DELAY * Math.pow(2, reconnectAttempts),
    RECONNECT_MAX_DELAY,
  );

  console.log(
    `SSE: Reconnecting in ${delay}ms (attempt ${reconnectAttempts + 1})`,
  );

  reconnectTimeout = setTimeout(() => {
    reconnectAttempts++;
    connect();
  }, delay);
}

function connect(): void {
  if (!browser || !getToken()) {
    status = "disconnected";
    return;
  }

  // Close existing connection
  if (eventSource) {
    closeSSEConnection(eventSource);
    eventSource = null;
  }

  status = "connecting";

  // 异步创建 SSE 连接（使用短期令牌握手）
  createSSEConnection({
    onOpen: () => {
      status = "connected";
      reconnectAttempts = 0;
      console.log("SSE: Connected");
    },
    onMessage: (message) => {
      notifyHandlers(message);
    },
    onError: () => {
      status = "error";
    },
    onClose: () => {
      status = "disconnected";
      eventSource = null;
      scheduleReconnect();
    },
  }).then((es) => {
    if (es) {
      eventSource = es;
    } else {
      status = "disconnected";
    }
  });
}

function disconnect(): void {
  clearReconnectTimeout();
  reconnectAttempts = 0;

  if (eventSource) {
    closeSSEConnection(eventSource);
    eventSource = null;
  }

  status = "disconnected";
}

// ===== Subscription =====

function subscribe(handler: MessageHandler): () => void {
  messageHandlers.add(handler);
  return () => {
    messageHandlers.delete(handler);
  };
}

// ===== Export Hook =====

export function useSSE() {
  return {
    // State
    get status() {
      return status;
    },
    get isConnected() {
      return status === "connected";
    },
    get lastMessage() {
      return lastMessage;
    },

    // Actions
    connect,
    disconnect,
    subscribe,
  };
}
