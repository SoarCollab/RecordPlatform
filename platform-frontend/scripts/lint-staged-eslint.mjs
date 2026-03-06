import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "..");
const eslintBin = path.join(projectRoot, "node_modules", ".bin", "eslint");

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
 * Run ESLint with explicit argv handling so route paths with brackets are passed
 * as literal file names instead of shell patterns.
 *
 * @param {string[]} filePaths Absolute file paths to lint.
 * @returns {Promise<void>}
 */
function runEslint(filePaths) {
  if (filePaths.length === 0) {
    return Promise.resolve();
  }

  return new Promise((resolve, reject) => {
    const child = spawn(eslintBin, ["--fix", ...filePaths], {
      cwd: projectRoot,
      stdio: "inherit",
    });

    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`eslint exited with code ${code ?? "unknown"}`));
    });
  });
}

/**
 * Run the staged-file eslint pass.
 *
 * @returns {Promise<void>}
 */
async function main() {
  const files = normalizeFileArgs(process.argv.slice(2));
  await runEslint(files);
}

await main();
