class CatalogCardTabs extends HTMLElement {
  connectedCallback() {
    this.addEventListener("click", this);
    this.addEventListener("keydown", this);
  }

  disconnectedCallback() {
    this.removeEventListener("click", this);
    this.removeEventListener("keydown", this);
  }

  handleEvent(event) {
    const tab = event.target instanceof Element && event.target.closest("[data-card-tab]");
    if (!(tab instanceof HTMLButtonElement) || !this.contains(tab)) return;
    if (event.type === "click") {
      this.activate(tab, true);
      return;
    }
    if (event.type !== "keydown") return;
    const tabs = [...this.querySelectorAll("[data-card-tab]")];
    const vertical = tab.closest("[aria-orientation=vertical]") !== null;
    const previousKey = vertical ? "ArrowUp" : "ArrowLeft";
    const nextKey = vertical ? "ArrowDown" : "ArrowRight";
    let nextIndex = null;
    if (event.key === previousKey) nextIndex = (tabs.indexOf(tab) - 1 + tabs.length) % tabs.length;
    if (event.key === nextKey) nextIndex = (tabs.indexOf(tab) + 1) % tabs.length;
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = tabs.length - 1;
    if (nextIndex === null) return;
    event.preventDefault();
    this.activate(tabs[nextIndex], true);
  }

  activate(activeTab, focus) {
    const id = activeTab.dataset.cardTab;
    this.querySelectorAll("[data-card-tab]").forEach((tab) => {
      const selected = tab === activeTab;
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
    });
    this.querySelectorAll("[data-card-panel]").forEach((panel) => {
      panel.hidden = panel.dataset.cardPanel !== id;
    });
    if (focus) activeTab.focus();
  }
}


customElements.define("catalog-card-tabs", CatalogCardTabs);
