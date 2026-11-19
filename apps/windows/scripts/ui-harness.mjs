// DOM/IPC adapter for command and QR regression tests, excluded from the build.
import { readFile } from "node:fs/promises";
import vm from "node:vm";
import { encodeConnectionCode } from "../dist/pairing-code.js";
import { encodePairingQr } from "../dist/pairing-qr.js";
export const fixture = JSON.parse(
  await readFile(
    new URL("../../../protocol/fixtures/h1-vectors.json", import.meta.url),
    "utf8",
  ),
);
export const payload = JSON.stringify(fixture.messages.descriptor);
const html = await readFile(
  new URL("../src/index.html", import.meta.url),
  "utf8",
);
const script = (
  await readFile(new URL("../dist/main.js", import.meta.url), "utf8")
).replace(/^import .*?;\n/gm, "");
const camel = (name) =>
  name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
export async function ui(encoder = encodePairingQr, initial = {}) {
  const elements = new Map();
  const roots = [];
  const calls = [];
  const windowListeners = new Map();
  const clipboard = [];
  let clipboardBlocked = false;
  let document;
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase();
      this.children = [];
      this.attributes = new Map();
      this.dataset = {};
      this.style = {};
      this.open = false;
      this.value = "";
      this.disabled = false;
      this.hidden = false;
      this.scrollTop = 0;
      this.listeners = new Map();
      this._text = "";
      this._class = "";
      this.parentElement = null;
    }
    get className() {
      return this._class;
    }
    set className(value) {
      this._class = value;
    }
    get classList() {
      const classes = () =>
        new Set(this.className.split(/\s+/).filter(Boolean));
      return {
        add: (value) => {
          const next = classes();
          next.add(value);
          this.className = [...next].join(" ");
        },
        remove: (value) => {
          const next = classes();
          next.delete(value);
          this.className = [...next].join(" ");
        },
        contains: (value) => classes().has(value),
        toggle: (value, enabled) => {
          const next = classes();
          if (enabled ?? !next.has(value)) next.add(value);
          else next.delete(value);
          this.className = [...next].join(" ");
        },
      };
    }
    get textContent() {
      return (
        this._text + this.children.map((child) => child.textContent).join("")
      );
    }
    set textContent(value) {
      this.replaceChildren();
      this._text = value;
    }
    set innerHTML(_) {
      throw new Error("Untrusted HTML rendering is forbidden");
    }
    setAttribute(name, value) {
      this.attributes.set(name, value);
      if (name.startsWith("data-")) this.dataset[camel(name.slice(5))] = value;
    }
    getAttribute(name) {
      return this.attributes.get(name) ?? null;
    }
    removeAttribute(name) {
      this.attributes.delete(name);
      if (name === "src") this.src = "";
    }
    append(...children) {
      for (const child of children) {
        child.remove();
        child.parentElement = this;
        this.children.push(child);
      }
    }
    replaceChildren(...children) {
      for (const child of this.children) child.parentElement = null;
      this.children = [];
      this._text = "";
      this.append(...children);
    }
    insertBefore(child, target) {
      child.remove();
      child.parentElement = this;
      const index = this.children.indexOf(target);
      this.children.splice(index < 0 ? this.children.length : index, 0, child);
    }
    remove() {
      if (this.parentElement) {
        const parent = this.parentElement;
        parent.children.splice(parent.children.indexOf(this), 1);
        this.parentElement = null;
      }
    }
    matches(selector) {
      if (selector.startsWith("."))
        return this.classList.contains(selector.slice(1));
      const data = selector.match(/^\[data-([^=\]]+)(?:="([^"]*)")?\]$/);
      if (data) {
        const value = this.dataset[camel(data[1])];
        return (
          value !== undefined && (data[2] === undefined || data[2] === value)
        );
      }
      return this.tagName.toLowerCase() === selector;
    }
    querySelectorAll(selector) {
      return this.descendants().filter((child) =>
        selector.split(",").some((part) => child.matches(part.trim())),
      );
    }
    descendants() {
      return this.children.flatMap((child) => [child, ...child.descendants()]);
    }
    querySelector(selector) {
      return this.querySelectorAll(selector)[0] ?? null;
    }
    addEventListener(name, callback) {
      const list = this.listeners.get(name) ?? [];
      list.push(callback);
      this.listeners.set(name, list);
    }
    dispatch(name) {
      for (const callback of this.listeners.get(name) ?? [])
        callback({ preventDefault() {} });
    }
    showModal() {
      this.open = true;
    }
    close() {
      if (!this.open) return;
      this.open = false;
      this.dispatch("close");
    }
    focus() {
      document.activeElement = this;
    }
    select() {
      this.selected = true;
    }
    click() {
      if (!this.disabled) this.onclick?.({ preventDefault() {} });
    }
  }
  for (const match of html.matchAll(/<(\w+)\b([^>]*)>/g)) {
    const attrs = match[2];
    const id = attrs.match(/\bid="([^"]+)"/)?.[1];
    if (!id && !attrs.includes("data-close=")) continue;
    const element = new Element(match[1]);
    element.id = id;
    element.className = attrs.match(/\bclass="([^"]+)"/)?.[1] ?? "";
    element.value = attrs.match(/\bvalue="([^"]+)"/)?.[1] ?? "";
    element.disabled = /\bdisabled\b/.test(attrs);
    element.hidden = /(?:^|\s)hidden(?:\s|$)/.test(attrs);
    const close = attrs.match(/data-close="([^"]+)"/)?.[1];
    if (close) element.dataset.close = close;
    if (id) elements.set(id, element);
    roots.push(element);
  }
  document = {
    getElementById: (id) => {
      if (!elements.has(id)) throw new Error(`Missing DOM id: ${id}`);
      return elements.get(id);
    },
    createElement: (tag) => new Element(tag),
    createElementNS: (_, tag) => new Element(tag),
    activeElement: null,
    querySelectorAll: (selector) =>
      roots
        .flatMap((root) => [root, ...root.descendants()])
        .filter((element) =>
          selector.split(",").some((part) => element.matches(part.trim())),
        ),
  };
  const snapshot = {
    session: { session_id: "session-a", host: "192.168.1.20", port: 51337 },
    receive_enabled: false,
    peers: [],
    transfers: [],
    upload_batch: null,
    download_batch: null,
    ...initial,
  };
  let now = 0,
    interval,
    pending,
    fail = false,
    handler = async () => {},
    snapshotHandler = null;
  const context = {
    document,
    window: {
      addEventListener: (event, callback) =>
        windowListeners.set(event, callback),
      __TAURI__: {
        core: {
          invoke: async (command, args) => {
            calls.push({ command, args });
            if (command === "snapshot") {
              const value = structuredClone(snapshot);
              return snapshotHandler ? snapshotHandler(value) : value;
            }
            if (command === "open_pairing_window") {
              if (fail) throw "io_error";
              return pending ? pending : fixture.messages.descriptor;
            }
            if (command === "stop_session") snapshot.session = null;
            return handler(command, args);
          },
        },
      },
    },
    encodePairingQr: encoder,
    encodeConnectionCode,
    performance: { now: () => now },
    setInterval: (callback) => {
      interval = callback;
    },
    console,
    navigator: {
      clipboard: {
        async writeText(value) {
          if (clipboardBlocked) throw new Error("Clipboard denied");
          clipboard.push(value);
        },
      },
    },
  };
  vm.runInNewContext(script, context);
  const settle = () => new Promise((resolve) => setImmediate(resolve));
  await settle();
  return {
    element: (id) => document.getElementById(id),
    document,
    snapshot,
    calls,
    clipboard,
    blockClipboard: () => { clipboardBlocked = true; },
    settle,
    onSnapshot: (callback) => { snapshotHandler = callback; },
    onInvoke: (callback) => {
      handler = callback;
    },
    open: async () => {
      document.getElementById("pair").click();
      await settle();
    },
    tick: async (time = now + 500) => {
      now = time;
      interval();
      await settle();
    },
    expire: async () => {
      now = 120001;
      interval();
      await settle();
    },
    pagehide: () => windowListeners.get("pagehide")(),
    failOpen: () => {
      fail = true;
    },
    defer: () => {
      let resolve;
      pending = new Promise((r) => {
        resolve = r;
      });
      return resolve;
    },
  };
}
