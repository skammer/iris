const AUTOSCROLL_THRESHOLD_PX = 300;
const THEME_STORAGE_KEY = "clj-agent-theme";

const storedTheme = () => {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY);
  } catch (_error) {
    return null;
  }
};

const persistTheme = (theme) => {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch (_error) {
    return;
  }
};

const preferredTheme = () =>
  storedTheme()
  || (window.matchMedia?.("(prefers-color-scheme: light)").matches ? "light" : "dark");

const applyTheme = (theme) => {
  document.documentElement.dataset.theme = theme === "light" ? "light" : "dark";
};

applyTheme(preferredTheme());

class ThemeToggle extends HTMLElement {
  connectedCallback() {
    this.button?.addEventListener("click", this);
    this.render();
  }

  disconnectedCallback() {
    this.button?.removeEventListener("click", this);
  }

  get button() {
    return this.querySelector("button");
  }

  handleEvent() {
    const next = document.documentElement.dataset.theme === "light" ? "dark" : "light";
    persistTheme(next);
    applyTheme(next);
    this.render();
  }

  render() {
    const button = this.button;
    if (!(button instanceof HTMLButtonElement)) return;
    const light = document.documentElement.dataset.theme === "light";
    button.textContent = light ? "Light" : "Dark";
    button.setAttribute("aria-pressed", String(light));
  }
}

class AutoGrowTextarea extends HTMLElement {
  #form = null;

  connectedCallback() {
    this.style.display = "contents";
    this.textarea?.addEventListener("input", this);
    this.textarea?.addEventListener("keydown", this);
    this.#form = this.closest("form");
    this.#form?.addEventListener("submit", this);
    requestAnimationFrame(() => this.grow());
  }

  disconnectedCallback() {
    this.textarea?.removeEventListener("input", this);
    this.textarea?.removeEventListener("keydown", this);
    this.#form?.removeEventListener("submit", this);
    this.#form = null;
  }

  get textarea() {
    const node = this.querySelector("textarea");
    return node instanceof HTMLTextAreaElement ? node : null;
  }

  handleEvent(event) {
    if (event.type === "input") {
      this.grow();
      return;
    }
    if (event.type === "submit") {
      // Defer so other submit listeners (e.g. Datastar) read FormData first.
      queueMicrotask(() => {
        const textarea = this.textarea;
        if (!textarea) return;
        textarea.value = "";
        this.grow();
      });
      return;
    }
    if (
      event instanceof KeyboardEvent
      && this.hasAttribute("submit-on-enter")
      && event.key === "Enter"
      && !event.shiftKey
      && !event.isComposing
    ) {
      event.preventDefault();
      this.closest("form")?.requestSubmit();
    }
  }

  grow() {
    const textarea = this.textarea;
    if (!textarea) return;
    textarea.style.height = "auto";
    const maxHeight = parseFloat(getComputedStyle(textarea).maxHeight);
    const next = Number.isFinite(maxHeight)
      ? Math.min(textarea.scrollHeight, maxHeight)
      : textarea.scrollHeight;
    textarea.style.height = `${next}px`;
  }
}

class ScrollBottom extends HTMLElement {
  #stick = true;
  #target = null;
  #observer = new MutationObserver(() => this.afterChange());

  connectedCallback() {
    this.bindTarget();
    this.#observer.observe(this, { childList: true, subtree: true, characterData: true });
    requestAnimationFrame(() => this.scrollToBottom(true));
  }

  disconnectedCallback() {
    this.#target?.removeEventListener("scroll", this);
    this.#target = null;
    this.#observer.disconnect();
  }

  get target() {
    const node = this.querySelector("[data-run-output-tail]");
    return node instanceof HTMLElement ? node : this;
  }

  bindTarget() {
    const next = this.target;
    if (next === this.#target) return;
    this.#target?.removeEventListener("scroll", this);
    this.#target = next;
    this.#target.addEventListener("scroll", this, { passive: true });
  }

  handleEvent() {
    this.#stick = this.distanceFromBottom() <= AUTOSCROLL_THRESHOLD_PX;
  }

  distanceFromBottom() {
    const target = this.target;
    return target.scrollHeight - target.clientHeight - target.scrollTop;
  }

  afterChange() {
    this.bindTarget();
    if (this.#stick) requestAnimationFrame(() => this.scrollToBottom(false));
  }

  scrollToBottom(force) {
    if (!force && !this.#stick) return;
    const target = this.target;
    target.scrollTop = target.scrollHeight;
  }
}

class AgentChatPanel extends HTMLElement {}

class ChatStream extends HTMLElement {
  #stick = true;
  #observer = new MutationObserver(() => this.afterChange());

  connectedCallback() {
    this.addEventListener("scroll", this, { passive: true });
    this.#observer.observe(this, { childList: true, subtree: true, characterData: true });
    requestAnimationFrame(() => this.scrollToBottom(true));
  }

  disconnectedCallback() {
    this.removeEventListener("scroll", this);
    this.#observer.disconnect();
  }

  handleEvent() {
    this.#stick = this.scrollHeight - this.clientHeight - this.scrollTop <= AUTOSCROLL_THRESHOLD_PX;
  }

  afterChange() {
    if (this.#stick) requestAnimationFrame(() => this.scrollToBottom(false));
  }

  scrollToBottom(force) {
    if (!force && !this.#stick) return;
    this.scrollTop = this.scrollHeight;
  }
}

class AgentRunPanel extends ScrollBottom {
  connectedCallback() {
    super.connectedCallback();
    this.renderLiveState();
  }

  renderLiveState() {
    const node = this.querySelector("[data-run-live-state]");
    if (!(node instanceof HTMLElement)) return;
    const state = this.dataset.liveState || "poll";
    node.textContent = state;
    node.className = `run-live-state ${state}`;
  }
}

if (!customElements.get("theme-toggle")) customElements.define("theme-toggle", ThemeToggle);
if (!customElements.get("auto-grow-textarea")) customElements.define("auto-grow-textarea", AutoGrowTextarea);
if (!customElements.get("scroll-bottom")) customElements.define("scroll-bottom", ScrollBottom);
if (!customElements.get("chat-stream")) customElements.define("chat-stream", ChatStream);
if (!customElements.get("agent-chat-panel")) customElements.define("agent-chat-panel", AgentChatPanel);
if (!customElements.get("agent-run-panel")) customElements.define("agent-run-panel", AgentRunPanel);
