import { attachSkillAutocomplete } from "./autocomplete.js";

// Session-scoped, tab-local drafts survive navigation and explicit reloads.
class IrisChatComposer extends HTMLElement {
  static observedAttributes = ["data-session-id"];
  #cleanup;
  #revision = 0;
  connectedCallback() { this.#attach(); }
  attributeChangedCallback() { if (this.isConnected) this.#attach(); }
  disconnectedCallback() { this.#revision++; this.#cleanup?.(); this.#cleanup = null; }
  #attach() {
    const revision = ++this.#revision;
    this.#cleanup?.();
    this.#cleanup = null;
    queueMicrotask(() => {
      if (!this.isConnected || revision !== this.#revision) return;
      const form = this.querySelector("form"), input = form?.querySelector("textarea");
      if (!input) return;
      const key = `iris:draft:${this.dataset.sessionId}`;
      const lifetime = new AbortController();
      const save = () => { try {
        if (input.value) sessionStorage.setItem(key, input.value);
        else sessionStorage.removeItem(key);
      } catch (_) { /* Storage may be disabled; live composer still works. */ } };
      try { input.value = sessionStorage.getItem(key) || ""; } catch (_) {}
      const cleanupAutocomplete = attachSkillAutocomplete(form);
      input.addEventListener("input", save, { signal: lifetime.signal });
      form.addEventListener("reset", () => queueMicrotask(save), { signal: lifetime.signal });
      window.addEventListener("pagehide", save, { signal: lifetime.signal });
      this.#cleanup = () => { save(); lifetime.abort(); cleanupAutocomplete(); };
    });
  }
}
customElements.define("iris-chat-composer", IrisChatComposer);
