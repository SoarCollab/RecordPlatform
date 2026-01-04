import { browser } from "$app/environment";
import {
  getUnreadConversationCount,
  getUnreadAnnouncementCount,
} from "$api/endpoints/messages";
import { getPendingCount } from "$api/endpoints/tickets";
import {
  getPendingRequestCount,
  getUnreadFriendShareCount,
} from "$api/endpoints/friends";

// ===== State =====

let unreadMessages = $state(0);
let unreadAnnouncements = $state(0);
let pendingTickets = $state(0);
let pendingFriendRequests = $state(0);
let unreadFriendShares = $state(0);
let isLoading = $state(false);
let lastFetched = $state<Date | null>(null);

// Refresh interval (5 minutes)
const REFRESH_INTERVAL = 5 * 60 * 1000;

// ===== Actions =====

async function fetchBadgeCounts() {
  if (!browser) return;

  isLoading = true;

  try {
    const [
      messagesResult,
      announcementsResult,
      ticketsResult,
      friendRequestsResult,
      friendSharesResult,
    ] = await Promise.allSettled([
      getUnreadConversationCount(),
      getUnreadAnnouncementCount(),
      getPendingCount(),
      getPendingRequestCount(),
      getUnreadFriendShareCount(),
    ]);

    if (messagesResult.status === "fulfilled") {
      unreadMessages = messagesResult.value.count;
    }

    if (announcementsResult.status === "fulfilled") {
      unreadAnnouncements = announcementsResult.value.count;
    }

    if (ticketsResult.status === "fulfilled") {
      pendingTickets = ticketsResult.value.count;
    }

    if (friendRequestsResult.status === "fulfilled") {
      pendingFriendRequests = friendRequestsResult.value.count;
    }

    if (friendSharesResult.status === "fulfilled") {
      unreadFriendShares = friendSharesResult.value.count;
    }

    lastFetched = new Date();
  } catch {
    // Silently fail - badges are not critical
  } finally {
    isLoading = false;
  }
}

function updateMessageCount(count: number) {
  unreadMessages = count;
}

function updateAnnouncementCount(count: number) {
  unreadAnnouncements = count;
}

function updateTicketCount(count: number) {
  pendingTickets = count;
}

function updateFriendRequestCount(count: number) {
  pendingFriendRequests = count;
}

function updateFriendShareCount(count: number) {
  unreadFriendShares = count;
}

function decrementMessages() {
  if (unreadMessages > 0) {
    unreadMessages--;
  }
}

function decrementAnnouncements() {
  if (unreadAnnouncements > 0) {
    unreadAnnouncements--;
  }
}

function reset() {
  unreadMessages = 0;
  unreadAnnouncements = 0;
  pendingTickets = 0;
  pendingFriendRequests = 0;
  unreadFriendShares = 0;
  lastFetched = null;
}

// Auto-refresh setup
let refreshInterval: ReturnType<typeof setInterval> | null = null;

function startAutoRefresh() {
  if (!browser || refreshInterval) return;

  // Initial fetch
  fetchBadgeCounts();

  // Set up interval
  refreshInterval = setInterval(fetchBadgeCounts, REFRESH_INTERVAL);
}

function stopAutoRefresh() {
  if (refreshInterval) {
    clearInterval(refreshInterval);
    refreshInterval = null;
  }
}

// ===== Export Hook =====

export function useBadges() {
  return {
    // State getters
    get unreadMessages() {
      return unreadMessages;
    },
    get unreadAnnouncements() {
      return unreadAnnouncements;
    },
    get pendingTickets() {
      return pendingTickets;
    },
    get pendingFriendRequests() {
      return pendingFriendRequests;
    },
    get unreadFriendShares() {
      return unreadFriendShares;
    },
    get friendBadgeTotal() {
      return pendingFriendRequests + unreadFriendShares;
    },
    get isLoading() {
      return isLoading;
    },
    get lastFetched() {
      return lastFetched;
    },
    // Computed
    get totalUnread() {
      return unreadMessages + unreadAnnouncements;
    },
    get hasUnread() {
      return (
        unreadMessages > 0 ||
        unreadAnnouncements > 0 ||
        pendingTickets > 0 ||
        pendingFriendRequests > 0 ||
        unreadFriendShares > 0
      );
    },

    // Actions
    fetch: fetchBadgeCounts,
    updateMessageCount,
    updateAnnouncementCount,
    updateTicketCount,
    updateFriendRequestCount,
    updateFriendShareCount,
    decrementMessages,
    decrementAnnouncements,
    reset,
    startAutoRefresh,
    stopAutoRefresh,
  };
}
