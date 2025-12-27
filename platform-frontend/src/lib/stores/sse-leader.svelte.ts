import { browser } from "$app/environment";

// ===== Types =====
export type LeaderRole = "init" | "electing" | "leader" | "follower";

type BroadcastMessage =
  | { type: "leader-claim"; tabId: string; timestamp: number }
  | { type: "leader-confirm"; tabId: string }
  | { type: "leader-heartbeat"; tabId: string }
  | { type: "leader-step-down"; tabId: string }
  | { type: "sse-message"; message: unknown }
  | { type: "sse-status"; status: string };

// ===== Configuration =====
const LEADER_HEARTBEAT_INTERVAL = 3000; // 3 seconds
const LEADER_TIMEOUT = 5000; // Consider leader dead after 5s
const ELECTION_TIMEOUT = 150; // Wait 150ms for competing claims

// ===== State =====
let role = $state<LeaderRole>("init");

// Non-reactive internal state
let channel: BroadcastChannel | null = null;
let tabId = "";
let claimTimestamp = 0;
let leaderId: string | null = null;
let leaderLastSeen = 0;
let heartbeatInterval: ReturnType<typeof setInterval> | null = null;
let healthCheckInterval: ReturnType<typeof setInterval> | null = null;
let electionTimeout: ReturnType<typeof setTimeout> | null = null;

// Callbacks
let onBecomeLeader: (() => void) | null = null;
let onBecomeFollower: (() => void) | null = null;
let onMessage: ((message: unknown) => void) | null = null;
let onStatusChange: ((status: string) => void) | null = null;

// ===== Initialization =====
function init(
  userId: string,
  callbacks: {
    onBecomeLeader: () => void;
    onBecomeFollower: () => void;
    onMessage: (message: unknown) => void;
    onStatusChange: (status: string) => void;
  },
) {
  if (!browser) return;

  // Check BroadcastChannel support
  if (typeof BroadcastChannel === "undefined") {
    console.warn("SSE Leader: BroadcastChannel not supported, acting as leader");
    role = "leader";
    callbacks.onBecomeLeader();
    return;
  }

  tabId = crypto.randomUUID();
  onBecomeLeader = callbacks.onBecomeLeader;
  onBecomeFollower = callbacks.onBecomeFollower;
  onMessage = callbacks.onMessage;
  onStatusChange = callbacks.onStatusChange;

  // Use user-specific channel to isolate different users
  const channelName = `sse-leader-${userId}`;
  channel = new BroadcastChannel(channelName);

  channel.onmessage = (event) => handleMessage(event.data);

  // Start election after brief delay (let existing leaders announce themselves)
  setTimeout(() => startElection(), 50);
}

// ===== Message Handling =====
function handleMessage(msg: BroadcastMessage) {
  switch (msg.type) {
    case "leader-claim":
      handleLeaderClaim(msg);
      break;
    case "leader-confirm":
      handleLeaderConfirm(msg);
      break;
    case "leader-heartbeat":
      handleLeaderHeartbeat(msg);
      break;
    case "leader-step-down":
      handleLeaderStepDown(msg);
      break;
    case "sse-message":
      if (role === "follower") {
        onMessage?.(msg.message);
      }
      break;
    case "sse-status":
      if (role === "follower") {
        onStatusChange?.(msg.status);
      }
      break;
  }
}

function handleLeaderClaim(msg: { tabId: string; timestamp: number }) {
  if (msg.tabId === tabId) return; // Ignore own claims

  // Compare priority: lower timestamp wins; if equal, lower tabId wins (tie-breaker)
  const theyHavePriority =
    msg.timestamp < claimTimestamp ||
    (msg.timestamp === claimTimestamp && msg.tabId < tabId);

  if (role === "leader") {
    if (theyHavePriority) {
      // They have priority, step down
      stepDown();
      becomeFollower(msg.tabId);
    } else {
      // I have priority, re-confirm
      broadcast({ type: "leader-confirm", tabId });
    }
  } else if (role === "electing") {
    if (theyHavePriority) {
      // They have priority, become follower
      if (electionTimeout) {
        clearTimeout(electionTimeout);
        electionTimeout = null;
      }
      becomeFollower(msg.tabId);
    }
    // If we have priority, wait for election timeout
  }
}

function handleLeaderConfirm(msg: { tabId: string }) {
  if (msg.tabId !== tabId) {
    if (electionTimeout) {
      clearTimeout(electionTimeout);
      electionTimeout = null;
    }
    becomeFollower(msg.tabId);
  }
}

function handleLeaderHeartbeat(msg: { tabId: string }) {
  if (msg.tabId !== tabId) {
    leaderId = msg.tabId;
    leaderLastSeen = Date.now();
    if (role !== "follower") {
      if (electionTimeout) {
        clearTimeout(electionTimeout);
        electionTimeout = null;
      }
      becomeFollower(msg.tabId);
    }
  }
}

function handleLeaderStepDown(msg: { tabId: string }) {
  if (leaderId === msg.tabId) {
    // Our leader stepped down, start new election
    console.log("SSE Leader: Leader stepped down, starting election");
    startElection();
  }
}

// ===== Election Logic =====
function startElection() {
  role = "electing";
  claimTimestamp = Date.now();

  broadcast({ type: "leader-claim", tabId, timestamp: claimTimestamp });

  // Wait for competing claims
  electionTimeout = setTimeout(() => {
    if (role === "electing") {
      // No one objected, become leader
      becomeLeader();
    }
  }, ELECTION_TIMEOUT);
}

function becomeLeader() {
  role = "leader";
  leaderId = tabId;

  // Stop health check if running
  if (healthCheckInterval) {
    clearInterval(healthCheckInterval);
    healthCheckInterval = null;
  }

  // Start heartbeat
  heartbeatInterval = setInterval(() => {
    broadcast({ type: "leader-heartbeat", tabId });
  }, LEADER_HEARTBEAT_INTERVAL);

  // Announce leadership
  broadcast({ type: "leader-confirm", tabId });

  console.log("SSE Leader: Became leader");
  onBecomeLeader?.();
}

function becomeFollower(newLeaderId: string) {
  const wasLeader = role === "leader";
  role = "follower";
  leaderId = newLeaderId;
  leaderLastSeen = Date.now();

  // Stop heartbeat if was leader
  if (wasLeader && heartbeatInterval) {
    clearInterval(heartbeatInterval);
    heartbeatInterval = null;
  }

  // Start health check
  if (!healthCheckInterval) {
    healthCheckInterval = setInterval(
      checkLeaderHealth,
      LEADER_HEARTBEAT_INTERVAL,
    );
  }

  console.log(`SSE Leader: Became follower of ${newLeaderId.slice(0, 8)}...`);
  onBecomeFollower?.();
}

function stepDown() {
  broadcast({ type: "leader-step-down", tabId });
  if (heartbeatInterval) {
    clearInterval(heartbeatInterval);
    heartbeatInterval = null;
  }
}

function checkLeaderHealth() {
  if (role === "follower" && Date.now() - leaderLastSeen > LEADER_TIMEOUT) {
    console.log("SSE Leader: Leader timeout, starting election");
    startElection();
  }
}

// ===== Broadcasting =====
function broadcast(msg: BroadcastMessage) {
  channel?.postMessage(msg);
}

// For leader to broadcast SSE messages to followers
function broadcastSSEMessage(message: unknown) {
  if (role === "leader") {
    broadcast({ type: "sse-message", message });
  }
}

function broadcastSSEStatus(status: string) {
  if (role === "leader") {
    broadcast({ type: "sse-status", status });
  }
}

// ===== Cleanup =====
function cleanup() {
  if (role === "leader") {
    stepDown();
  }
  if (electionTimeout) {
    clearTimeout(electionTimeout);
    electionTimeout = null;
  }
  if (heartbeatInterval) {
    clearInterval(heartbeatInterval);
    heartbeatInterval = null;
  }
  if (healthCheckInterval) {
    clearInterval(healthCheckInterval);
    healthCheckInterval = null;
  }
  channel?.close();
  channel = null;
  role = "init";
}

// ===== Export =====
export function useSSELeader() {
  return {
    get role() {
      return role;
    },
    get isLeader() {
      return role === "leader";
    },
    get isFollower() {
      return role === "follower";
    },
    init,
    cleanup,
    broadcastSSEMessage,
    broadcastSSEStatus,
  };
}
