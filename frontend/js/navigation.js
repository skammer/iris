const createUiClientId = () => {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
};

window.irisUiClientId = window.irisUiClientId || createUiClientId();

let chatStreamController = new AbortController();
window.irisChatStreamController = chatStreamController;

export const resetChatStreamController = () => {
  chatStreamController.abort();
  chatStreamController = new AbortController();
  window.irisChatStreamController = chatStreamController;
  return chatStreamController;
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

class IrisRouterState extends HTMLElement {
  static observedAttributes = ["data-route-path"];
  connectedCallback() { syncRouterState(); }
  attributeChangedCallback() { if (this.isConnected) syncRouterState(); }
}
customElements.define("iris-router-state", IrisRouterState);

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
      syncRoute(path);
      if (route.matches(".session-link")) {
        const workspace = route.closest(".chat-workspace");
        workspace?.classList.remove("chat-workspace--sessions-open");
        workspace?.querySelector(".chat-sessions-toggle")?.setAttribute("aria-expanded", "false");
      }
    }
  }
}, true);

window.addEventListener("popstate", () => {
  window.location.reload();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeToolDetail();
  const target = event.target;
  if (!(target instanceof Element)) return;
  const current = target.closest("#shell-nav .ui-card-tab[role=tab]");
  if (!(current instanceof HTMLButtonElement)) return;
  const tablist = current.closest("[role=tablist]");
  if (!(tablist instanceof HTMLElement)) return;
  const tabs = [...tablist.querySelectorAll(".ui-card-tab[role=tab]")];
  const index = tabs.indexOf(current);
  const nextIndex = {
    ArrowLeft: (index - 1 + tabs.length) % tabs.length,
    ArrowRight: (index + 1) % tabs.length,
    Home: 0,
    End: tabs.length - 1,
  }[event.key];
  if (nextIndex === undefined) return;
  event.preventDefault();
  tabs[nextIndex].focus();
  tabs[nextIndex].click();
});
