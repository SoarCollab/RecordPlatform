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
    // eslint-plugin-svelte v3 now parses .svelte.ts with svelte-eslint-parser;
    // must configure TypeScript parser for these files
    files: ["**/*.svelte.ts"],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
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
    // eslint-plugin-svelte v3 新增规则，暂时关闭，后续逐步修复
    rules: {
      "svelte/no-navigation-without-resolve": "off",
      "svelte/require-each-key": "off",
      "svelte/prefer-svelte-reactivity": "off",
    },
  },
  {
    ignores: ["build/", ".svelte-kit/", "dist/", "node_modules/", "coverage/"],
  },
);
