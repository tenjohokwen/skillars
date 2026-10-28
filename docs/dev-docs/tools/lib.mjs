// Shared helpers for the dev-docs tools. Node built-ins only: these scripts run in CI with no
// `npm install`, so do not add an import of anything that is not `node:*`.
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const DOCS_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const SEARCH_INDEX_FILE = join(DOCS_ROOT, "assets", "search-index.js");

// Directories under docs/dev-docs that are not pages.
const NON_PAGE_DIRS = new Set(["assets", "tools"]);

/** Every page, root first, then `<slug>/index.html` sorted by slug. `rel` is relative to DOCS_ROOT. */
export function listPages() {
  const pages = [{ rel: "index.html", depth: 0 }];
  for (const name of readdirSync(DOCS_ROOT).sort()) {
    if (NON_PAGE_DIRS.has(name) || name.startsWith(".")) continue;
    if (!statSync(join(DOCS_ROOT, name)).isDirectory()) continue;
    pages.push({ rel: `${name}/index.html`, depth: 1, dir: name });
  }
  return pages.map((p) => ({ ...p, abs: join(DOCS_ROOT, p.rel), prefix: "../".repeat(p.depth) }));
}

export function readPage(page) {
  return existsSync(page.abs) ? readFileSync(page.abs, "utf8") : null;
}

// ---------------------------------------------------------------------------------------------
// Tokenizer: just enough HTML for hand-written pages. Not a spec-compliant parser.

export const VOID = new Set([
  "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
]);
const RAW_TEXT = new Set(["script", "style"]);

const TOKEN_RE =
  /<!--[\s\S]*?-->|<!doctype[^>]*>|<\/([a-zA-Z][\w-]*)\s*>|<([a-zA-Z][\w-]*)((?:\s+[^\s"'>\/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'>]+))?)*)\s*(\/?)>/gi;
const ATTR_RE = /([^\s"'>\/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g;

export function parseAttrs(s) {
  const attrs = {};
  if (!s) return attrs;
  for (const m of s.matchAll(ATTR_RE)) attrs[m[1].toLowerCase()] = m[2] ?? m[3] ?? m[4] ?? "";
  return attrs;
}

/**
 * Tokens: {type: 'open'|'close'|'text'|'comment'|'doctype', name, attrs, start, end, selfClosing}.
 * script/style bodies are emitted as one text token.
 */
export function tokenize(src) {
  const tokens = [];
  let last = 0;
  TOKEN_RE.lastIndex = 0;
  let m;
  while ((m = TOKEN_RE.exec(src))) {
    if (m.index > last) tokens.push({ type: "text", start: last, end: m.index, text: src.slice(last, m.index) });
    const raw = m[0];
    if (raw.startsWith("<!--")) tokens.push({ type: "comment", start: m.index, end: TOKEN_RE.lastIndex });
    else if (/^<!doctype/i.test(raw)) tokens.push({ type: "doctype", start: m.index, end: TOKEN_RE.lastIndex });
    else if (m[1]) tokens.push({ type: "close", name: m[1].toLowerCase(), start: m.index, end: TOKEN_RE.lastIndex });
    else {
      const name = m[2].toLowerCase();
      tokens.push({
        type: "open", name, attrs: parseAttrs(m[3]), start: m.index, end: TOKEN_RE.lastIndex, selfClosing: m[4] === "/",
      });
      if (RAW_TEXT.has(name)) {
        const closeIdx = src.toLowerCase().indexOf(`</${name}`, TOKEN_RE.lastIndex);
        if (closeIdx > TOKEN_RE.lastIndex) {
          tokens.push({ type: "text", start: TOKEN_RE.lastIndex, end: closeIdx, text: src.slice(TOKEN_RE.lastIndex, closeIdx), raw: true });
        }
        if (closeIdx >= 0) TOKEN_RE.lastIndex = closeIdx;
      }
    }
    last = TOKEN_RE.lastIndex;
  }
  if (last < src.length) tokens.push({ type: "text", start: last, end: src.length, text: src.slice(last) });
  return tokens;
}

/** Returns offset -> 1-based line number. */
export function lineIndex(src) {
  const starts = [0];
  for (let i = 0; i < src.length; i++) if (src[i] === "\n") starts.push(i + 1);
  return (offset) => {
    let lo = 0, hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= offset) lo = mid; else hi = mid - 1;
    }
    return lo + 1;
  };
}

const ENTITIES = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", mdash: "—", ndash: "–", hellip: "…",
  rsquo: "’", lsquo: "‘", ldquo: "“", rdquo: "”", rarr: "→", larr: "←", harr: "↔", times: "×",
  middot: "·", le: "≤", ge: "≥", minus: "−", copy: "©",
};
export function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (all, e) => {
    if (e[0] === "#") {
      const cp = e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(cp) ? String.fromCodePoint(cp) : all;
    }
    return ENTITIES[e.toLowerCase()] ?? all;
  });
}

export function hasClass(tok, cls) {
  return (tok.attrs?.class || "").split(/\s+/).includes(cls);
}

// ---------------------------------------------------------------------------------------------
// Sidebar

const ASIDE_RE = /<aside class="sidebar">[\s\S]*?<\/aside>/;

export function extractSidebar(src) {
  const m = ASIDE_RE.exec(src);
  return m ? { html: m[0], start: m.index, end: m.index + m[0].length } : null;
}

/** Prefixes every relative href/src (not scheme:, #anchor, /absolute) with `prefix`. */
export function prefixRelative(html, prefix) {
  if (!prefix) return html;
  return html.replace(/\b(href|src)="(?![a-z][a-z0-9+.-]*:|#|\/)/gi, `$1="${prefix}`);
}

// ---------------------------------------------------------------------------------------------
// Headings / slugs

export function slugify(text) {
  const full = decodeEntities(text.replace(/<[^>]*>/g, ""))
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  if (full.length <= 60) return full || "section";
  // Cut at a word boundary so long headings don't end in half a word.
  const cut = full.slice(0, 61);
  return cut.slice(0, cut.lastIndexOf("-")) || full.slice(0, 60);
}

// ---------------------------------------------------------------------------------------------
// Search index

// Inline elements: no word break inserted around them when extracting text.
const INLINE = new Set(["a", "code", "strong", "em", "b", "i", "span", "mark", "small", "sub", "sup", "kbd", "abbr", "q", "s", "u"]);
// Elements whose text never goes into the index.
const SKIP = (tok) =>
  tok.name === "script" || tok.name === "style" || tok.name === "footer" ||
  (tok.name === "pre" && hasClass(tok, "mermaid")) ||
  (tok.name === "div" && hasClass(tok, "breadcrumb"));

const norm = (s) => s.replace(/\s+/g, " ").trim();

/** {u, t, s: [{i, h, x}]} for one page. Section 0 is the text before the first h2/h3 (i = ""). */
export function extractSearchEntry(page, src) {
  const tokens = tokenize(src);
  const mainIdx = tokens.findIndex((t) => t.type === "open" && t.name === "main");
  let title = "";
  const sections = [{ i: "", h: "", x: "" }];
  let cur = sections[0];
  let skipDepth = 0, skipName = null;
  let inHeading = null; // {level, buf}
  let inH1 = false;
  const parts = [];
  const flush = () => { cur.x = norm(cur.x + " " + parts.splice(0).join("")); };
  for (let k = mainIdx + 1; k < tokens.length; k++) {
    const t = tokens[k];
    if (t.type === "close" && t.name === "main") break;
    if (skipName) {
      if (t.type === "open" && t.name === skipName && !VOID.has(t.name)) skipDepth++;
      else if (t.type === "close" && t.name === skipName && --skipDepth === 0) skipName = null;
      continue;
    }
    if (t.type === "open" && SKIP(t)) { skipName = t.name; skipDepth = 1; continue; }
    if (t.type === "open" && t.name === "h1") { inH1 = true; continue; }
    if (t.type === "close" && t.name === "h1") { inH1 = false; continue; }
    if (t.type === "open" && (t.name === "h2" || t.name === "h3")) {
      flush();
      cur = { i: t.attrs.id || "", h: "", x: "" };
      sections.push(cur);
      inHeading = cur;
      continue;
    }
    if (t.type === "close" && (t.name === "h2" || t.name === "h3")) { if (inHeading) inHeading.h = norm(inHeading.h); inHeading = null; continue; }
    if (t.type === "text") {
      const text = decodeEntities(t.text);
      if (inH1) title += text;
      else if (inHeading) inHeading.h += text;
      else parts.push(text);
      continue;
    }
    if ((t.type === "open" || t.type === "close") && !INLINE.has(t.name)) {
      if (inHeading) inHeading.h += " "; else if (!inH1) parts.push(" ");
    }
  }
  flush();
  return { u: page.rel, t: norm(title), s: sections.filter((s) => s.h || s.x) };
}

export function buildSearchIndex() {
  const entries = [];
  for (const page of listPages()) {
    const src = readPage(page);
    if (src != null) entries.push(extractSearchEntry(page, src));
  }
  return (
    "// GENERATED by docs/dev-docs/tools/build-search-index.mjs. Do not edit by hand:\n" +
    "// run `node docs/dev-docs/tools/build-search-index.mjs` after changing any page.\n" +
    "// A script (not JSON) so the search works over file://, where fetch() is blocked.\n" +
    "window.DOCS_SEARCH_INDEX = [\n" +
    // Non-ASCII as \uXXXX so the file is charset-independent.
    entries.map((e) => JSON.stringify(e).replace(/[\u007f-\uffff]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"))).join(",\n") +
    "\n];\n"
  );
}
