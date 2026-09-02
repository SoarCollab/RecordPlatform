export type PlatformTextFetcher = (url: string) => Promise<string>;

export type NativePreviewFetcher = (
  url: string,
) => Promise<Pick<Response, "ok" | "status" | "text">>;

/**
 * Loads preview text without routing already-decrypted same-document URLs through the API client.
 */
export async function loadPreviewText(
  url: string,
  platformFetchText: PlatformTextFetcher,
  nativeFetch: NativePreviewFetcher = globalThis.fetch,
): Promise<string> {
  if (!url.startsWith("blob:") && !url.startsWith("data:")) {
    return platformFetchText(url);
  }

  const response = await nativeFetch(url);
  if (!response.ok) {
    throw new Error(`请求失败 (${response.status})`);
  }
  return response.text();
}
