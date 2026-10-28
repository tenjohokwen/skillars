#!/usr/bin/env node
// Copies the root page's <aside class="sidebar"> into every other page, re-prefixing relative
// links with ../ for pages one level down. Edit the sidebar in docs/dev-docs/index.html only,
// then run this. Also inserts the assets/search-index.js script tag where a page lacks it.
//
//   node docs/dev-docs/tools/sync-sidebar.mjs
import { writeFileSync } from "node:fs";
import { listPages, readPage, extractSidebar, prefixRelative } from "./lib.mjs";

const [root, ...pages] = listPages();
const rootSidebar = extractSidebar(readPage(root));
if (!rootSidebar) {
  console.error(`${root.rel}: no <aside class="sidebar"> found`);
  process.exit(1);
}
let changed = 0, failed = 0;
for (const page of [root, ...pages]) {
  const src = readPage(page);
  if (src == null) { console.error(`${page.rel}: missing (directory without index.html)`); failed++; continue; }
  let out = src;
  if (page !== root) {
    const sb = extractSidebar(src);
    if (!sb) { console.error(`${page.rel}: no <aside class="sidebar"> found; copy the skeleton from outbox/index.html`); failed++; continue; }
    out = src.slice(0, sb.start) + prefixRelative(rootSidebar.html, page.prefix) + src.slice(sb.end);
  }
  const searchTag = `<script src="${page.prefix}assets/search-index.js"></script>`;
  const docsTag = `<script src="${page.prefix}assets/docs.js"></script>`;
  if (!out.includes(searchTag) && out.includes(docsTag)) out = out.replace(docsTag, `${searchTag}\n${docsTag}`);
  if (out !== src) { writeFileSync(page.abs, out); changed++; console.log(`updated ${page.rel}`); }
}
console.log(`${changed} page(s) updated.`);
process.exit(failed ? 1 : 0);
