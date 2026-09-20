// Documentation pages: persisted light/dark toggle and "On this page" scrollspy.
// The initial theme (localStorage, defaulting to dark) is
// applied synchronously in base.njk to avoid a flash of the wrong theme; this
// script only handles the toggle interaction and the TOC active-section state.
(function () {
  const root = document.documentElement;
  const STORAGE_KEY = "oml-docs-theme";

  const toggle = document.querySelector("[data-docs-theme-toggle]");
  if (toggle) {
    toggle.addEventListener("click", () => {
      const next = root.getAttribute("data-docs-theme") === "dark" ? "light" : "dark";
      root.setAttribute("data-docs-theme", next);
      try {
        localStorage.setItem(STORAGE_KEY, next);
      } catch (err) {
        // Storage disabled (private browsing, blocked cookies, etc.) — the
        // toggle still works for the current page view.
      }
    });
  }

  const tocLinks = Array.from(document.querySelectorAll(".docs-toc a"));
  if (!tocLinks.length || !("IntersectionObserver" in window)) return;

  const targets = tocLinks
    .map((link) => document.getElementById(link.getAttribute("href").slice(1)))
    .filter(Boolean);

  const setActive = (id) => {
    for (const link of tocLinks) {
      link.classList.toggle("is-active", link.getAttribute("href") === `#${id}`);
    }
  };

  const observer = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      if (visible.length) setActive(visible[0].target.id);
    },
    { rootMargin: "0px 0px -70% 0px", threshold: 0 },
  );

  for (const target of targets) observer.observe(target);
})();
