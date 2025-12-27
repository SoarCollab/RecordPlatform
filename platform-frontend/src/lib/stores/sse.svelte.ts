import { browser } from "$app/environment";
import { getToken } from "$api/client";
import {
  createSSEConnection,
  closeSSEConnection,
  type SSEMessage,
} from "$api/endpoints/sse";
import { useSSELeader } from "./sse-leader.svelte";

// ===== Types =====

export type SSEStatus = "disconnected" | "connecting" | "connected" | "error";

// ===== Configuration =====

const RECONNECT_BASE_DELAY = 2000; // 2 seconds (increased from 1s)
const RECONNECT_MAX_DELAY = 30000; // 30 seconds
const MAX_RECONNECT_ATTEMPTS = 5; // Reduced from 10

// ===== State =====

let status = $state<SSEStatus>("disconnected");
let lastMessage = $state<SSEMessage | null>(null);
let canManualReconnect = $state(false);

// Non-reactive: internal tracking only
let reconnectAttempts = 0;
let eventSource: EventSource | null = null;
let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
let connectionId = "";
let userId = "";
let isPageVisible = true;
let isInitialized = false;

// ===== Leader Election Integration =====
const leader = useSSELeader();

// ===== Event Handlers =====

type MessageHandler = (message: SSEMessage) => void;
const messageHandlers = new Set<MessageHandler>();

function notifyHandlers(message: SSEMessage): void {
  lastMessage = message;
  messageHandlers.forEach((handler) => handler(message));
}

function updateStatus(newStatus: SSEStatus): void {
  status = newStatus;
  canManualReconnect = newStatus === "error";

  // Broadcast status to followers
  leader.broadcastSSEStatus(newStatus);
}

// ===== Visibility API =====

function setupVisibilityListener(): void {
  if (!browser) return;

  document.addEventListener("visibilitychange", handleVisibilityChange);
  isPageVisible = document.visibilityState === "visible";
}

function cleanupVisibilityListener(): void {
  if (!browser) return;
  document.removeEventListener("visibilitychange", handleVisibilityChange);
}

function handleVisibilityChange(): void {
  const wasVisible = isPageVisible;
  isPageVisible = document.visibilityState === "visible";

  if (!wasVisible && isPageVisible) {
    // Page became visible
    console.log("SSE: Page visible, checking connection");

    if (leader.isLeader && (status === "error" || status === "disconnected")) {
      // Reset retry counter and reconnect immediately
      reconnectAttempts = 0;
      canManualReconnect = false;
      connect();
    }
  } else if (wasVisible && !isPageVisible) {
    // Page became hidden - pause reconnection scheduling (keep existing connection)
    console.log("SSE: Page hidden, pausing reconnection");
    clearReconnectTimeout();
  }
}

// ===== Connection Logic =====

function clearReconnectTimeout(): void {
  if (reconnectTimeout) {
    clearTimeout(reconnectTimeout);
    reconnectTimeout = null;
  }
}

function scheduleReconnect(): void {
  // Don't schedule if page is hidden
  if (!isPageVisible) {
    console.log("SSE: Page hidden, skipping reconnect schedule");
    return;
  }

  if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    console.error("SSE: Max reconnect attempts reached");
    updateStatus("error");
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
  if (!browser) {
    updateStatus("disconnected");
    return;
  }

  // Only leader should connect - check first to avoid unnecessary token operations
  if (!leader.isLeader) {
    console.log("SSE: Not leader, skipping connection");
    return;
  }

  if (!getToken()) {
    updateStatus("disconnected");
    return;
  }

  // Close existing connection
  if (eventSource) {
    closeSSEConnection(eventSource);
    eventSource = null;
  }

  updateStatus("connecting");

  createSSEConnection({
    connectionId,
    onOpen: () => {
      updateStatus("connected");
      reconnectAttempts = 0;
      console.log("SSE: Connected");
    },
    onMessage: (message) => {
      notifyHandlers(message);
      // Broadcast to followers
      leader.broadcastSSEMessage(message);
    },
    onError: () => {
      updateStatus("error");
    },
    onClose: () => {
      updateStatus("disconnected");
      eventSource = null;
      scheduleReconnect();
    },
  }).then((es) => {
    if (es) {
      eventSource = es;
    } else {
      updateStatus("disconnected");
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

  updateStatus("disconnected");
}

function manualReconnect(): void {
  console.log("SSE: Manual reconnect triggered");
  reconnectAttempts = 0;
  canManualReconnect = false;
  connect();
}

// ===== Initialization =====

function init(currentUserId: string): void {
  if (!browser || isInitialized) return;

  userId = currentUserId;
  connectionId = crypto.randomUUID();
  isInitialized = true;

  setupVisibilityListener();

  // Initialize leader election
  leader.init(userId, {
    onBecomeLeader: () => {
      console.log("SSE: Became leader, establishing connection");
      connect();
    },
    onBecomeFollower: () => {
      console.log("SSE: Became follower, closing any existing connection");
      if (eventSource) {
        closeSSEConnection(eventSource);
        eventSource = null;
      }
      clearReconnectTimeout();
      // Status will be synced from leader via BroadcastChannel
    },
    onMessage: (message) => {
      notifyHandlers(message as SSEMessage);
    },
    onStatusChange: (newStatus) => {
      status = newStatus as SSEStatus;
      canManualReconnect = newStatus === "error";
    },
  });
}

function cleanup(): void {
  disconnect();
  leader.cleanup();
  cleanupVisibilityListener();
  isInitialized = false;
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
    get canManualReconnect() {
      return canManualReconnect;
    },
    get isLeader() {
      return leader.isLeader;
    },

    // Actions
    init,
    cleanup,
    connect,
    disconnect,
    manualReconnect,
    subscribe,
  };
}
