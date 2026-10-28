#!/usr/bin/env node
// Health check for docs/dev-docs. Node built-ins only (runs in CI without `npm install`).
//
//   node docs/dev-docs/tools/check-docs.mjs            static checks
//   node docs/dev-docs/tools/check-docs.mjs --render   + render every page in headless Chrome and
//                                                      fail on Mermaid syntax errors
//
// Static checks, per page:
//   - balanced HTML tags (void elements ignored)
//   - every relative href/src resolves to a file; #anchors exist in the target page
//   - <aside class="sidebar"> identical to the root page's (modulo ../), and lists every page
//   - every h2/h3 has an id, ids unique; search-index.js and docs.js are loaded
//   - Mermaid blocks: no ';' inside labels/messages, no raw <Tag> except <br/>
//   - footer carries "Last verified against master <code>SHA</code> (YYYY-MM-DD)"
//   - assets/search-index.js is up to date
// Chrome for --render: $CHROME_BIN, else the usual install locations.
import { existsSync, statSync, readFileSync, mkdtempSync, rmSync } from "node:fs";
import { join, dirname, resolve, relative } from "node:path";
import { tmpdir } from "node:os";
import { execFile } from "node:child_process";
import { pathToFileURL } from "node:url";
import {
  DOCS_ROOT, SEARCH_INDEX_FILE, listPages, readPage, tokenize, lineIndex, decodeEntities, hasClass,
  VOID, extractSidebar, prefixRelative, buildSearchIndex,
} from "./lib.mjs";

const args = new Set(process.argv.slice(2));
const RENDER = args.has("--render");
const REPO_ROOT = resolve(DOCS_ROOT, "../..");
const show = (abs) => relative(process.cwd(), abs) || ".";

const errors = [];
const fail = (file, line, msg) => errors.push({ file, line, msg });

const STAMP_RE = /<footer class="page-footer">[\s\S]*?Last verified against master <code>([0-9a-f]{7,40})<\/code> \((\d{4}-\d{2}-\d{2})\)[\s\S]*?<\/footer>/i;
const EXTERNAL_RE = /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i;

const pages = listPages();
const parsed = new Map(); // abs -> {src, tokens, line, ids}

for (const page of pages) {
  const src = readPage(page);
  if (src == null) { fail(page.abs, 0, `directory has no index.html (every directory under docs/dev-docs except assets/ and tools/ is a page)`); continue; }
  const tokens = tokenize(src);
  const line = lineIndex(src);
  const ids = new Map();
  for (const t of tokens) {
    if (t.type !== "open") continue;
    for (const key of ["id", "name"]) {
      if (key === "name" && t.name !== "a") continue;
      const v = t.attrs[key];
      if (v == null) continue;
      if (ids.has(v)) fail(page.abs, line(t.start), `duplicate id "${v}" (first at line ${ids.get(v)})`);
      else ids.set(v, line(t.start));
    }
  }
  parsed.set(page.abs, { page, src, tokens, line, ids });
}

// --- per-page checks ---------------------------------------------------------------------------
for (const { page, src, tokens, line, ids } of parsed.values()) {
  const f = page.abs;

  // Tag balance.
  const stack = [];
  for (const t of tokens) {
    if (t.type === "open" && !VOID.has(t.name) && !t.selfClosing) stack.push(t);
    else if (t.type === "close") {
      if (VOID.has(t.name)) { fail(f, line(t.start), `closing tag </${t.name}> for a void element`); continue; }
      const top = stack[stack.length - 1];
      if (top && top.name === t.name) { stack.pop(); continue; }
      const at = stack.map((o) => o.name).lastIndexOf(t.name);
      if (at < 0) { fail(f, line(t.start), `stray </${t.name}> with no matching open tag`); continue; }
      for (const o of stack.splice(at + 1).reverse()) fail(f, line(o.start), `<${o.name}> is never closed (implicitly closed by </${t.name}> at line ${line(t.start)})`);
      stack.pop();
    } else if (t.type === "text" && !t.raw && /<[a-zA-Z\/!]/.test(t.text)) {
      const off = t.start + t.text.search(/<[a-zA-Z\/!]/);
      fail(f, line(off), `unparseable or unescaped '<' in text: ${JSON.stringify(src.slice(off, off + 40))}`);
    }
  }
  for (const o of stack) fail(f, line(o.start), `<${o.name}> is never closed`);

  // Links.
  for (const t of tokens) {
    if (t.type !== "open") continue;
    for (const attr of ["href", "src"]) {
      const raw = t.attrs[attr];
      if (raw == null) continue;
      const ln = line(t.start);
      if (raw === "") { fail(f, ln, `empty ${attr}`); continue; }
      if (EXTERNAL_RE.test(raw)) continue;
      const hashAt = raw.indexOf("#");
      const path = decodeURI(hashAt >= 0 ? raw.slice(0, hashAt) : raw).split("?")[0];
      const frag = hashAt >= 0 ? decodeURIComponent(raw.slice(hashAt + 1)) : null;
      if (path.startsWith("/")) { fail(f, ln, `absolute path ${attr}="${raw}" breaks over file://; use a relative path`); continue; }
      const target = path ? resolve(dirname(f), path) : f;
      if (!existsSync(target)) { fail(f, ln, `${attr}="${raw}" -> ${show(target)} does not exist`); continue; }
      if (statSync(target).isDirectory()) { fail(f, ln, `${attr}="${raw}" points at a directory; link to its index.html (directory links don't work over file://)`); continue; }
      if (target.startsWith(REPO_ROOT) === false) { fail(f, ln, `${attr}="${raw}" leaves the repository`); continue; }
      if (frag) {
        const tp = parsed.get(target);
        if (tp && !tp.ids.has(frag)) fail(f, ln, `${attr}="${raw}": no id="${frag}" in ${show(target)}`);
      }
    }
  }

  // Headings need ids (search results deep-link to them).
  for (const t of tokens) {
    if (t.type === "open" && (t.name === "h2" || t.name === "h3") && !t.attrs.id) {
      fail(f, line(t.start), `<${t.name}> has no id (run node docs/dev-docs/tools/add-heading-ids.mjs)`);
    }
  }

  // Scripts every page needs.
  for (const s of ["assets/search-index.js", "assets/docs.js"]) {
    if (!src.includes(`<script src="${page.prefix}${s}"></script>`)) fail(f, 0, `missing <script src="${page.prefix}${s}"></script> (run tools/sync-sidebar.mjs)`);
  }

  // Mermaid.
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k];
    if (!(t.type === "open" && t.name === "pre" && hasClass(t, "mermaid"))) continue;
    const startLine = line(t.end);
    let body = "";
    for (let j = k + 1; j < tokens.length && !(tokens[j].type === "close" && tokens[j].name === "pre"); j++) {
      if (tokens[j].type === "text") body += tokens[j].text;
      else if (tokens[j].type === "open" && tokens[j].name === "br") body += "<br/>";
      else fail(f, line(tokens[j].start), `raw HTML tag <${tokens[j].name}> inside a Mermaid block; only <br/> is allowed, escape anything else (&lt; &gt;)`);
    }
    checkMermaid(f, startLine, decodeEntities(body));
  }

  // Freshness stamp.
  const stamps = src.match(/<footer class="page-footer">/g) || [];
  const m = STAMP_RE.exec(src);
  if (stamps.length !== 1) fail(f, 0, `expected exactly one <footer class="page-footer">, found ${stamps.length}`);
  if (!m) fail(f, 0, `footer lacks a well-formed stamp: Last verified against master <code>SHORT_SHA</code> (YYYY-MM-DD)`);
  else {
    const d = new Date(`${m[2]}T00:00:00Z`);
    if (Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== m[2]) fail(f, line(m.index), `stamp date ${m[2]} is not a real date`);
  }
}

function checkMermaid(file, startLine, text) {
  const lines = text.split("\n");
  const kind = (lines.find((l) => l.trim() && !l.trim().startsWith("%%")) || "").trim().split(/\s+/)[0];
  const colonLabels = /^(sequenceDiagram|stateDiagram|stateDiagram-v2|classDiagram|erDiagram|journey|gantt|timeline|pie)$/.test(kind);
  lines.forEach((raw, i) => {
    const ln = startLine + i;
    const l = raw.trim();
    if (!l || l.startsWith("%%")) return;
    for (const tag of l.match(/<\/?[A-Za-z][^<>]*>/g) || []) {
      if (!/^<br\s*\/?>$/i.test(tag)) fail(file, ln, `Mermaid: raw tag ${tag} in a diagram (only <br/> is allowed; write it as &amp;lt;...&amp;gt; or reword)`);
    }
    if (/^(classDef|style|linkStyle)\b/.test(l)) return;
    const s = l.replace(/#[a-z0-9]+;/gi, "#"); // Mermaid entity codes (#59; #quot;) are fine
    // ';' inside bracketed/quoted labels or edge labels.
    let depth = 0, quote = false;
    for (let c = 0; c < s.length; c++) {
      const ch = s[c];
      if (ch === '"') quote = !quote;
      else if (!quote && "[({".includes(ch)) depth++;
      else if (!quote && "])}".includes(ch)) depth = Math.max(0, depth - 1);
      else if (ch === ";" && (depth > 0 || quote)) { fail(file, ln, `Mermaid: ';' inside a label ends the statement early: ${l}`); return; }
    }
    const pipes = s.match(/\|[^|]*\|/g) || [];
    if (pipes.some((p) => p.includes(";"))) { fail(file, ln, `Mermaid: ';' inside an edge label: ${l}`); return; }
    if (colonLabels) {
      const at = s.indexOf(":");
      if (at >= 0 && s.slice(at + 1).replace(/;\s*$/, "").includes(";")) fail(file, ln, `Mermaid: ';' inside a message/label ends the statement early: ${l}`);
    }
  });
}

// --- cross-page checks -------------------------------------------------------------------------
const root = parsed.get(pages[0].abs);
const rootSidebar = root && extractSidebar(root.src);
if (!rootSidebar) fail(pages[0].abs, 0, `no <aside class="sidebar"> in the root page`);
else {
  const linked = new Set(
    [...rootSidebar.html.matchAll(/href="([^"#]+)"/g)].map((m) => resolve(DOCS_ROOT, m[1])),
  );
  for (const page of pages.slice(1)) {
    if (!linked.has(page.abs)) fail(pages[0].abs, root.line(rootSidebar.start), `sidebar does not link ${page.rel}`);
  }
  if (!rootSidebar.html.includes('id="docs-search"')) fail(pages[0].abs, root.line(rootSidebar.start), `sidebar has no search box (input#docs-search)`);
  for (const p of parsed.values()) {
    if (p === root) continue;
    const sb = extractSidebar(p.src);
    if (!sb) { fail(p.page.abs, 0, `no <aside class="sidebar">`); continue; }
    const want = prefixRelative(rootSidebar.html, p.page.prefix);
    if (sb.html !== want) {
      const a = sb.html.split("\n"), b = want.split("\n");
      let i = 0;
      while (i < a.length && a[i] === b[i]) i++;
      fail(p.page.abs, p.line(sb.start) + i, `sidebar differs from the root page's: got ${JSON.stringify((a[i] ?? "<end>").trim())}, want ${JSON.stringify((b[i] ?? "<end>").trim())} (run tools/sync-sidebar.mjs)`);
    }
  }
}

const freshIndex = buildSearchIndex();
const onDisk = existsSync(SEARCH_INDEX_FILE) ? readFileSync(SEARCH_INDEX_FILE, "utf8") : null;
if (onDisk !== freshIndex) fail(SEARCH_INDEX_FILE, 0, onDisk == null ? `missing (run tools/build-search-index.mjs)` : `stale (run tools/build-search-index.mjs)`);

// --- render ------------------------------------------------------------------------------------
if (RENDER) await renderAll();

report();

function report() {
  const byFile = [...errors].sort((x, y) => x.file.localeCompare(y.file) || x.line - y.line);
  for (const e of byFile) console.error(`${show(e.file)}${e.line ? `:${e.line}` : ""}: ${e.msg}`);
  const what = `${pages.length} pages${RENDER ? " (static + render)" : " (static)"}`;
  if (errors.length) {
    console.error(`\ncheck-docs: ${errors.length} problem(s) in ${what}.`);
    process.exit(1);
  }
  console.log(`check-docs: OK, ${what}.`);
}

function findChrome() {
  const candidates = [
    process.env.CHROME_BIN,
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser",
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  ].filter(Boolean);
  return candidates.find((c) => existsSync(c));
}

function dumpDom(chrome, abs) {
  const profile = mkdtempSync(join(tmpdir(), "check-docs-chrome-"));
  const argv = [
    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check", "--disable-extensions",
    "--allow-file-access-from-files", "--virtual-time-budget=15000", `--user-data-dir=${profile}`,
    ...(process.platform === "linux" ? ["--no-sandbox"] : []),
    "--dump-dom", pathToFileURL(abs).href,
  ];
  return new Promise((res) => {
    execFile(chrome, argv, { maxBuffer: 64 * 1024 * 1024, timeout: 90_000 }, (err, stdout, stderr) => {
      rmSync(profile, { recursive: true, force: true });
      res({ err, stdout, stderr });
    });
  });
}

async function renderAll() {
  const chrome = findChrome();
  if (!chrome) { fail(DOCS_ROOT, 0, `--render: no Chrome/Chromium found; set CHROME_BIN`); return; }
  const queue = [...parsed.values()];
  const worker = async () => {
    for (let p; (p = queue.shift()); ) {
      const { err, stdout, stderr } = await dumpDom(chrome, p.page.abs);
      if (!stdout.includes("</html>")) { fail(p.page.abs, 0, `render: Chrome produced no DOM (${err?.message ?? "no error"}) ${stderr.slice(0, 300)}`); continue; }
      const blocks = [...p.src.matchAll(/<pre class="mermaid"/g)].length;
      if (/Syntax error/i.test(stdout)) fail(p.page.abs, 0, `render: Mermaid reported a syntax error`);
      const pres = [...stdout.matchAll(/<pre\b[^>]*>/g)].map((m) => m[0]).filter((tag) => /class="[^"]*\bmermaid\b/.test(tag));
      const unprocessed = pres.filter((tag) => !/data-processed=/.test(tag));
      if (unprocessed.length) fail(p.page.abs, 0, `render: ${unprocessed.length} of ${blocks} Mermaid block(s) left unprocessed`);
      const svgs = (stdout.match(/<svg\b[^>]*aria-roledescription=/g) || []).length;
      if (!unprocessed.length && svgs < blocks) fail(p.page.abs, 0, `render: ${blocks} Mermaid block(s) but only ${svgs} diagram(s) rendered`);
    }
  };
  await Promise.all(Array.from({ length: 4 }, worker));
}
