import adapter from "@sveltejs/adapter-static";
import { vitePreprocess } from "@sveltejs/vite-plugin-svelte";
import { sveltekit } from "@sveltejs/kit/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");

  // Default to localhost if not set
  const targetUrl = env.PUBLIC_API_BASE_URL || "http://localhost:8000";
  // Extract origin for proxy target (e.g. http://192.168.5.100:8000)
  // We need to handle cases where the env var has a path or not.
  // For the proxy 'target', it usually expects the origin.
  let proxyTarget = targetUrl;
  try {
    const url = new URL(targetUrl);
    proxyTarget = url.origin;
  } catch {
    // If it's not a valid URL (e.g. relative), keep as is or fallback
    console.warn("Invalid PUBLIC_API_BASE_URL, using directly:", targetUrl);
  }

  return {
    plugins: [
      tailwindcss(),
      sveltekit({
        preprocess: vitePreprocess(),
        adapter: adapter({
          pages: "dist",
          assets: "dist",
          fallback: "index.html" /* SPA 路由回退 */,
          precompress: true /* 预压缩 .gz/.br */,
        }),

        alias: {
          // kit 3 已移除内置 `$lib` 别名，这里按原语义补回，
          // 使既有 $lib/... 引用无需全量改写。
          $lib: "src/lib",
          $components: "src/lib/components",
          $stores: "src/lib/stores",
          $api: "src/lib/api",
          $services: "src/lib/services",
          $utils: "src/lib/utils",
        },
      }),
    ],
    server: {
      port: 5173,
      proxy: {
        "/record-platform": {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
