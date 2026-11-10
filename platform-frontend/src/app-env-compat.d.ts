// SvelteKit 3 把 `$app/environment` 改名为 `$app/env`（browser/dev/building/version 语义不变）。
// 运行时通过 vite 插件里的 alias 保持兼容，这里补上类型声明让 TypeScript 也能解析。
declare module "$app/environment" {
  export * from "$app/env";
}
