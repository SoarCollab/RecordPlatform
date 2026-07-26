import { api } from "../client";
import type {
  ManifestBackfillCreateRequest,
  ManifestBackfillItemPageVO,
  ManifestBackfillRunVO,
  ManifestReferenceCensusVO,
  ManifestReferenceSweepMarkRequest,
  ManifestReferenceSweepMarkVO,
} from "../types";

const BASE = "/admin/manifest-backfill-runs";

/** Creates an asynchronous scan, dry-run, or apply run. */
export function createManifestBackfillRun(
  request: ManifestBackfillCreateRequest,
): Promise<ManifestBackfillRunVO> {
  return api.post<ManifestBackfillRunVO>(BASE, request);
}

/** Lists the newest bounded run history. */
export function listManifestBackfillRuns(
  limit = 50,
): Promise<ManifestBackfillRunVO[]> {
  return api.get<ManifestBackfillRunVO[]>(BASE, { params: { limit } });
}

/** Reads one durable run. */
export function getManifestBackfillRun(
  runId: string,
): Promise<ManifestBackfillRunVO> {
  return api.get<ManifestBackfillRunVO>(`${BASE}/${runId}`);
}

/** Reads one bounded cursor page of per-file outcomes. */
export function listManifestBackfillItems(
  runId: string,
  params?: {
    cursor?: string;
    status?: string;
    classification?: string;
    reason?: string;
    limit?: number;
  },
): Promise<ManifestBackfillItemPageVO> {
  return api.get<ManifestBackfillItemPageVO>(`${BASE}/${runId}/items`, {
    params,
  });
}

/** Pauses a run at its next durable boundary. */
export function pauseManifestBackfillRun(
  runId: string,
): Promise<ManifestBackfillRunVO> {
  return api.post<ManifestBackfillRunVO>(`${BASE}/${runId}/pause`);
}

/** Resumes a paused run against the same snapshot. */
export function resumeManifestBackfillRun(
  runId: string,
): Promise<ManifestBackfillRunVO> {
  return api.post<ManifestBackfillRunVO>(`${BASE}/${runId}/resume`);
}

/** Requeues one failed item. */
export function retryManifestBackfillItem(
  runId: string,
  itemId: string,
): Promise<ManifestBackfillRunVO> {
  return api.post<ManifestBackfillRunVO>(
    `${BASE}/${runId}/items/${itemId}/retry`,
  );
}

/** Creates and seals a fresh reference census. */
export function createManifestReferenceCensus(): Promise<ManifestReferenceCensusVO> {
  return api.post<ManifestReferenceCensusVO>(`${BASE}/reference-census`);
}

/** Starts the independently gated grace lifecycle for one exact object. */
export function markManifestReferenceSweepObject(
  request: ManifestReferenceSweepMarkRequest,
): Promise<ManifestReferenceSweepMarkVO> {
  return api.post<ManifestReferenceSweepMarkVO>(
    `${BASE}/reference-sweep/marks`,
    request,
  );
}
