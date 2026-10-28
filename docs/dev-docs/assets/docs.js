(function () {
  // Docs root = the directory above assets/. Resolved from this script's own URL so links work
  // from any page depth and over file://.
  var self = document.currentScript;
  var docsRoot = self ? new URL("../", self.src) : new URL("./", window.location.href);

  document.addEventListener("DOMContentLoaded", function () {
    if (window.mermaid) {
      mermaid.initialize({
        startOnLoad: true,
        theme: window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "default",
        securityLevel: "strict",
      });
    }

    var here = window.location.pathname.replace(/\/index\.html$/, "/");
    document.querySelectorAll(".sidebar nav a").forEach(function (a) {
      var href = a.getAttribute("href");
      if (!href) return;
      var resolved = new URL(href, window.location.href).pathname.replace(/\/index\.html$/, "/");
      if (resolved === here) a.classList.add("active");
    });

    initSearch();
  });

  // ---------------------------------------------------------------------------------------------
  // Search over window.DOCS_SEARCH_INDEX (assets/search-index.js, built by
  // tools/build-search-index.mjs). Entry: {u: page path, t: title, s: [{i: heading id, h: heading, x: text}]}.

  var MAX_RESULTS = 10;
  var MAX_PER_PAGE = 4;
  var prepared = null;

  function prepare() {
    if (prepared) return prepared;
    prepared = [];
    (window.DOCS_SEARCH_INDEX || []).forEach(function (page) {
      page.s.forEach(function (sec, n) {
        prepared.push({
          page: page,
          sec: sec,
          first: n === 0 && !sec.i,
          tl: page.t.toLowerCase(),
          hl: sec.h.toLowerCase(),
          xl: sec.x.toLowerCase(),
        });
      });
    });
    return prepared;
  }

  function terms(q) {
    return q.toLowerCase().split(/\s+/).filter(Boolean);
  }

  function count(hay, needle) {
    var n = 0;
    for (var at = hay.indexOf(needle); at >= 0 && n < 5; at = hay.indexOf(needle, at + needle.length)) n++;
    return n;
  }

  // Every term must occur in the page title, the section heading or the section text.
  // Ranking: title > heading > body. The title bonus is full only for a page's top section, so a
  // title hit ranks the page itself first instead of flooding results with all its sections.
  function search(q) {
    var ts = terms(q);
    if (!ts.length) return [];
    var hits = [];
    prepare().forEach(function (e) {
      var score = 0;
      for (var k = 0; k < ts.length; k++) {
        var t = ts[k];
        var inT = e.tl.indexOf(t) >= 0;
        var inH = e.hl.indexOf(t) >= 0;
        var c = count(e.xl, t);
        if (!inT && !inH && !c) return;
        if (inT) score += e.first ? 100 : 4;
        if (inH) score += 30;
        score += c;
      }
      if (e.first && ts.join(" ") === e.tl) score += 100;
      if (e.hl === ts.join(" ")) score += 30;
      hits.push({ e: e, score: score });
    });
    hits.sort(function (a, b) {
      return b.score - a.score || a.e.page.u.localeCompare(b.e.page.u);
    });
    var perPage = {};
    var out = [];
    for (var i = 0; i < hits.length && out.length < MAX_RESULTS; i++) {
      var u = hits[i].e.page.u;
      perPage[u] = (perPage[u] || 0) + 1;
      if (perPage[u] <= MAX_PER_PAGE) out.push(hits[i].e);
    }
    return out.map(function (e) {
      return {
        href: new URL(e.page.u + (e.sec.i ? "#" + e.sec.i : ""), docsRoot).href,
        title: e.page.t,
        heading: e.sec.h,
        snippet: snippet(e, ts),
      };
    });
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  function escapeRe(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function highlight(text, ts) {
    var re = new RegExp("(" + ts.map(escapeRe).join("|") + ")", "gi");
    return text
      .split(re)
      .map(function (part, n) {
        return n % 2 ? "<mark>" + escapeHtml(part) + "</mark>" : escapeHtml(part);
      })
      .join("");
  }

  function snippet(e, ts) {
    var x = e.sec.x;
    var at = -1;
    ts.forEach(function (t) {
      var p = e.xl.indexOf(t);
      if (p >= 0 && (at < 0 || p < at)) at = p;
    });
    var start = Math.max(0, at - 50);
    if (start > 0) {
      var sp = x.indexOf(" ", start);
      if (sp >= 0 && sp < at) start = sp + 1;
    }
    var end = Math.min(x.length, start + 150);
    if (end < x.length) {
      var sp2 = x.lastIndexOf(" ", end);
      if (sp2 > start) end = sp2;
    }
    var text = (start > 0 ? "… " : "") + x.slice(start, end) + (end < x.length ? " …" : "");
    return highlight(text, ts);
  }

  function initSearch() {
    var input = document.getElementById("docs-search");
    var list = document.getElementById("docs-search-results");
    if (!input || !list) return;
    if (!window.DOCS_SEARCH_INDEX) {
      input.disabled = true;
      input.placeholder = "Search index not loaded";
      return;
    }
    var results = [];
    var active = -1;

    function setActive(n) {
      var items = list.querySelectorAll("li[role=option]");
      if (!items.length) return;
      active = (n + items.length) % items.length;
      items.forEach(function (li, k) {
        li.classList.toggle("active", k === active);
        li.setAttribute("aria-selected", k === active ? "true" : "false");
      });
      input.setAttribute("aria-activedescendant", items[active].id);
      items[active].scrollIntoView({ block: "nearest" });
    }

    function close() {
      list.hidden = true;
      list.innerHTML = "";
      results = [];
      active = -1;
      input.setAttribute("aria-expanded", "false");
      input.removeAttribute("aria-activedescendant");
    }

    function render() {
      var q = input.value.trim();
      if (!q) return close();
      results = search(q);
      active = -1;
      input.removeAttribute("aria-activedescendant");
      if (!results.length) {
        list.innerHTML = '<li class="search-empty">No results</li>';
      } else {
        list.innerHTML = results
          .map(function (r, k) {
            var label = escapeHtml(r.title) + (r.heading ? ' <span class="search-sep">›</span> ' + escapeHtml(r.heading) : "");
            return (
              '<li role="option" id="docs-search-opt-' + k + '" aria-selected="false">' +
              '<a href="' + escapeHtml(r.href) + '" tabindex="-1">' +
              '<span class="search-title">' + label + "</span>" +
              (r.snippet ? '<span class="search-snippet">' + r.snippet + "</span>" : "") +
              "</a></li>"
            );
          })
          .join("");
      }
      list.hidden = false;
      input.setAttribute("aria-expanded", "true");
    }

    input.addEventListener("input", render);
    input.addEventListener("focus", function () {
      if (input.value.trim() && list.hidden) render();
    });
    input.addEventListener("keydown", function (ev) {
      if (ev.key === "ArrowDown") {
        ev.preventDefault();
        if (list.hidden) render();
        setActive(active + 1);
      } else if (ev.key === "ArrowUp") {
        ev.preventDefault();
        setActive(active - 1);
      } else if (ev.key === "Enter") {
        if (!results.length) return;
        ev.preventDefault();
        window.location.href = results[Math.max(active, 0)].href;
      } else if (ev.key === "Escape") {
        ev.preventDefault();
        input.value = "";
        close();
      }
    });
    list.addEventListener("mousedown", function (ev) {
      // Keep focus in the input so the blur handler doesn't close the list before the click lands.
      if (ev.target.closest("a")) ev.preventDefault();
    });
    input.addEventListener("blur", function () {
      setTimeout(function () {
        if (document.activeElement !== input) {
          list.hidden = true;
          input.setAttribute("aria-expanded", "false");
        }
      }, 150);
    });
    document.addEventListener("keydown", function (ev) {
      if (ev.key !== "/" || ev.ctrlKey || ev.metaKey || ev.altKey) return;
      var t = ev.target;
      if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
      ev.preventDefault();
      input.focus();
      input.select();
    });
  }

  window.DocsSearch = { search: search };
})();
