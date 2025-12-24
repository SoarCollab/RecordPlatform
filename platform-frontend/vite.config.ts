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
    plugins: [tailwindcss(), sveltekit()],
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
