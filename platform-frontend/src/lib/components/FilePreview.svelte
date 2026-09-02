<script lang="ts">
  import { api } from "$api/client";
  import { classifyFilePreview, loadPreviewText } from "$utils/file-preview";

  interface Props {
    url: string;
    contentType: string;
    fileName: string;
    class?: string;
  }

  let { url, contentType, fileName, class: className = "" }: Props = $props();

  let textContent = $state("");
  let loadingText = $state(false);
  let textError = $state<string | null>(null);
  let nativePreviewError = $state(false);

  const previewType = $derived(classifyFilePreview(fileName, contentType));

  $effect(() => {
    const sourceUrl = url;
    const sourceFileName = fileName;
    const sourceContentType = contentType;
    const sourcePreviewType = previewType;
    let cancelled = false;

    // Reset state whenever a reused preview component receives another file.
    void sourceFileName;
    void sourceContentType;
    nativePreviewError = false;
    textContent = "";
    textError = null;
    loadingText = sourcePreviewType === "text";
    if (sourcePreviewType === "text") {
      void loadTextContent(sourceUrl, () => cancelled);
    }

    return () => {
      cancelled = true;
    };
  });

  /** Loads one captured text source and ignores completion after the source changes. */
  async function loadTextContent(
    sourceUrl: string,
    isCancelled: () => boolean,
  ): Promise<void> {
    try {
      const text = await loadPreviewText(sourceUrl, (requestUrl) =>
        api.fetchText(requestUrl),
      );
      if (!isCancelled()) textContent = text;
    } catch (err) {
      if (!isCancelled()) {
        textError = err instanceof Error ? err.message : "加载失败";
      }
    } finally {
      if (!isCancelled()) loadingText = false;
    }
  }

  function getLanguageClass(type: string): string {
    if (type === "application/json") return "language-json";
    if (type === "application/xml" || type === "text/xml")
      return "language-xml";
    if (type === "application/javascript" || type === "text/javascript")
      return "language-javascript";
    if (type === "text/html") return "language-html";
    if (type === "text/css") return "language-css";
    if (type === "text/markdown") return "language-markdown";
    return "";
  }
</script>

<div class="file-preview {className}">
  {#if nativePreviewError}
    <div
      class="bg-muted/30 flex flex-col items-center justify-center gap-4 rounded-lg border p-12"
    >
      <p class="font-medium">浏览器无法解码此文件</p>
      <p class="text-muted-foreground text-sm">可下载后使用本地应用打开</p>
      <a class="text-primary text-sm underline" href={url} download={fileName}
        >下载文件</a
      >
    </div>
  {:else if previewType === "image"}
    <div class="bg-muted/30 flex items-center justify-center p-4">
      <img
        src={url}
        alt={fileName}
        class="max-h-[70vh] max-w-full rounded-lg object-contain shadow-lg"
        onerror={() => (nativePreviewError = true)}
      />
    </div>
  {:else if previewType === "video"}
    <div class="flex items-center justify-center bg-black p-4">
      <video
        src={url}
        controls
        class="max-h-[70vh] max-w-full rounded-lg"
        preload="metadata"
        onerror={() => (nativePreviewError = true)}
      >
        <track kind="captions" />
        您的浏览器不支持视频播放
      </video>
    </div>
  {:else if previewType === "audio"}
    <div
      class="bg-muted/30 flex flex-col items-center justify-center gap-4 p-8"
    >
      <div
        class="bg-primary/10 text-primary flex h-24 w-24 items-center justify-center rounded-full"
      >
        <svg
          class="h-12 w-12"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3"
          />
        </svg>
      </div>
      <p class="text-sm font-medium">{fileName}</p>
      <audio
        src={url}
        controls
        class="w-full max-w-md"
        onerror={() => (nativePreviewError = true)}
      >
        您的浏览器不支持音频播放
      </audio>
    </div>
  {:else if previewType === "pdf"}
    <div class="h-[70vh] w-full">
      <iframe
        src={url}
        title={fileName}
        class="h-full w-full rounded-lg border-0"
      ></iframe>
    </div>
  {:else if previewType === "text"}
    <div class="bg-muted/30 overflow-hidden rounded-lg border">
      {#if loadingText}
        <div class="flex items-center justify-center p-8">
          <div
            class="border-primary h-6 w-6 animate-spin rounded-full border-2 border-t-transparent"
          ></div>
        </div>
      {:else if textError}
        <div class="text-muted-foreground p-8 text-center">
          <p>{textError}</p>
        </div>
      {:else}
        <pre
          class="max-h-[70vh] overflow-auto p-4 text-sm {getLanguageClass(
            contentType,
          )}"><code>{textContent}</code></pre>
      {/if}
    </div>
  {:else}
    <div
      class="bg-muted/30 flex flex-col items-center justify-center gap-4 rounded-lg border p-12"
    >
      <div
        class="bg-muted text-muted-foreground flex h-16 w-16 items-center justify-center rounded-full"
      >
        <svg
          class="h-8 w-8"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
          />
        </svg>
      </div>
      <p class="text-muted-foreground">此文件类型不支持预览</p>
      <p class="text-muted-foreground text-sm">{contentType}</p>
      <a class="text-primary text-sm underline" href={url} download={fileName}
        >下载文件</a
      >
    </div>
  {/if}
</div>

<style>
  pre {
    margin: 0;
    white-space: pre-wrap;
    word-wrap: break-word;
  }
  code {
    font-family:
      ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
  }
</style>
