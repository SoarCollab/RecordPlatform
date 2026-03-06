import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as prettier from "prettier";
import * as sveltePlugin from "prettier-plugin-svelte";
import * as tailwindPlugin from "prettier-plugin-tailwindcss";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "..");
const prettierIgnorePath = path.join(projectRoot, ".prettierignore");
const prettierPlugins = [
  sveltePlugin.default ?? sveltePlugin,
  tailwindPlugin.default ?? tailwindPlugin,
];

/**
 * Normalize incoming file arguments to absolute paths under the frontend project.
 *
 * @param {string[]} fileArgs Raw file arguments from lint-staged.
 * @returns {string[]} Unique absolute file paths.
 */
function normalizeFileArgs(fileArgs) {
  return [...new Set(fileArgs)]
    .filter(Boolean)
    .map((filePath) =>
      path.isAbsolute(filePath)
        ? filePath
        : path.resolve(projectRoot, filePath),
    );
}

/**
 * Format a single file with the Prettier API so bracketed route paths do not go
 * through Prettier CLI glob parsing.
 *
 * @param {string} filePath Absolute file path.
 * @returns {Promise<void>}
 */
async function formatFile(filePath) {
  const fileInfo = await prettier.getFileInfo(filePath, {
    ignorePath: prettierIgnorePath,
    plugins: prettierPlugins,
  });

  if (fileInfo.ignored || !fileInfo.inferredParser) {
    return;
  }

  const source = await fs.readFile(filePath, "utf8");
  const resolvedConfig =
    (await prettier.resolveConfig(filePath, { editorconfig: true })) ?? {};
  const formatted = await prettier.format(source, {
    ...resolvedConfig,
    filepath: filePath,
    plugins: prettierPlugins,
  });

  if (formatted !== source) {
    await fs.writeFile(filePath, formatted, "utf8");
  }
}

/**
 * Run the staged-file prettier pass.
 *
 * @returns {Promise<void>}
 */
async function main() {
  const files = normalizeFileArgs(process.argv.slice(2));

  for (const filePath of files) {
    await formatFile(filePath);
  }
}

await main();
