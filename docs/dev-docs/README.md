# docs/dev-docs

Static HTML developer docs. Open `index.html` in a browser; no build step and no server (everything
works over `file://`). Tooling lives in `tools/` and uses only Node built-ins (Node 18+, no `npm install`).

## Layout

```
index.html               root page; its sidebar is the master copy
<slug>/index.html        one page per directory (every directory except assets/ and tools/ is a page)
assets/style.css         the only stylesheet (light + dark via prefers-color-scheme)
assets/docs.js           Mermaid init, active sidebar link, search
assets/search-index.js   GENERATED search index; never edit by hand
assets/mermaid.min.js    vendored Mermaid
tools/                   checker and generators (below)
```

## Adding a page

1. `mkdir docs/dev-docs/<slug>` and copy `outbox/index.html` into it as the skeleton. Change `<title>`,
   the breadcrumb, `<h1>` and `.subtitle`. Keep the `<head>`, the `<aside class="sidebar">` and the
   three `<script>` tags at the end of `<body>`. Use only classes from `assets/style.css`.
2. Add the link to the sidebar in **`index.html` only**, then copy it to every page:
   `node docs/dev-docs/tools/sync-sidebar.mjs`. The checker fails if any page's sidebar differs from
   the root's or if a page directory is missing from it.
3. Give headings ids: `node docs/dev-docs/tools/add-heading-ids.mjs`. It only adds missing ids and
   never renames an existing one, because other pages and the search index link to them.
4. Add the footer stamp (below).
5. `node docs/dev-docs/tools/build-search-index.mjs`, then run the checker.

## Commands

All from the repo root:

| Command | What it does |
|---|---|
| `node docs/dev-docs/tools/check-docs.mjs` | Static checks: balanced tags, relative `href`/`src` and `#anchor` targets, identical sidebars, h2/h3 ids, Mermaid lint, footer stamp, stale search index. |
| `node docs/dev-docs/tools/check-docs.mjs --render` | The above, plus every page rendered in headless Chrome. Fails on Mermaid "Syntax error" or an unrendered `pre.mermaid`. Chrome comes from `$CHROME_BIN` or the usual install paths. CI runs this (`.github/workflows/docs-check.yml`) on PRs that touch `docs/dev-docs/**`. |
| `node docs/dev-docs/tools/build-search-index.mjs` | Regenerates `assets/search-index.js`. Run it after any page edit, because the checker compares it with a fresh build. |
| `node docs/dev-docs/tools/sync-sidebar.mjs` | Rewrites every page's sidebar from the root page's, and adds the `search-index.js` script tag where missing. |
| `node docs/dev-docs/tools/add-heading-ids.mjs` | Adds slug ids to h2/h3 headings that lack one. |

Problems print as `file:line: reason`, and the exit code is non-zero if any are found.

## Freshness stamp

Every page ends with exactly one footer:

```html
<footer class="page-footer">Last verified against master <code>e8239b0e</code> (2026-10-28).</footer>
```

The SHA is the short master commit you checked the page's claims against, and the date is when you
did it. Update it whenever you re-verify the page, not for typo fixes. The checker only checks that
the stamp is well-formed. A reader uses the stamp to judge how far the page may have drifted:
`git log <sha>..master -- <paths the page describes>`.

## Mermaid gotchas

- Diagrams go in `<pre class="mermaid">`. The content is HTML text, so escape `<`, `>` and `&`.
  The one exception is a line break inside a label, written as a raw `<br/>`.
- Never put `;` in a label or message. Mermaid treats it as a statement separator, so it cuts the
  label short and usually makes the rest of the line a syntax error. Reword it, or use `#59;`.
- No generic types or tags in labels (`List<Foo>`): with `securityLevel: "strict"` Mermaid strips
  them as HTML. Write `List of Foo`.
- Quote labels that contain `()`, `[]`, `{}`, `:` or `|`: `A["save() then flush()"]`.
- The static lint catches the common cases, and `--render` catches everything else, so run it.
