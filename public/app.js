import { action } from "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.8/bundles/datastar.js";

const AUTOSCROLL_THRESHOLD_PX = 300;
let lastConnectedSessionId = null;

const parseHTML = (html) => new DOMParser().parseFromString(html, "text/html");

const replaceById = (doc, id) => {
  const next = doc.getElementById(id);
  const current = document.getElementById(id);
  if (next && current) current.replaceWith(next);
};

const distanceFromBottom = (panel) => panel.scrollHeight - panel.clientHeight - panel.scrollTop;

const nearestChatPanel = (el) => el instanceof Element ? el.closest("agent-chat-panel") : null;

class AgentChatPanel extends HTMLElement {
  #streamAbortController = null;
  #streamState = null;
  #connectSyncTimer = null;
  #isSubmitting = false;
  #boundSubmit = (event) => this.handleSubmit(event);
  #boundInput = () => this.handleInput();
  #boundKeydown = (event) => this.handleKeydown(event);

  connectedCallback() {
    this.form?.addEventListener("submit", this.#boundSubmit);
    this.textarea?.addEventListener("input", this.#boundInput);
    this.textarea?.addEventListener("keydown", this.#boundKeydown);
    this.#queueConnectSync();
  }

  disconnectedCallback() {
    this.form?.removeEventListener("submit", this.#boundSubmit);
    this.textarea?.removeEventListener("input", this.#boundInput);
    this.textarea?.removeEventListener("keydown", this.#boundKeydown);
    if (this.#connectSyncTimer) {
      clearTimeout(this.#connectSyncTimer);
      this.#connectSyncTimer = null;
    }
    if (this.#streamAbortController) {
      this.#streamAbortController.abort();
      this.#streamAbortController = null;
    }
  }

  get sessionId() {
    const input = this.querySelector('#chat-form input[name="session_id"]');
    return input instanceof HTMLInputElement ? input.value : "";
  }

  get form() {
    const form = this.querySelector("#chat-form");
    return form instanceof HTMLFormElement ? form : null;
  }

  get textarea() {
    const textarea = this.querySelector("#chat-form .chat-input");
    return textarea instanceof HTMLTextAreaElement ? textarea : null;
  }

  get messagesPanel() {
    return this.querySelector("#session-messages-panel");
  }

  get messagesContainer() {
    const panel = this.messagesPanel;
    if (!panel) return null;
    let list = panel.querySelector(".messages");
    if (!list) {
      panel.textContent = "";
      list = document.createElement("div");
      list.className = "messages";
      panel.appendChild(list);
    }
    return list;
  }

  get statusNode() {
    return this.querySelector("#chat-status");
  }

  autogrow() {
    const textarea = this.textarea;
    if (!(textarea instanceof HTMLTextAreaElement)) return;
    textarea.style.height = "auto";
    textarea.style.height = `${textarea.scrollHeight}px`;
  }

  shouldStickToBottom(panel = this.messagesContainer) {
    return !!panel && distanceFromBottom(panel) <= AUTOSCROLL_THRESHOLD_PX;
  }

  scrollToBottom(force = false) {
    const panel = this.messagesContainer;
    if (!panel) return;
    if (!force && !this.shouldStickToBottom(panel)) return;
    panel.scrollTo({ top: panel.scrollHeight, behavior: "auto" });
  }

  pausePolling() {
    const panel = this.messagesPanel;
    if (!panel) return;
    const attr = "data-on-interval__duration.3s";
    const value = panel.getAttribute(attr);
    if (!value) return;
    panel.dataset.pollingInterval = value;
    panel.removeAttribute(attr);
  }

  resumePolling() {
    const panel = this.messagesPanel;
    if (!panel) return;
    const attr = "data-on-interval__duration.3s";
    const value = panel.dataset.pollingInterval;
    if (value && !panel.getAttribute(attr)) {
      panel.setAttribute(attr, value);
    }
  }

  setLoading(loading) {
    const form = this.form;
    if (!form) return;
    form.classList.toggle("is-loading", loading);
    const submit = form.querySelector('button[type="submit"]');
    if (submit instanceof HTMLButtonElement) submit.disabled = loading;
    if (this.statusNode) this.statusNode.hidden = !loading;
  }

  renderMessage(role, content, meta = "", extraClass = "") {
    const article = document.createElement("article");
    article.className = `message ${extraClass}`.trim();

    const roleNode = document.createElement("div");
    roleNode.className = `message-role ${role}`;
    roleNode.textContent = role;

    const contentNode = document.createElement("div");
    contentNode.className = "code";
    contentNode.textContent = content;

    const metaNode = document.createElement("div");
    metaNode.className = "meta";
    metaNode.textContent = meta;

    article.append(roleNode, contentNode, metaNode);
    return { article, contentNode, metaNode };
  }

  appendMessage(role, content, meta = "", extraClass = "") {
    const list = this.messagesContainer;
    if (!list) return null;
    const stick = this.shouldStickToBottom(list);
    const message = this.renderMessage(role, content, meta, extraClass);
    list.appendChild(message.article);
    if (stick) this.scrollToBottom(true);
    return message;
  }

  ensureStreamingAssistant() {
    const list = this.messagesContainer;
    if (!list) return null;
    let article = list.querySelector(".message.is-streaming");
    if (!article) {
      const created = this.renderMessage("assistant", "", "streaming...", "is-streaming");
      list.appendChild(created.article);
      article = created.article;
    }
    return {
      article,
      contentNode: article.querySelector(".code"),
      metaNode: article.querySelector(".meta")
    };
  }

  restoreStreamState() {
    if (!this.#streamState || this.#streamState.sessionId !== this.sessionId) return;
    this.pausePolling();
    const assistant = this.ensureStreamingAssistant();
    if (!assistant) return;
    assistant.contentNode.textContent = this.#streamState.content;
    assistant.metaNode.textContent = this.#streamState.meta;
    assistant.article.classList.toggle("is-error", this.#streamState.error);
  }

  #queueConnectSync() {
    if (this.#connectSyncTimer) clearTimeout(this.#connectSyncTimer);
    this.#connectSyncTimer = window.setTimeout(() => {
      this.#connectSyncTimer = null;
      this.autogrow();
      this.restoreStreamState();
      if (this.sessionId && this.sessionId !== lastConnectedSessionId) {
        this.scrollToBottom(true);
      }
      if (this.sessionId) lastConnectedSessionId = this.sessionId;
    }, 0);
  }

  async refreshFragments() {
    const sessionId = this.sessionId;
    const stick = this.shouldStickToBottom(this.messagesContainer);
    const [messagesResponse, dashboardResponse, sessionsResponse] = await Promise.all([
      fetch(`/ui/session-messages?session_id=${encodeURIComponent(sessionId)}`),
      fetch("/ui/dashboard"),
      fetch("/ui/sessions")
    ]);

    if (messagesResponse.ok) replaceById(parseHTML(await messagesResponse.text()), "session-messages-panel");
    if (dashboardResponse.ok) replaceById(parseHTML(await dashboardResponse.text()), "dashboard-summary");
    if (sessionsResponse.ok) replaceById(parseHTML(await sessionsResponse.text()), "sessions-panel");
    this.resumePolling();
    this.autogrow();
    if (stick) this.scrollToBottom(true);
  }

  async #streamCompletion(prompt) {
    this.#streamAbortController = new AbortController();
    const response = await fetch("/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream"
      },
      signal: this.#streamAbortController.signal,
      body: JSON.stringify({
        session_id: this.sessionId,
        prompt,
        stream: true
      })
    });

    if (!response.ok || !response.body) {
      throw new Error(`chat_failed_${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });

      let split = buffer.indexOf("\n\n");
      while (split >= 0) {
        const block = buffer.slice(0, split);
        buffer = buffer.slice(split + 2);
        this.#applySSEBlock(block);
        split = buffer.indexOf("\n\n");
      }

      if (done) break;
    }
  }

  #applySSEBlock(block) {
    const lines = block.split(/\r?\n/).filter((line) => line.startsWith("data: "));
    if (!lines.length) return;
    const data = lines.map((line) => line.slice(6)).join("\n");
    if (data === "[DONE]") return;
    const payload = JSON.parse(data);
    if (payload.error) {
      this.#streamState = {
        sessionId: this.sessionId,
        content: payload.message || payload.error,
        meta: "error",
        error: true
      };
      this.#renderStreamState();
      return;
    }
    const delta = payload.choices?.[0]?.delta?.content;
    if (!delta) return;
    if (!this.#streamState) {
      this.#streamState = {
        sessionId: this.sessionId,
        content: "",
        meta: "streaming...",
        error: false
      };
    }
    const stick = this.shouldStickToBottom(this.messagesContainer);
    this.#streamState.content += delta;
    this.#streamState.meta = "streaming...";
    this.#renderStreamState();
    if (stick) this.scrollToBottom(true);
  }

  #renderStreamState() {
    const assistant = this.ensureStreamingAssistant();
    if (!assistant || !this.#streamState) return;
    assistant.contentNode.textContent = this.#streamState.content;
    assistant.metaNode.textContent = this.#streamState.meta;
    assistant.article.classList.toggle("is-error", this.#streamState.error);
  }

  async submitPrompt() {
    if (this.#isSubmitting) return;
    const textarea = this.textarea;
    if (!(textarea instanceof HTMLTextAreaElement)) return;

    const prompt = textarea.value.trim();
    if (!prompt || !this.sessionId) return;

    this.scrollToBottom(true);
    this.appendMessage("user", prompt);
    this.#streamState = {
      sessionId: this.sessionId,
      content: "",
      meta: "streaming...",
      error: false
    };
    this.pausePolling();
    this.ensureStreamingAssistant();
    this.scrollToBottom(true);
    textarea.value = "";
    this.autogrow();
    textarea.focus();
    this.#isSubmitting = true;
    this.setLoading(true);

    try {
      await this.#streamCompletion(prompt);
      this.#streamState = null;
      await this.refreshFragments();
    } catch (error) {
      if (error.name !== "AbortError") {
        this.#streamState = {
          sessionId: this.sessionId,
          content: String(error),
          meta: "error",
          error: true
        };
        this.#renderStreamState();
        this.resumePolling();
      }
    } finally {
      this.#isSubmitting = false;
      this.#streamAbortController = null;
      this.setLoading(false);
    }
  }

  handleInput() {
    this.autogrow();
  }

  handleKeydown(event) {
    if (!(event instanceof KeyboardEvent)) return;
    if (event.isComposing || event.shiftKey || event.key !== "Enter") return;
    event.preventDefault();
    void this.submitPrompt();
  }

  handleSubmit(event) {
    if (event instanceof Event) event.preventDefault();
    void this.submitPrompt();
  }
}

if (!customElements.get("agent-chat-panel")) {
  customElements.define("agent-chat-panel", AgentChatPanel);
}

action({
  name: "chatSubmit",
  apply({ el }, evt) {
    nearestChatPanel(el)?.handleSubmit(evt);
  }
});

action({
  name: "chatInput",
  apply({ el }) {
    nearestChatPanel(el)?.handleInput();
  }
});

action({
  name: "chatKeydown",
  apply({ el }, evt) {
    nearestChatPanel(el)?.handleKeydown(evt);
  }
});
