// oml.sh — generated documentation site. Static output to _site, deployed to
// GitHub Pages by .github/workflows/pages.yml on push to main.

// Strips markup and slugifies heading text into a stable, URL-safe anchor id.
function slugify(text) {
  return text
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9\s-]/g, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");
}

// Assigns an id to every h2/h3 in rendered doc HTML (skipping any that already
// have one) and returns the updated HTML alongside a flat table of contents
// used to render the right-hand "On this page" navigation.
function addHeadingAnchors(html) {
  const seen = new Map();
  const toc = [];
  const withIds = html.replace(
    /<h([23])((?:\s[^>]*)?)>([\s\S]*?)<\/h\1>/g,
    (match, level, attrs, inner) => {
      const text = inner.replace(/<[^>]*>/g, "").trim();
      let slug = slugify(text) || "section";
      const count = seen.get(slug) ?? 0;
      seen.set(slug, count + 1);
      if (count > 0) slug = `${slug}-${count}`;

      const finalAttrs = /\sid=/.test(attrs) ? attrs : ` id="${slug}"${attrs}`;
      toc.push({ level: Number(level), text, slug });
      return `<h${level}${finalAttrs}>${inner}</h${level}>`;
    },
  );
  return { html: withIds, toc };
}

export default function (eleventyConfig) {
  eleventyConfig.addPassthroughCopy("src/style.css"); // -> /style.css
  eleventyConfig.addPassthroughCopy("src/docs.js");   // -> /docs.js
  eleventyConfig.addPassthroughCopy("src/logo.svg");  // -> /logo.svg
  eleventyConfig.addPassthroughCopy("src/favicon.svg"); // -> /favicon.svg
  eleventyConfig.addPassthroughCopy("src/CNAME");     // GitHub Pages custom domain

  // Documentation sidebar navigation: doc pages tagged "docs" (see
  // src/docs/docs.json), ordered by front-matter `order`.
  eleventyConfig.addCollection("docsNav", (collectionApi) =>
    collectionApi
      .getFilteredByTag("docs")
      .sort((a, b) => (a.data.order ?? 0) - (b.data.order ?? 0)),
  );

  // Anchors every doc page's h2/h3 headings and returns { html, toc } so
  // docs.njk can render a right-hand "On this page" table of contents.
  eleventyConfig.addFilter("docsToc", (html) => addHeadingAnchors(html));

  return {
    dir: { input: "src", includes: "_includes", output: "_site" },
    htmlTemplateEngine: "njk",
    markdownTemplateEngine: "njk",
  };
}
