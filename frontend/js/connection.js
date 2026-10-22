import { resetChatStreamController } from "./navigation.js";
class AgentChatPanel extends HTMLElement {
  static observedAttributes = ["data-session-id"];
  attributeChangedCallback(_name, previous, current) {
    if (previous === current) return;
    clearTimeout(this.#timer);
    this.#controller?.abort();
    this.#controller = resetChatStreamController();
  }
  #controller;
  #timer;
  connectedCallback() {
    if (window.irisChatStreamController.signal.aborted) resetChatStreamController();
    this.#controller = window.irisChatStreamController;
    this.addEventListener("click", this);
    window.addEventListener("online", this);
  }
  disconnectedCallback() {
    clearTimeout(this.#timer);
    this.#controller?.abort();
    this.removeEventListener("click", this);
    window.removeEventListener("online", this);
  }
  handleEvent(event) {
    if ((event.type === "online" && this.dataset.connection === "failed") ||
        event.target.closest?.("[data-chat-reconnect]")) this.reconnect();
  }
  setConnection(state) {
    this.dataset.connection = state;
    const status = this.querySelector("[data-connection-status]");
    if (status) {
      status.hidden = state === "connected";
      status.querySelector("span").textContent = state === "failed"
        ? "Connection lost. Your draft is kept."
        : "Reconnecting… Your draft is kept.";
    }
    clearTimeout(this.#timer);
    if (state === "failed" && navigator.onLine) this.#timer = setTimeout(() => this.reconnect(), 5000);
  }
  reconnect() {
    if (!this.isConnected) return;
    this.#controller = resetChatStreamController();
    this.setConnection("retrying");
    this.dispatchEvent(new Event("iris-reconnect"));
  }
}
customElements.define("agent-chat-panel", AgentChatPanel);
document.addEventListener("datastar-fetch", ({ detail }) => {
  const { type, el } = detail || {};
  if (el instanceof AgentChatPanel && el.isConnected) {
    if (type === "retrying" || type === "error") el.setConnection("retrying");
    if (type === "retries-failed") el.setConnection("failed");
    if (type === "datastar-patch-elements") el.setConnection("connected");
  } else if (type === "retries-failed" && el?.isConnected) {
    const notice = document.getElementById("connection-notice");
    if (notice) { notice.hidden = false; notice.textContent = "Request failed. Please retry the action. Your draft is kept."; }
  }
});
