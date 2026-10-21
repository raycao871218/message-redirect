"use strict";
window.addEventListener("DOMContentLoaded", async () => {
  await I18n.ready;
  let requestNumber = 0;
  const bindings = new WeakMap();
  const labels = new Map();
  const catalog = await fetch("/static/locales.json", {cache: "no-cache"}).then(response => response.json());
  for (const values of Object.values(catalog)) for (const [key, text] of Object.entries(values))
    if (key.startsWith("swagger_")) labels.set(text, key);
  const root = document.getElementById("swagger-ui");
  function controls() {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      if (node.parentElement.closest("code, pre, textarea, .highlight-code, .microlight, .model, .prop, .opblock-summary-path")) continue;
      const original = node.nodeValue.trim();
      const binding = bindings.get(node);
      const key = binding?.rendered === original ? binding.key : labels.get(original);
      if (!key) continue;
      const translated = I18n.t(key);
      bindings.set(node, {key, rendered: translated});
      if (original !== translated) node.nodeValue = translated;
    }
  }
  const observer = new MutationObserver(controls);
  observer.observe(root, {childList: true, subtree: true, characterData: true});
  async function load() {
    const current = ++requestNumber;
    try {
      const response = await fetch("/openapi.json?lang=" + I18n.language, {cache: "no-store"});
      if (!response.ok) throw new Error("Schema unavailable");
      const schema = await response.json();
      if (current !== requestNumber) return;
      if (window.ui) window.ui.specActions.updateJsonSpec(schema);
      else window.ui = SwaggerUIBundle({spec: schema, dom_id: "#swagger-ui", deepLinking: true,
        persistAuthorization: false, validatorUrl: null, displayRequestDuration: true,
        presets: [SwaggerUIBundle.presets.apis], layout: "BaseLayout"});
      controls();
    } catch {
      if (current === requestNumber && !window.ui) I18n.assign(root, I18n.t("docs_unavailable"));
    }
  }
  document.addEventListener("gugugu:language", () => { controls(); load(); });
  await load();
});
