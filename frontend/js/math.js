import { assetUrl } from "./assets.js";
const options = {
  delimiters: [
    { left: "$$", right: "$$", display: true },
    { left: "\\[", right: "\\]", display: true },
    { left: "$", right: "$", display: false },
    { left: "\\(", right: "\\)", display: false },
  ],
  ignoredTags: ["pre", "code", "script", "style", "textarea", "option"],
  throwOnError: false,
};
let loading;
const script = (src) => new Promise((resolve, reject) => {
  const el = document.createElement("script");
  el.src = assetUrl(src);
  el.onload = resolve;
  el.onerror = () => { el.remove(); reject(new Error("Math renderer unavailable")); };
  document.head.append(el);
});
const loadMath = () => loading ??= (async () => {
  if (!document.querySelector("link[data-iris-math]")) {
    const css = document.createElement("link");
    css.rel = "stylesheet"; css.href = assetUrl("/public/katex/katex.min.css");
    css.dataset.irisMath = "true";
    document.head.append(css);
  }
  if (!window.katex) await script("/public/katex/katex.min.js");
  if (!window.renderMathInElement) await script("/public/katex/auto-render.min.js");
})().catch((error) => { loading = null; throw error; });

class IrisMarkdown extends HTMLElement {
  #timer = null;
  #loading = false;
  #observer = new MutationObserver(() => this.#schedule());
  connectedCallback() { this.#observe(); this.#schedule(); }
  disconnectedCallback() { this.#observer.disconnect(); clearTimeout(this.#timer); this.#timer = null; }
  #observe() { this.#observer.observe(this, { childList: true, subtree: true, characterData: true }); }
  #schedule() {
    // Fixed deadline: a continuous stream cannot starve typesetting.
    if (this.#timer !== null || this.#loading) return;
    this.#timer = setTimeout(() => { this.#timer = null; this.#typeset(); }, 60);
  }
  async #typeset() {
    if (!this.isConnected || !/\$|\\[([]/.test(this.textContent)) return;
    this.#loading = true;
    try { await loadMath(); } catch (_) { return; } finally { this.#loading = false; }
    if (!this.isConnected) return;
    // Do not observe our own KaTeX mutations. Other messages are untouched.
    this.#observer.disconnect();
    window.renderMathInElement(this, options);
    this.#observe();
    this.dispatchEvent(new Event("iris-content-resized", { bubbles: true }));
  }
}
customElements.define("iris-markdown", IrisMarkdown);
document.addEventListener("click", (event) => {
  const spoiler = event.target instanceof Element && event.target.closest(".spoiler");
  if (spoiler) spoiler.classList.toggle("spoiler--revealed");
});
