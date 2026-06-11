const AUTOSCROLL_THRESHOLD_PX = 300;
const THEME_STORAGE_KEY = "iris-theme";

const syncRoute = (path, replace = false) => {
  if (!path || window.location.pathname === path) return;
  const method = replace ? "replaceState" : "pushState";
  window.history[method]({ irisRoute: path }, "", path);
};

const routerStatePath = () =>
  document.getElementById("router-state")?.dataset.routePath || "";

let routerInitialized = false;

const syncRouterState = () => {
  const path = routerStatePath();
  if (!path) return;
  syncRoute(path, !routerInitialized);
  routerInitialized = true;
};

const routerObserver = new MutationObserver(() => syncRouterState());

const closeToolDetail = () => {
  const sidebar = document.getElementById("tool-detail-sidebar");
  if (!(sidebar instanceof HTMLElement)) return;
  sidebar.hidden = true;
  sidebar.removeAttribute("data-open");
};

const openToolDetail = (trigger) => {
  const templateId = trigger.dataset.toolDetailTemplate;
  const template = templateId ? document.getElementById(templateId) : null;
  const sidebar = document.getElementById("tool-detail-sidebar");
  const title = document.getElementById("tool-detail-sidebar-title");
  const status = document.getElementById("tool-detail-sidebar-status");
  const body = document.getElementById("tool-detail-sidebar-body");
  if (
    !(template instanceof HTMLTemplateElement)
    || !(sidebar instanceof HTMLElement)
    || !(title instanceof HTMLElement)
    || !(status instanceof HTMLElement)
    || !(body instanceof HTMLElement)
  ) return;
  title.textContent = trigger.dataset.toolDetailTitle || "Tool detail";
  status.textContent = trigger.dataset.toolDetailStatus || "";
  body.replaceChildren(template.content.cloneNode(true));
  sidebar.hidden = false;
  sidebar.dataset.open = "true";
};

document.addEventListener("click", (event) => {
  const target = event.target;
  if (!(target instanceof Element)) return;

  const close = target.closest("[data-tool-detail-close]");
  if (close) {
    closeToolDetail();
    return;
  }

  const detail = target.closest("[data-tool-detail]");
  if (detail instanceof HTMLElement) {
    openToolDetail(detail);
    return;
  }

  const route = target.closest("[data-route]");
  if (route instanceof HTMLElement) {
    const path = route.dataset.route;
    if (path) syncRoute(path);
  }
}, true);

window.addEventListener("popstate", () => {
  window.location.reload();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeToolDetail();
});

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
    textarea.style.overflowY =
      Number.isFinite(maxHeight) && textarea.scrollHeight > maxHeight
        ? "auto"
        : "hidden";
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
  #streaming = false;
  #observer = new MutationObserver(() => this.afterChange());

  connectedCallback() {
    this.addEventListener("scroll", this, { passive: true });
    this.#streaming = this.isStreaming();
    this.#observer.observe(this, { childList: true, subtree: true, characterData: true });
    requestAnimationFrame(() => this.scrollToAnchor());
  }

  disconnectedCallback() {
    this.removeEventListener("scroll", this);
    this.#observer.disconnect();
  }

  handleEvent() {
    this.#stick = this.scrollHeight - this.clientHeight - this.scrollTop <= AUTOSCROLL_THRESHOLD_PX;
  }

  isStreaming() {
    return this.querySelector(".message--streaming") !== null;
  }

  afterChange() {
    const streaming = this.isStreaming();
    const started = !this.#streaming && streaming;
    const completed = this.#streaming && !streaming;
    this.#streaming = streaming;
    if (this.#stick && (started || completed)) {
      requestAnimationFrame(() => this.scrollToAnchor());
    }
  }

  scrollToAnchor() {
    const anchor = this.querySelector(".chat-stream__bottom-anchor");
    if (anchor instanceof HTMLElement) {
      anchor.scrollIntoView({ block: "end" });
    }
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

const inMarkdownFenceBeforeCaret = (text, caret) => {
  const head = text.slice(0, caret);
  let inFence = false;
  for (const line of head.split(/\r?\n/)) {
    if (line.replace(/^[\t ]+/, "").startsWith("```")) inFence = !inFence;
  }
  return inFence;
};

const slashDraftAtCaret = (text, caret) => {
  if (caret < 0 || caret > text.length || inMarkdownFenceBeforeCaret(text, caret)) {
    return null;
  }
  const lineStart = text.lastIndexOf("\n", caret - 1) + 1;
  const lineEndAt = text.indexOf("\n", caret);
  const lineEnd = lineEndAt < 0 ? text.length : lineEndAt;
  const line = text.slice(lineStart, lineEnd);
  if (/^[\t ]*>/.test(line)) return null;
  const beforeCaret = line.slice(0, caret - lineStart);
  for (let i = beforeCaret.length - 1; i >= 0; i--) {
    if (beforeCaret[i] !== "/") continue;
    const after = beforeCaret.slice(i + 1);
    if (!/^[A-Za-z0-9_-]*$/.test(after)) continue;
    if (i > 0 && !/[\t ]/.test(beforeCaret[i - 1])) continue;
    return { from: lineStart + i, to: caret, prefix: after };
  }
  return null;
};

const attachSkillAutocomplete = (form) => {
  if (!(form instanceof HTMLFormElement) || form.dataset.skillAutocompleteReady === "true") return;
  const textarea = form.querySelector("textarea[data-skill-input]");
  if (!(textarea instanceof HTMLTextAreaElement)) return;
  form.dataset.skillAutocompleteReady = "true";
  const menu = document.createElement("div");
  menu.className = "skill-menu";
  menu.hidden = true;
  form.append(menu);
  let replaceRange = null;
  let fetchGeneration = 0;

  const close = () => {
    menu.hidden = true;
    replaceRange = null;
  };

  const choose = (row) => {
    if (!replaceRange) return;
    const value = textarea.value;
    const next = `${value.slice(0, replaceRange.from)}/${row.name} ${value.slice(replaceRange.to)}`;
    const caret = replaceRange.from + row.name.length + 2;
    textarea.value = next;
    textarea.setSelectionRange(caret, caret);
    textarea.dispatchEvent(new Event("input", { bubbles: true }));
    textarea.focus();
    close();
  };

  const render = (items) => {
    menu.replaceChildren();
    if (!items.length) {
      close();
      return;
    }
    for (const row of items) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "skill-menu__row";
      button.innerHTML = `<span class="skill-menu__name"></span><span class="skill-menu__desc"></span>`;
      button.querySelector(".skill-menu__name").textContent = `/${row.name}`;
      button.querySelector(".skill-menu__desc").textContent = row.description || "";
      button.addEventListener("mousedown", (event) => {
        event.preventDefault();
        choose(row);
      });
      menu.append(button);
    }
    menu.hidden = false;
  };

  const refresh = async () => {
    const draft = slashDraftAtCaret(textarea.value, textarea.selectionStart ?? 0);
    if (!draft) {
      close();
      return;
    }
    replaceRange = draft;
    const generation = ++fetchGeneration;
    const params = new URLSearchParams({
      prefix: draft.prefix,
      page: "1",
      page_size: "20",
    });
    try {
      const response = await fetch(`/v1/slash-commands?${params.toString()}`, {
        headers: { Accept: "application/json" },
      });
      if (!response.ok || generation !== fetchGeneration) return;
      const body = await response.json();
      render(Array.isArray(body.items) ? body.items : []);
    } catch (_error) {
      if (generation === fetchGeneration) close();
    }
  };

  textarea.addEventListener("input", refresh);
  textarea.addEventListener("click", refresh);
  textarea.addEventListener("keyup", refresh);
  textarea.addEventListener("keydown", (event) => {
    if (menu.hidden) return;
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === "Enter" || event.key === "Tab") {
      const first = menu.querySelector(".skill-menu__row");
      if (first instanceof HTMLButtonElement) {
        event.preventDefault();
        first.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
      }
    }
  });
  document.addEventListener("click", (event) => {
    if (!form.contains(event.target)) close();
  });
};

const attachSkillAutocompletes = () => {
  document.querySelectorAll("form[data-skill-autocomplete]").forEach(attachSkillAutocomplete);
};

if (!customElements.get("theme-toggle")) customElements.define("theme-toggle", ThemeToggle);
if (!customElements.get("auto-grow-textarea")) customElements.define("auto-grow-textarea", AutoGrowTextarea);
if (!customElements.get("scroll-bottom")) customElements.define("scroll-bottom", ScrollBottom);
if (!customElements.get("chat-stream")) customElements.define("chat-stream", ChatStream);
if (!customElements.get("agent-chat-panel")) customElements.define("agent-chat-panel", AgentChatPanel);
if (!customElements.get("agent-run-panel")) customElements.define("agent-run-panel", AgentRunPanel);

routerObserver.observe(document.body, {
  childList: true,
  subtree: true,
  attributes: true,
  attributeFilter: ["data-route-path"],
});
const skillAutocompleteObserver = new MutationObserver(attachSkillAutocompletes);
skillAutocompleteObserver.observe(document.body, { childList: true, subtree: true });
attachSkillAutocompletes();
requestAnimationFrame(syncRouterState);

// --- math typesetting (KaTeX auto-render) + spoiler reveal -------------------
// SSE morphs reset typeset math back to raw $...$ text, so re-typeset after
// DOM mutations; the .katex filter prevents self-trigger loops and typesetting
// is idempotent (output contains no delimiters).
const MATH_OPTS = {
  delimiters: [
    { left: "$$", right: "$$", display: true },
    { left: "\\[", right: "\\]", display: true },
    { left: "$", right: "$", display: false },
    { left: "\\(", right: "\\)", display: false },
  ],
  ignoredTags: ["pre", "code", "script", "style", "textarea", "option"],
  throwOnError: false,
};
let mathTimer = null;
const typesetMath = () => {
  if (typeof window.renderMathInElement !== "function") return;
  document.querySelectorAll(".message-content.markdown").forEach((el) => {
    window.renderMathInElement(el, MATH_OPTS);
  });
};
const mathObserver = new MutationObserver((mutations) => {
  const relevant = mutations.some((m) => {
    const target = m.target instanceof Element ? m.target : m.target.parentElement;
    return target && !target.closest(".katex");
  });
  if (!relevant) return;
  clearTimeout(mathTimer);
  mathTimer = setTimeout(typesetMath, 60);
});
mathObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
if (document.readyState === "complete") {
  typesetMath();
} else {
  window.addEventListener("load", typesetMath);
}

// Spoilers: delegated so the handler survives SSE morphs.
document.addEventListener("click", (event) => {
  const spoiler = event.target instanceof Element && event.target.closest(".spoiler");
  if (spoiler) spoiler.classList.toggle("spoiler--revealed");
});
