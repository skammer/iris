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


// One owner per composer; every listener and in-flight request has a lifetime.
export function attachSkillAutocomplete(form) {
  const textarea = form.querySelector("textarea[data-skill-input]");
  const lifetime = new AbortController();
  const menu = document.createElement("div");
  menu.className = "skill-menu";
  menu.hidden = true;
  form.append(menu);
  let range = null, generation = 0, request = null, timer = null;
  let lastKey = null, pasted = false;
  const listen = (el, name, fn) => el.addEventListener(name, fn, { signal: lifetime.signal });
  const cancel = () => {
    generation++;
    clearTimeout(timer);
    request?.abort();
    request = null;
  };
  const close = () => {
    cancel();
    menu.hidden = true;
    range = null;
    lastKey = null;
  };
  const choose = (row) => {
    if (!range) return;
    const caret = range.from + row.name.length + 2;
    textarea.value = `${textarea.value.slice(0, range.from)}/${row.name} ${textarea.value.slice(range.to)}`;
    textarea.setSelectionRange(caret, caret);
    close();
    textarea.dispatchEvent(new Event("input", { bubbles: true }));
    textarea.focus();
  };
  const render = (items) => {
    menu.replaceChildren();
    for (const row of items) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "skill-menu__row";
      const name = document.createElement("span"), desc = document.createElement("span");
      name.className = "skill-menu__name";
      desc.className = "skill-menu__desc";
      name.textContent = `/${row.name}`;
      desc.textContent = row.description || "";
      button.append(name, desc);
      button.addEventListener("mousedown", (event) => { event.preventDefault(); choose(row); });
      button.addEventListener("click", () => choose(row));
      menu.append(button);
    }
    menu.hidden = !items.length;
  };
  const refresh = () => {
    if (pasted) { pasted = false; close(); return; }
    const draft = slashDraftAtCaret(textarea.value, textarea.selectionStart ?? 0);
    if (!draft || textarea.selectionStart !== textarea.selectionEnd) { close(); return; }
    const key = `${draft.from}:${draft.to}:${draft.prefix}`;
    if (key === lastKey) return;
    cancel();
    menu.hidden = true;
    lastKey = key;
    range = draft;
    const mine = generation;
    timer = setTimeout(async () => {
      request = new AbortController();
      try {
        const params = new URLSearchParams({ prefix: draft.prefix, page: "1", page_size: "20" });
        const response = await fetch(`/v1/slash-commands?${params}`, {
          headers: { Accept: "application/json" }, signal: request.signal,
        });
        if (mine !== generation || lifetime.signal.aborted) return;
        if (!response.ok) { close(); return; }
        const body = await response.json();
        if (mine !== generation || lifetime.signal.aborted || !form.isConnected) return;
        render(Array.isArray(body.items) ? body.items : []);
      } catch (_) {
        if (mine === generation) close();
      }
    }, 150);
  };
  listen(textarea, "paste", () => { pasted = true; close(); });
  listen(textarea, "input", refresh);
  listen(textarea, "click", refresh);
  listen(textarea, "keyup", (event) => {
    if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) refresh();
  });
  listen(textarea, "keydown", (event) => {
    if (event.isComposing) return;
    if (event.key === "Escape") { close(); return; }
    if (!menu.hidden && (event.key === "Enter" || event.key === "Tab")) {
      event.preventDefault();
      menu.querySelector("button")?.click();
    } else if (menu.hidden && event.key === "Enter" && !event.shiftKey && textarea.hasAttribute("data-submit-on-enter")) {
      event.preventDefault();
      form.requestSubmit();
    }
  });
  listen(document, "click", (event) => { if (!form.contains(event.target)) close(); });
  listen(form, "reset", close);
  return () => { close(); lifetime.abort(); menu.remove(); };
}
