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
});
