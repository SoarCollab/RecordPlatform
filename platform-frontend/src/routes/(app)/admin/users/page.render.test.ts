import { cleanup, fireEvent, render, waitFor } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  listTenantMembers: vi.fn(),
  listTenantInvitations: vi.fn(),
  createTenantInvitation: vi.fn(),
  revokeTenantInvitation: vi.fn(),
  changeTenantMemberRole: vi.fn(),
  changeTenantMemberStatus: vi.fn(),
  revokeTenantMemberSessions: vi.fn(),
}));
const notifications = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
const auth = vi.hoisted(() => ({
  user: { externalId: "U-self" },
  initialized: true,
  isAdmin: true,
}));
const navigation = vi.hoisted(() => ({ goto: vi.fn() }));

vi.mock("$api/endpoints/tenant-users", () => api);
vi.mock("$stores/notifications.svelte", () => ({
  useNotifications: () => notifications,
}));
vi.mock("$stores/auth.svelte", () => ({
  useAuth: () => auth,
}));
vi.mock("$app/navigation", () => navigation);

import TenantUsersPage from "./+page.svelte";

/** Creates a manually resolvable promise for out-of-order request tests. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => (resolve = done));
  return { promise, resolve };
}

describe("tenant users page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    auth.isAdmin = true;
    api.listTenantInvitations.mockResolvedValue([]);
  });

  afterEach(cleanup);

  it("keeps the newest member-search result when responses arrive out of order", async () => {
    const oldRequest = deferred<unknown>();
    const newRequest = deferred<unknown>();
    api.listTenantMembers
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise);

    const view = render(TenantUsersPage);
    await waitFor(() => expect(api.listTenantMembers).toHaveBeenCalledTimes(1));
    await fireEvent.input(view.getByLabelText("搜索成员"), {
      target: { value: "new" },
    });
    await fireEvent.click(view.getByRole("button", { name: "搜索" }));

    newRequest.resolve({
      records: [
        {
          id: "U-new",
          username: "new-result",
          email: "new@example.com",
          role: "user",
          status: 1,
          registerTime: "2026-09-04 10:00:00",
        },
      ],
      total: 1,
      pages: 1,
    });
    await waitFor(() =>
      expect(view.getAllByText("new-result")).toHaveLength(2),
    );

    oldRequest.resolve({
      records: [
        {
          id: "U-old",
          username: "stale-result",
          email: "old@example.com",
          role: "user",
          status: 1,
          registerTime: "2026-09-04 09:00:00",
        },
      ],
      total: 1,
      pages: 1,
    });
    await Promise.resolve();
    expect(view.queryByText("stale-result")).toBeNull();
    expect(view.getAllByText("new-result")).toHaveLength(2);
  });

  it("redirects non-admin identities without loading tenant members", async () => {
    auth.isAdmin = false;

    render(TenantUsersPage);

    await waitFor(() =>
      expect(navigation.goto).toHaveBeenCalledWith("/dashboard", {
        replaceState: true,
      }),
    );
    expect(api.listTenantMembers).not.toHaveBeenCalled();
    expect(api.listTenantInvitations).not.toHaveBeenCalled();
  });
});
