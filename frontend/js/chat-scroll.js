const AUTOSCROLL_THRESHOLD_PX = 32;
class ChatStream extends HTMLElement {
  #frame = null;
  #stick = true;
  #manualScroll = false;
  #lastScrollTop = 0;
  #lastTouchY = null;
  #bottomButton = null;
  #observer = new MutationObserver(() => this.afterChange());

  connectedCallback() {
    this.#bottomButton = this.closest(".chat-transcript")?.querySelector("[data-chat-scroll-bottom]") || null;
    this.#lastScrollTop = this.scrollTop;
    this.#syncFollowState();
    this.addEventListener("scroll", this, { passive: true });
    this.addEventListener("wheel", this, { passive: true });
    this.addEventListener("touchstart", this, { passive: true });
    this.addEventListener("touchmove", this, { passive: true });
    this.#bottomButton?.addEventListener("click", this);
    this.addEventListener("iris-content-resized", this);
    this.addEventListener("load", this, true);
    this.#observer.observe(this, { childList: true, subtree: true, characterData: true });
    this.#scheduleScroll();
  }

  disconnectedCallback() {
    this.removeEventListener("scroll", this);
    this.removeEventListener("wheel", this);
    this.removeEventListener("touchstart", this);
    this.removeEventListener("touchmove", this);
    this.#bottomButton?.removeEventListener("click", this);
    this.#bottomButton = null;
    this.#observer.disconnect();
    cancelAnimationFrame(this.#frame);
    this.#frame = null;
    this.removeEventListener("iris-content-resized", this);
    this.removeEventListener("load", this, true);
  }

  handleEvent(event) {
    if (event.type === "iris-content-resized" || event.type === "load") { this.afterChange(); return; }
    if (event.type === "click") {
      if (this.dataset.history === "true") this.querySelector("[data-history-latest]")?.click();
      this.followBottom();
      return;
    }
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
    if (this.dataset.history === "true") this.#syncFollowState();
    // CSS scroll anchoring (app.css) pins growth natively where supported,
    // but it is suppressed while scrollTop is 0 and absent in Safari, so
    // keep pinning from here too. Both target the same position; no fight.
    if (this.#stick) this.#scheduleScroll();
  }

  #scheduleScroll() {
    if (this.#frame !== null) return;
    this.#frame = requestAnimationFrame(() => {
      this.#frame = null;
      if (this.isConnected && this.#stick) this.scrollToAnchor();
    });
  }

  #distanceFromBottom() {
    return Math.max(0, this.scrollHeight - this.clientHeight - this.scrollTop);
  }

  #syncFollowState() {
    this.dataset.followBottom = this.#stick ? "true" : "false";
    if (this.#bottomButton instanceof HTMLButtonElement) {
      const visible = this.dataset.history === "true" || (!this.#stick && this.#distanceFromBottom() > AUTOSCROLL_THRESHOLD_PX);
      this.#bottomButton.hidden = !visible;
      this.#bottomButton.setAttribute("aria-hidden", String(!visible));
    }
  }

  releaseBottom() {
    this.#manualScroll = true;
    this.#stick = false;
    this.#syncFollowState();
  }

  scrollToAnchor() {
    this.scrollTop = this.scrollHeight;
    this.#lastScrollTop = this.scrollTop;
    this.#syncFollowState();
  }

  // Sending a message is an explicit "take me to the conversation tail",
  // even when scrolled up reading history (where #stick is false).
  followBottom() {
    this.#manualScroll = false;
    this.#stick = true;
    this.#syncFollowState();
    this.#scheduleScroll();
  }
}

document.addEventListener("submit", (event) => {
  if (event.target instanceof HTMLFormElement && event.target.id === "chat-form") {
    document.querySelector("chat-stream")?.followBottom?.();
  }
}, true);


customElements.define("chat-stream", ChatStream);
