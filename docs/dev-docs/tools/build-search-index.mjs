#!/usr/bin/env node
// Regenerates assets/search-index.js from every page (title, h2/h3 headings with ids, plain text).
// Read-only with respect to the pages. check-docs.mjs fails when the committed index is stale.
//
//   node docs/dev-docs/tools/build-search-index.mjs
import { writeFileSync, readFileSync, existsSync } from "node:fs";
import { relative } from "node:path";
import { buildSearchIndex, SEARCH_INDEX_FILE } from "./lib.mjs";

const next = buildSearchIndex();
const prev = existsSync(SEARCH_INDEX_FILE) ? readFileSync(SEARCH_INDEX_FILE, "utf8") : null;
const name = relative(process.cwd(), SEARCH_INDEX_FILE);
if (prev === next) {
  console.log(`${name} is up to date (${(next.length / 1024).toFixed(0)} KiB).`);
} else {
  writeFileSync(SEARCH_INDEX_FILE, next);
  console.log(`Wrote ${name} (${(next.length / 1024).toFixed(0)} KiB).`);
}
