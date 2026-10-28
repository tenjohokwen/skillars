#!/usr/bin/env node
// Gives every <h2>/<h3> without an id a stable slug id (from its text, de-duplicated within the
// page). Existing ids are never changed, since other pages and the search index link to them.
// check-docs.mjs requires an id on every h2/h3; run this after adding a page or headings.
//
//   node docs/dev-docs/tools/add-heading-ids.mjs
import { writeFileSync } from "node:fs";
import { listPages, readPage, slugify } from "./lib.mjs";

let total = 0;
for (const page of listPages()) {
  const src = readPage(page);
  if (src == null) continue;
  const used = new Set([...src.matchAll(/\s(?:id|name)="([^"]*)"/g)].map((m) => m[1]));
  let added = 0;
  const out = src.replace(/<h([23])((?:\s[^>]*)?)>([\s\S]*?)<\/h\1>/g, (all, level, attrs, inner) => {
    if (/\sid=/.test(attrs)) return all;
    const base = slugify(inner);
    let id = base;
    for (let n = 2; used.has(id); n++) id = `${base}-${n}`;
    used.add(id);
    added++;
    return `<h${level}${attrs} id="${id}">${inner}</h${level}>`;
  });
  if (added) { writeFileSync(page.abs, out); total += added; console.log(`${page.rel}: ${added} id(s) added`); }
}
console.log(`${total} heading id(s) added.`);
