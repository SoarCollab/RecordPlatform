import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import svelte from "eslint-plugin-svelte";
import globals from "globals";

export default tseslint.config(
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  ...svelte.configs["flat/recommended"],
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
  },
  {
    files: ["**/*.svelte"],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    rules: {
      // shadcn-svelte 组件使用 $props() rest 模式，非 custom element 无需此规则
      "svelte/valid-compile": ["error", { ignoreWarnings: true }],
    },
  },
  {
    files: ["**/*.ts", "**/*.svelte"],
    rules: {
      // 允许以下划线开头的未使用变量
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
    },
  },
  {
    ignores: ["build/", ".svelte-kit/", "dist/", "node_modules/", "coverage/"],
  },
);
