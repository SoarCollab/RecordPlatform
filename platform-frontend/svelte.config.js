import adapter from "@sveltejs/adapter-static";
import { vitePreprocess } from "@sveltejs/vite-plugin-svelte";

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({
      pages: "dist",
      assets: "dist",
      fallback: "index.html", // SPA 路由回退
      precompress: true, // 预压缩 .gz/.br
    }),
    alias: {
      $components: "src/lib/components",
      $stores: "src/lib/stores",
      $api: "src/lib/api",
      $services: "src/lib/services",
      $utils: "src/lib/utils",
    },
  },
};

export default config;
