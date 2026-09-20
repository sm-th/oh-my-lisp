// oml.sh — generated documentation site. Static output to _site, deployed to
// GitHub Pages by .github/workflows/pages.yml on push to main.
export default function (eleventyConfig) {
  eleventyConfig.addPassthroughCopy("src/style.css"); // -> /style.css
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

  return {
    dir: { input: "src", includes: "_includes", output: "_site" },
    htmlTemplateEngine: "njk",
    markdownTemplateEngine: "njk",
  };
}
