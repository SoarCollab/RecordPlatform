import { ApiError, createApiClient } from "./client";
import { ResultCode } from "./types/common";

type PlatformReadPath =
  | "/platform/session"
  | "/platform/overview"
  | "/platform/resources/health"
  | "/platform/tenants"
  | `/platform/tenants/${string}`
  | "/platform/users"
  | "/platform/configuration"
  | `/platform/configuration/${string}`
  | "/platform/audit"
  | `/platform/audit/${string}`;

type PlatformWritePath =
  | "/platform/tenants"
  | `/platform/tenants/${string}`
  | `/platform/configuration/${string}`;

type PlatformQuery = Readonly<Record<string, string | number | undefined>>;
type PlatformWriteMethod = "POST" | "PUT" | "DELETE";

const transport = createApiClient({ tenantId: "0" });
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const READ_PATH =
  /^\/platform\/(?:session|overview|resources\/health|users|tenants(?:\/[^/]+(?:\/(?:users|invitations|quota|usage))?)?|configuration(?:\/[^/]+)?|audit(?:\/[^/]+)?)$/;
const WRITE_PATHS: Record<PlatformWriteMethod, RegExp> = {
  POST: /^\/platform\/tenants(?:\/[^/]+\/(?:invitations|users\/[^/]+\/sessions\/revoke))?$/,
  PUT: /^\/platform\/(?:tenants\/[^/]+(?:\/(?:status|quota|users\/[^/]+\/(?:role|status)))?|configuration\/[^/]+)$/,
  DELETE: /^\/platform\/tenants\/[^/]+\/invitations\/[^/]+$/,
};

/** Rejects raw URLs and traversal before the fixed-system transport sees a path. */
function validatePath(path: string, allowed: RegExp): void {
  if (
    typeof path !== "string" ||
    /[?#\\\s]/.test(path) ||
    !allowed.test(path) ||
    path.split("/").some((segment) => /^(?:\.|%2e){1,2}$/i.test(segment))
  ) {
    throw new ApiError(ResultCode.PARAM_IS_INVALID, "平台请求路径无效");
  }
}

/** Reads platform data for endpoint decoders without exposing transport controls. */
export async function platformGet(
  path: PlatformReadPath,
  params?: PlatformQuery,
): Promise<unknown> {
  validatePath(path, READ_PATH);
  return transport.get<unknown>(path, { params });
}

/** Sends one confirmed attempt with its exact UUID and no automatic mutation retry. */
export async function platformWrite(
  method: PlatformWriteMethod,
  path: PlatformWritePath,
  request: Readonly<object>,
  idempotencyKey: string,
): Promise<unknown> {
  validatePath(path, WRITE_PATHS[method]);
  if (typeof idempotencyKey !== "string" || !UUID.test(idempotencyKey)) {
    throw new ApiError(ResultCode.PARAM_IS_INVALID, "平台操作标识无效");
  }
  const config = {
    headers: { "Idempotency-Key": idempotencyKey },
    retries: 0,
  };
  if (method === "POST") return transport.post<unknown>(path, request, config);
  if (method === "PUT") return transport.put<unknown>(path, request, config);
  return transport.deleteWithBody<unknown>(path, request, config);
}
