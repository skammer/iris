const AUTOSCROLL_THRESHOLD_PX = 32;
const THEME_STORAGE_KEY = "iris-theme";

const createUiClientId = () => {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
};

window.irisUiClientId = window.irisUiClientId || createUiClientId();

let chatStreamController = new AbortController();
window.irisChatStreamController = chatStreamController;

const resetChatStreamController = () => {
  chatStreamController.abort();
  chatStreamController = new AbortController();
  window.irisChatStreamController = chatStreamController;
  return chatStreamController;
};

const abortChatStreamController = () => {
  chatStreamController.abort();
};

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
    if (path) {
      if (path.startsWith("/chat")) resetChatStreamController();
      else abortChatStreamController();
      syncRoute(path);
    }
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
  #switchTimer = null;
  #pendingTheme = null;

  connectedCallback() {
    this.button?.addEventListener("click", this);
    this.render();
  }

  disconnectedCallback() {
    this.button?.removeEventListener("click", this);
    clearTimeout(this.#switchTimer);
  }

  get button() {
    return this.querySelector("button");
  }

  handleEvent() {
    const root = document.documentElement;
    const current = this.#pendingTheme || root.dataset.theme;
    const next = current === "light" ? "dark" : "light";
    const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    this.#pendingTheme = next;
    root.classList.add("theme-switching");
    clearTimeout(this.#switchTimer);
    this.#switchTimer = setTimeout(() => {
      persistTheme(next);
      applyTheme(next);
      this.#pendingTheme = null;
      this.render();
      requestAnimationFrame(() => root.classList.remove("theme-switching"));
    }, reduceMotion ? 0 : 90);
  }

  render() {
    const button = this.button;
    if (!(button instanceof HTMLButtonElement)) return;
    const light = document.documentElement.dataset.theme === "light";
    button.textContent = light ? "Light" : "Dark";
    button.setAttribute("aria-pressed", String(light));
  }
}

class AgentChatPanel extends HTMLElement {
  #streamController = null;

  connectedCallback() {
    if (chatStreamController.signal.aborted) resetChatStreamController();
    this.#streamController = chatStreamController;
  }

  disconnectedCallback() {
    if (this.#streamController === chatStreamController) {
      abortChatStreamController();
    } else {
      this.#streamController?.abort();
    }
    this.#streamController = null;
  }
}

class ChatStream extends HTMLElement {
  #stick = true;
  #manualScroll = false;
  #lastScrollTop = 0;
  #lastTouchY = null;
  #observer = new MutationObserver(() => this.afterChange());

  connectedCallback() {
    this.#lastScrollTop = this.scrollTop;
    this.#syncFollowState();
    this.addEventListener("scroll", this, { passive: true });
    this.addEventListener("wheel", this, { passive: true });
    this.addEventListener("touchstart", this, { passive: true });
    this.addEventListener("touchmove", this, { passive: true });
    this.#observer.observe(this, { childList: true, subtree: true, characterData: true });
    requestAnimationFrame(() => this.scrollToAnchor());
  }

  disconnectedCallback() {
    this.removeEventListener("scroll", this);
    this.removeEventListener("wheel", this);
    this.removeEventListener("touchstart", this);
    this.removeEventListener("touchmove", this);
    this.#observer.disconnect();
  }

  handleEvent(event) {
    if (event.type === "wheel") {
      if (event.deltaY < 0) this.releaseBottom();
      return;
    }
    if (event.type === "touchstart") {
      this.#lastTouchY = event.touches?.[0]?.clientY ?? null;
      return;
    }
    if (event.type === "touchmove") {
      const y = event.touches?.[0]?.clientY ?? null;
      if (this.#lastTouchY != null && y != null && y > this.#lastTouchY) this.releaseBottom();
      this.#lastTouchY = y;
      return;
    }
    if (event.type !== "scroll") return;

    const current = this.scrollTop;
    const scrollingUp = current < this.#lastScrollTop - 1;
    if (scrollingUp) this.#manualScroll = true;
    if (!scrollingUp && this.#distanceFromBottom() <= AUTOSCROLL_THRESHOLD_PX) {
      this.#manualScroll = false;
    }
    this.#stick = !this.#manualScroll && this.#distanceFromBottom() <= AUTOSCROLL_THRESHOLD_PX;
    this.#lastScrollTop = current;
    this.#syncFollowState();
  }

  afterChange() {
    // CSS scroll anchoring (app.css) pins growth natively where supported,
    // but it is suppressed while scrollTop is 0 and absent in Safari, so
    // keep pinning from here too. Both target the same position; no fight.
    if (this.#stick) requestAnimationFrame(() => this.scrollToAnchor());
  }

  #distanceFromBottom() {
    return Math.max(0, this.scrollHeight - this.clientHeight - this.scrollTop);
  }

  #syncFollowState() {
    this.dataset.followBottom = this.#stick ? "true" : "false";
  }

  releaseBottom() {
    this.#manualScroll = true;
    this.#stick = false;
    this.#syncFollowState();
  }

  scrollToAnchor() {
    const anchor = this.querySelector(".chat-stream__bottom-anchor");
    if (anchor instanceof HTMLElement) {
      anchor.scrollIntoView({ block: "end" });
      this.#lastScrollTop = this.scrollTop;
    }
  }

  // Sending a message is an explicit "take me to the conversation tail",
  // even when scrolled up reading history (where #stick is false).
  followBottom() {
    this.#manualScroll = false;
    this.#stick = true;
    this.#syncFollowState();
    requestAnimationFrame(() => this.scrollToAnchor());
  }
}

document.addEventListener("submit", (event) => {
  if (event.target instanceof HTMLFormElement && event.target.id === "chat-form") {
    document.querySelector("chat-stream")?.followBottom?.();
  }
}, true);

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

  let suppressNextRefresh = false;

  const refresh = async () => {
    // Pasted content is never a skill invocation in progress: without this,
    // text ending in "/word" pops the menu and the next Enter gets hijacked
    // into completing a skill instead of submitting the message.
    if (suppressNextRefresh) {
      suppressNextRefresh = false;
      close();
      return;
    }
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

  textarea.addEventListener("paste", () => {
    suppressNextRefresh = true;
  });
  textarea.addEventListener("input", refresh);
  textarea.addEventListener("click", refresh);
  textarea.addEventListener("keyup", refresh);
  // Single owner of the Enter key: completing a skill and submitting the
  // form must never race in separate listeners.
  textarea.addEventListener("keydown", (event) => {
    if (!menu.hidden) {
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
      return;
    }
    if (
      textarea.hasAttribute("data-submit-on-enter")
      && event.key === "Enter"
      && !event.shiftKey
      && !event.isComposing
    ) {
      event.preventDefault();
      form.requestSubmit();
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
if (!customElements.get("chat-stream")) customElements.define("chat-stream", ChatStream);
if (!customElements.get("agent-chat-panel")) customElements.define("agent-chat-panel", AgentChatPanel);

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

// --- motion orchestration (transitions.dev) ----------------------------------
// Sliding nav pill: position survives Datastar morphs via module state, so a
// tab change animates from the previous tab even when inline styles are
// stripped by the patch.
const navPillState = { left: null, width: null };

const positionNavPill = () => {
  const nav = document.getElementById("shell-nav");
  if (!nav) return;
  const pill = nav.querySelector(".shell-nav__pill");
  const active = nav.querySelector(".tab-link.active");
  if (!pill || !active) return;
  const left = active.offsetLeft;
  const width = active.offsetWidth;
  const apply = (l, w) => {
    pill.style.transform = `translateX(${l}px)`;
    pill.style.width = `${w}px`;
  };
  const snap = (l, w) => {
    pill.style.transition = "none";
    apply(l, w);
    void pill.offsetWidth;
    pill.style.transition = "";
  };
  if (navPillState.left === null) {
    snap(left, width);
  } else if (navPillState.left !== left || navPillState.width !== width) {
    snap(navPillState.left, navPillState.width);
    apply(left, width);
  } else {
    snap(left, width);
  }
  navPillState.left = left;
  navPillState.width = width;
  nav.setAttribute("data-pill-ready", "true");
};

window.addEventListener("resize", () => {
  navPillState.left = null;
  positionNavPill();
});

// Workspace entrance: staggered rise on the FIRST paint only. Tab switches
// must be instant — re-animating on navigation makes every click read as a
// full-page flash (content paints, blinks to opacity 0, fades back in).
// The class is removed once the animation completes — leaving it on would
// re-run the entrance on every patched child and can strand content at the
// animation's from-state (opacity 0) if rendering is interrupted.
let entrancePlayed = false;
let entranceCleanupTimer = null;

const replayWorkspaceEntrance = () => {
  if (entrancePlayed) return;
  const grid = document.querySelector(".workspace-grid");
  if (!grid) return;
  entrancePlayed = true;
  grid.classList.add("is-entering");
  clearTimeout(entranceCleanupTimer);
  entranceCleanupTimer = setTimeout(() => {
    document.querySelectorAll(".workspace-grid.is-entering").forEach((el) => {
      el.classList.remove("is-entering");
    });
  }, 450);
};

// Number pop-in: re-enter digits when a labelled stat value changes. Values
// are tracked per label in module state so the 10s dashboard refresh only
// animates genuine changes.
const statValueCache = new Map();

const animateChangedNumbers = () => {
  document.querySelectorAll(".stat, .status-block, .result--metric").forEach((card) => {
    const labelEl = card.querySelector(".label, .status-label, strong");
    const valueEl = card.querySelector(".value, .status-value");
    if (!labelEl || !valueEl) return;
    const label = labelEl.textContent.trim();
    if (!label) return;
    // Scope the cache key to the containing panel: the header chip and the
    // overview stat card are both labelled "events" with slightly different
    // refresh cadences — one shared key makes them ping-pong animations
    // forever as each pass "corrects" the other's value.
    const key = `${card.closest("[id]")?.id ?? "page"}::${label}`;
    const text = valueEl.textContent.trim();
    const prev = statValueCache.get(key);
    statValueCache.set(key, text);
    if (prev === undefined || prev === text) return;
    if (!/^[\d.,]+$/.test(text)) return;
    valueEl.classList.remove("is-animating");
    valueEl.replaceChildren(
      ...[...text].map((ch, i, arr) => {
        const digit = document.createElement("span");
        digit.className = "t-digit";
        const fromEnd = arr.length - 1 - i;
        if (fromEnd < 2) digit.dataset.stagger = String(2 - fromEnd);
        digit.textContent = ch;
        return digit;
      }),
    );
    void valueEl.offsetWidth;
    valueEl.classList.add("is-animating");
  });
};

let motionTimer = null;
const motionObserver = new MutationObserver(() => {
  // Pre-paint (observer callbacks are microtasks): both the entrance class
  // and the pill position must land before the browser paints the patched
  // content. A morph strips the pill's inline transform, and a deferred
  // reposition lets it visibly transition toward translateX(0) — the first
  // tab — before snapping back to the clicked one.
  replayWorkspaceEntrance();
  positionNavPill();
  clearTimeout(motionTimer);
  motionTimer = setTimeout(animateChangedNumbers, 40);
});
motionObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
positionNavPill();
replayWorkspaceEntrance();
animateChangedNumbers();

// SSE last resort: a stream that exhausts its retry budget leaves the page
// silently frozen — patches stop arriving with no visible sign. Reload to
// re-establish every stream from scratch.
document.addEventListener("datastar-fetch", (event) => {
  if (event.detail?.type === "retries-failed") {
    window.location.reload();
  }
});
