import { beforeEach, describe, expect, it, vi } from "vitest";

const clientMocks = vi.hoisted(() => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock("../client", () => ({ api: clientMocks.api }));

import * as manifestApi from "./manifest-backfill";

describe("manifest backfill endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMocks.api.get.mockResolvedValue({});
    clientMocks.api.post.mockResolvedValue({});
  });

  it("uses the tenant-scoped admin governance paths", async () => {
    await manifestApi.createManifestBackfillRun({ mode: "SCAN" });
    await manifestApi.listManifestBackfillRuns(20);
    await manifestApi.getManifestBackfillRun("run-1");
    await manifestApi.listManifestBackfillItems("run-1", {
      status: "FAILED",
      limit: 25,
    });
    await manifestApi.pauseManifestBackfillRun("run-1");
    await manifestApi.resumeManifestBackfillRun("run-1");
    await manifestApi.retryManifestBackfillItem("run-1", "item-1");
    await manifestApi.createManifestReferenceCensus();
    await manifestApi.markManifestReferenceSweepObject({
      storagePath: "storage/tenant/1/chunk/hash",
      cipherHash: "hash",
    });

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/admin/manifest-backfill-runs",
      { mode: "SCAN" },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/admin/manifest-backfill-runs",
      { params: { limit: 20 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/admin/manifest-backfill-runs/run-1",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      3,
      "/admin/manifest-backfill-runs/run-1/items",
      { params: { status: "FAILED", limit: 25 } },
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/admin/manifest-backfill-runs/run-1/pause",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      3,
      "/admin/manifest-backfill-runs/run-1/resume",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      4,
      "/admin/manifest-backfill-runs/run-1/items/item-1/retry",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      5,
      "/admin/manifest-backfill-runs/reference-census",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      6,
      "/admin/manifest-backfill-runs/reference-sweep/marks",
      {
        storagePath: "storage/tenant/1/chunk/hash",
        cipherHash: "hash",
      },
    );
  });
});
