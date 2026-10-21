"use strict";
window.I18n = (() => {
  const storageKey = "gugugu.language";
  const languages = ["en", "zh", "ja"];
  let catalog = {}, reverse = new Map(), preference = "auto", language = "en";
  try { preference = localStorage.getItem(storageKey) || "auto"; } catch { /* Storage can be disabled. */ }
  function browserLanguage() {
    for (const tag of navigator.languages || [navigator.language]) {
      const candidate = String(tag).toLowerCase().split(/[-_]/)[0];
      if (languages.includes(candidate)) return candidate;
    }
    return "en";
  }
  function t(key) { return catalog[language]?.[key] ?? catalog.en?.[key] ?? key; }
  function assign(element, message, attribute = "textContent") {
    const key = reverse.get(message);
    const binding = attribute === "textContent" ? "data-i18n" : "data-i18n-" + attribute;
    if (key) element.setAttribute(binding, key); else element.removeAttribute(binding);
    if (attribute === "textContent") element.textContent = key ? t(key) : message;
    else element.setAttribute(attribute, key ? t(key) : message);
  }
  function render() {
    document.documentElement.lang = language === "zh" ? "zh-CN" : language;
    for (const attribute of ["textContent", "placeholder", "aria-label", "alt"]) {
      const binding = attribute === "textContent" ? "data-i18n" : "data-i18n-" + attribute;
      for (const element of document.querySelectorAll("[" + binding + "]")) {
        const text = t(element.getAttribute(binding));
        if (attribute === "textContent") element.textContent = text; else element.setAttribute(attribute, text);
      }
    }
    const badge = document.getElementById("language-button");
    if (badge) {
      const option = document.querySelector('[data-language="' + language + '"]');
      badge.replaceChildren(...Array.from(option.childNodes, node => node.cloneNode(true)));
    }
    for (const button of document.querySelectorAll("[data-language]")) {
      const selected = button.dataset.language === preference;
      button.setAttribute("aria-pressed", String(selected));
      button.classList.toggle("selected", selected);
    }
  }
  function select(value, persist = true) {
    preference = languages.includes(value) ? value : "auto";
    language = preference === "auto" ? browserLanguage() : preference;
    if (persist) try { localStorage.setItem(storageKey, preference); } catch { /* Selection still works for this page. */ }
    render();
    document.dispatchEvent(new CustomEvent("gugugu:language", {detail: {language}}));
  }
  const api = {t, assign, select, get language() { return language; }};
  api.ready = fetch("/static/locales.json", {cache: "no-cache"}).then(response => {
    if (!response.ok) throw new Error("Language resources unavailable");
    return response.json();
  }).then(data => {
    catalog = data;
    for (const values of Object.values(catalog)) for (const [key, text] of Object.entries(values)) reverse.set(text, key);
    reverse.set("invalid request", "invalid_request");
    select(preference, false);
    for (const button of document.querySelectorAll("[data-language]")) button.addEventListener("click", () => {
      select(button.dataset.language);
      button.closest("details").open = false;
    });
    window.addEventListener("languagechange", () => { if (preference === "auto") select("auto", false); });
    window.addEventListener("storage", event => { if (event.key === storageKey) select(event.newValue || "auto", false); });
    document.addEventListener("click", event => {
      for (const menu of document.querySelectorAll(".locale-switch[open]")) if (!menu.contains(event.target)) menu.open = false;
    });
    document.addEventListener("keydown", event => {
      if (event.key === "Escape") for (const menu of document.querySelectorAll(".locale-switch[open]")) menu.open = false;
    });
    return api;
  });
  return api;
})();
