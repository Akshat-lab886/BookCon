/* BookCon Web — SPA wired to the /api/v1 endpoints.
   Feature set intentionally limited to what the API fully supports today. */

const API = "/api/v1";

/* ---------------- state ---------------- */
const state = {
  token: localStorage.getItem("bc_token") || null,
  refresh: localStorage.getItem("bc_refresh") || null,
  user: JSON.parse(localStorage.getItem("bc_user") || "null"),
  route: localStorage.getItem("bc_route") || "home",
  books: [],
  bookmarks: [],
  shelves: [],
  tags: [],
  devices: [],
  series: [],
  search: "",
  format: null,
  shelf: null,
  shelves: [],
  sort: localStorage.getItem("bc_sort") || "recent",
  loading: false,
};

/* ---------------- dom helpers ---------------- */
const $ = (s, r = document) => r.querySelector(s);
const el = (tag, attrs = {}, ...kids) => {
  const n = document.createElement(tag);
  let text = null;
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") n.className = v;
    else if (k === "html") n.innerHTML = v;
    else if (k === "text") text = v;
    else if (k.startsWith("on") && typeof v === "function") n.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined && v !== false) n.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid === null || kid === undefined || kid === false) continue;
    n.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  // `text` is applied last so it wins over child nodes (used for simple labels).
  if (text !== null && text !== undefined) n.textContent = String(text);
  return n;
};
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function toast(msg, isErr = false) {
  const box = $(".toasts");
  const t = el("div", { class: "toast" + (isErr ? " err" : ""), text: msg });
  box.append(t);
  setTimeout(() => { t.style.opacity = "0"; t.style.transition = "opacity .25s"; setTimeout(() => t.remove(), 260); }, 3600);
}

/* ---------------- api ---------------- */
async function api(path, opts = {}) {
  const headers = Object.assign({}, opts.headers || {});
  if (state.token) headers.Authorization = "Bearer " + state.token;
  if (opts.json !== undefined) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(opts.json);
    delete opts.json;
  }
  let res = await fetch(API + path, Object.assign({}, opts, { headers }));

  // auto-refresh once on 401
  if (res.status === 401 && state.refresh && !opts._retried) {
    try {
      const r = await fetch(API + "/auth/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: state.refresh }),
      });
      if (r.ok) {
        const d = await r.json();
        saveAuth(d);
        return api(path, Object.assign({}, opts, { _retried: true }));
      }
    } catch (_) {}
    signOut(true);
    throw new Error("Session expired");
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
  if (!res.ok) {
    const msg = (data && (data.message || data.detail)) || ("HTTP " + res.status);
    throw new Error(typeof msg === "string" ? msg : JSON.stringify(msg));
  }
  return data;
}

function saveAuth(d) {
  state.token = d.access_token;
  state.refresh = d.refresh_token;
  state.user = d.user;
  localStorage.setItem("bc_token", d.access_token);
  localStorage.setItem("bc_refresh", d.refresh_token);
  localStorage.setItem("bc_user", JSON.stringify(d.user));
}

function signOut(silent) {
  const rt = state.refresh;
  state.token = state.refresh = state.user = null;
  localStorage.removeItem("bc_token");
  localStorage.removeItem("bc_refresh");
  localStorage.removeItem("bc_user");
  if (rt && !silent) {
    api("/auth/logout", { method: "POST", json: { refresh_token: rt } }).catch(() => {});
  }
  render();
}

/* ---------------- auth screen ---------------- */
function renderAuth() {
  const root = $("#root");
  root.innerHTML = "";
  let mode = "login";

  const email = el("input", { type: "email", placeholder: "you@example.com", autocomplete: "email" });
  const pass = el("input", { type: "password", placeholder: "••••••••", autocomplete: "current-password" });
  const name = el("input", { type: "text", placeholder: "Your name", autocomplete: "name" });
  const nameField = el("div", { class: "field hidden" },
    el("label", { text: "Display name" }), name);
  const errBox = el("div", { class: "hidden", style: "color:var(--danger);font-size:13px;margin-bottom:12px" });
  const submit = el("button", { class: "btn btn-primary btn-block", type: "submit" }, "Sign in");

  const form = el("form", {
    onsubmit: async (e) => {
      e.preventDefault();
      errBox.classList.add("hidden");
      submit.disabled = true;
      submit.textContent = "Working…";
      try {
        const path = mode === "login" ? "/auth/login" : "/auth/register";
        const body = { email: email.value.trim(), password: pass.value, device_name: "Web", app_version: "web-1.0.0", platform: "web" };
        if (mode === "register") body.display_name = name.value.trim();
        saveAuth(await api(path, { method: "POST", json: body }));
        render();
      } catch (ex) {
        errBox.textContent = ex.message;
        errBox.classList.remove("hidden");
      } finally {
        submit.disabled = false;
        submit.textContent = mode === "login" ? "Sign in" : "Create account";
      }
    },
  },
    el("div", { class: "field" }, el("label", { text: "Email" }), email),
    nameField,
    el("div", { class: "field" }, el("label", { text: "Password" }), pass),
    errBox,
    submit
  );

  const toggle = el("button", {
    class: "btn btn-ghost btn-block", type: "button",
    onclick: () => {
      mode = mode === "login" ? "register" : "login";
      nameField.classList.toggle("hidden", mode === "login");
      submit.textContent = mode === "login" ? "Sign in" : "Create account";
      $(".auth-card h1").textContent = mode === "login" ? "Welcome back" : "Create your account";
      $(".auth-card .sub").textContent = mode === "login"
        ? "Sign in to your self-hosted library"
        : "Start reading — your books stay on your server";
      toggle.textContent = mode === "login" ? "Need an account? Sign up" : "Have an account? Sign in";
    },
  }, "Need an account? Sign up");

  root.append(el("div", { class: "auth-wrap" },
    el("div", { class: "auth-card" },
      el("div", { class: "brand" }, el("div", { class: "brand-dot", text: "B" }), "BookCon"),
      el("h1", { text: "Welcome back" }),
      el("p", { class: "sub", text: "Sign in to your self-hosted library" }),
      form,
      el("div", { style: "height:14px" }),
      toggle
    )
  ));
}

/* ---------------- shell ---------------- */
const NAV = [
  { id: "home", label: "Home", ico: "⌂" },
  { id: "books", label: "Books", ico: "▤" },
  { id: "bookmarks", label: "Bookmarks", ico: "🔖" },
  { id: "profile", label: "Profile", ico: "☺" },
];

function navButton(n, mobile) {
  return el("button", {
    class: (mobile ? "mnav" : "nav-item") + (state.route === n.id ? " active" : ""),
    onclick: () => go(n.id),
  }, el("span", { class: "ico", text: n.ico }), el("span", { text: n.label }));
}

/** Navigate between tabs, remembering the choice across reloads. */
function go(id) {
  if (state.route === id) return;
  state.route = id;
  localStorage.setItem("bc_route", id);
  history.replaceState(null, "", "#/" + id);
  render();
  window.scrollTo({ top: 0 });
}

/* ---- hash routing: #/books | #/bookmarks | #/profile | #/book/<id> ---- */
const TABS = ["home", "books", "bookmarks", "profile"];

function readHash() {
  const h = (location.hash || "").replace(/^#\/?/, "");
  if (!h) return null;
  const [seg, arg] = h.split("/");
  if (seg === "book" && arg) return { route: state.route, bookId: decodeURIComponent(arg) };
  return TABS.includes(seg) ? { route: seg, bookId: null } : null;
}

function onHashChange() {
  const h = readHash();
  if (!h) return;
  if (h.route !== state.route) {
    state.route = h.route;
    localStorage.setItem("bc_route", h.route);
    render();
  }
  if (h.bookId) openBook(h.bookId, { fromHash: true });
  else document.querySelector(".modal-bg")?.remove();
}
window.addEventListener("hashchange", onHashChange);

function renderShell() {
  const root = $("#root");
  root.innerHTML = "";
  const u = state.user || {};
  const initials = (u.display_name || u.email || "?").trim().charAt(0).toUpperCase();

  const side = el("aside", { class: "sidebar" },
    el("div", { class: "brand" }, el("div", { class: "brand-dot", text: "B" }), "BookCon"),
    el("nav", { class: "nav" }, NAV.map((n) => navButton(n, false))),
    el("div", { class: "sidebar-foot" },
      el("div", { class: "user-chip" },
        el("div", { class: "avatar", text: initials }),
        el("div", { class: "meta" },
          el("div", { class: "nm", text: u.display_name || "Reader" }),
          el("div", { class: "em", text: u.email || "" })
        )
      ),
      el("button", { class: "nav-item", style: "margin-top:6px", onclick: () => signOut() },
        el("span", { class: "ico", text: "⏻" }), el("span", { text: "Sign out" }))
    )
  );

  const main = el("main", { class: "main", id: "view" });
  const mob = el("nav", { class: "mobile-nav" }, NAV.map((n) => navButton(n, true)));

  root.append(el("div", { class: "app" }, side, main), mob);
  renderRoute();
}

/* ---------------- routes ---------------- */
function renderRoute() {
  const v = $("#view");
  if (!v) return;
  v.innerHTML = "";
  ({ home: pageHome, books: pageBooks, bookmarks: pageBookmarks, profile: pageProfile }[state.route] || pageHome)(v);
}

function header(title, count, actions) {
  return el("div", { class: "page-head" },
    el("div", {}, el("h1", { text: title }), count !== null && count !== undefined ? el("div", { class: "count", text: count }) : null),
    actions ? el("div", { class: "head-actions" }, actions) : null
  );
}

function emptyState(icon, title, msg, action) {
  return el("div", { class: "empty" },
    el("div", { class: "ic", text: icon }),
    el("h2", { text: title }),
    el("p", { text: msg }),
    action || null
  );
}

/* ---- shared: book grid ---- */
function bookCard(b, pos) {
  const cover = b.cover_url
    ? el("img", { src: b.cover_url, alt: b.title, loading: "lazy", onerror: (e) => { e.target.replaceWith(el("div", { class: "initials", text: initialsOf(b.title) })); } })
    : el("div", { class: "initials", text: initialsOf(b.title) });
  const kids = [
    el("div", { class: "book-cover" }, cover, el("span", { class: "fmt", text: b.format || "?" })),
    el("h3", { text: b.title }),
    el("div", { class: "au", text: (b.authors && b.authors[0]) || "Unknown author" }),
  ];
  if (pos && pos.progress_percent) {
    const pct = Math.max(0, Math.min(100, Math.round(pos.progress_percent * 100)));
    kids.splice(1, 0, el("div", { class: "bar", style: "margin-top:2px" },
      el("i", { style: "width:" + pct + "%" })));
    kids.splice(2, 0, el("div", { class: "prog", text: pct + "% read" }));
  }
  return el("button", { class: "book", onclick: () => openBook(b.id) }, kids);
}

/** Load reading positions for a set of books (GET /positions needs book_ids). */
async function positionsFor(books) {
  if (!books.length) return {};
  try {
    const rows = await api("/positions?book_ids=" + encodeURIComponent(books.slice(0, 200).map((b) => b.id).join(",")));
    const by = {};
    (rows || []).forEach((p) => { if (p.progress_percent) by[p.book_id] = p; });
    return by;
  } catch (_) { return {}; }
}
const initialsOf = (t) => String(t || "?").split(/\s+/).slice(0, 2).map((w) => w[0] || "").join("").toUpperCase() || "?";

function searchBar(placeholder, onInput) {
  const inp = el("input", { type: "search", placeholder, value: state.search });
  let timer;
  inp.addEventListener("input", () => { state.search = inp.value; clearTimeout(timer); timer = setTimeout(onInput, 280); });
  return el("div", { class: "search-box" }, el("span", { class: "si", text: "⌕" }), inp);
}

function formatChips(onChange) {
  const mk = (key, label) => el("button", {
    class: "chip" + (state.format === key ? " on" : ""),
    onclick: () => { state.format = state.format === key ? null : key; onChange(); },
  }, label);
  return el("div", { class: "chips" }, mk("epub", "EPUB"), mk("pdf", "PDF"), mk("cbz", "CBZ"), mk(null, "All"));
}

/** Shelf filter chips + inline "new shelf" — all backed by /shelves + /books?shelf_id=. */
function shelfChips(onChange) {
  const row = el("div", { class: "chips", style: "margin-top:2px" });
  const addBtn = el("button", { class: "chip", onclick: async () => {
    const name = prompt("Name for the new shelf:");
    if (!name || !name.trim()) return;
    addBtn.disabled = true;
    try {
      const sh = await api("/shelves", { method: "POST", json: { name: name.trim() } });
      state.shelves.push(sh);
      toast('Shelf "' + sh.name + '" created');
      // Rebuild the row so the new chip appears, then re-filter.
      row.replaceWith(shelfChips(onChange));
      onChange();
    } catch (e) { toast(e.message, true); addBtn.disabled = false; }
  } }, "+ New shelf");

  const mk = (id, label) => el("button", {
    class: "chip" + (state.shelf === id ? " on" : ""),
    onclick: () => { state.shelf = state.shelf === id ? null : id; onChange(); },
  }, label);
  row.append(mk(null, "All shelves"));
  state.shelves.forEach((sh) => row.append(mk(sh.id, sh.name)));
  row.append(addBtn);
  return row;
}

async function loadShelves() {
  try { state.shelves = await api("/shelves") || []; } catch (_) { state.shelves = []; }
}

/* ---- Home ---- */
async function pageHome(v) {
  v.append(header("Welcome back", "Pick up where you left off or start something new",
    el("button", { class: "btn btn-import", onclick: () => go("books") }, "⬆  Import books")));

  const hero = el("div", { style: "display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px;margin-bottom:8px" });
  const recent = el("div", {});
  const cont = el("div", {});

  v.append(hero, el("h3", { style: "margin:26px 0 12px;font-size:17px" }, "Continue reading"), cont,
                el("h3", { style: "margin:32px 0 12px;font-size:17px" }, "Recently added"), recent);

  // --- stat cards from live data ---
  (async () => {
    const [books, bms] = await Promise.all([
      api("/books?limit=500").then((d) => d.items || []).catch(() => []),
      api("/bookmarks").then((d) => d || []).catch(() => []),
    ]);
    const card = (k, n, sub) => el("div", {
      style: "background:var(--card);border:1px solid var(--border);border-radius:14px;padding:18px 20px"
    },
      el("div", { style: "font-size:28px;font-weight:700;letter-spacing:-.6px", text: String(n) }),
      el("div", { style: "font-size:13px;color:var(--text);margin-top:2px", text: k }),
      el("div", { style: "font-size:12px;color:var(--muted);margin-top:2px", text: sub })
    );
    const fmts = [...new Set(books.map((b) => (b.format || "?").toUpperCase()))];
    hero.replaceChildren(
      card("Books in library", books.length, fmts.length ? fmts.join(" · ") : "Nothing imported yet"),
      card("Pages saved", bms.length, bms.length ? "bookmarks across all books" : "Bookmark while reading"),
      card("Storage", books.reduce((a, b) => a + (b.file_size_bytes || 0), 0) ? (books.reduce((a, b) => a + (b.file_size_bytes || 0), 0) / 1048576).toFixed(1) + " MB" : "0 MB", "across " + books.length + " file(s)")
    );
  })();

  // --- continue reading (driven by saved positions) ---
  (async () => {
    try {
      const all = (await api("/books?limit=500").then((d) => d.items || []).catch(() => []));
      if (!all.length) {
        cont.append(el("p", { style: "color:var(--muted);font-size:13.5px;padding:14px 0", text: "Nothing in progress yet — open a book to start reading." }));
        return;
      }
      // /positions requires explicit book_ids (comma-separated).
      const pos = await api("/positions?book_ids=" + encodeURIComponent(all.slice(0, 200).map((b) => b.id).join(",")));
      const byId = {};
      (pos || []).forEach((p) => { if (p.progress_percent) byId[p.book_id] = p; });
      const active = all.filter((b) => byId[b.id]).sort((a, b) => new Date(byId[b.id].updated_at) - new Date(byId[a.id].updated_at));
      if (!active.length) {
        cont.append(el("p", { style: "color:var(--muted);font-size:13.5px;padding:14px 0", text: "Nothing in progress yet — open a book to start reading." }));
        return;
      }
      const g = el("div", { class: "grid" });
      active.slice(0, 12).forEach((b) => g.append(progressCard(b, byId[b.id])));
      cont.replaceChildren(g);
    } catch (_) {
      cont.append(el("p", { style: "color:var(--muted);font-size:13.5px;padding:14px 0", text: "Reading progress is unavailable." }));
    }
  })();

  // --- recently added ---
  (async () => {
    try {
      const data = await api("/books?limit=12&sort=added");
      const items = data.items || [];
      if (!items.length) {
        recent.replaceWith(emptyState("▤", "Your library is empty",
          "Import EPUB, PDF or CBZ files to start building your shelf.",
          el("button", { class: "btn btn-import", onclick: () => go("books") }, "Import your first book")));
      } else {
        const g = el("div", { class: "grid" });
        items.forEach((b) => g.append(bookCard(b)));
        recent.replaceChildren(g);
      }
    } catch (e) {
      recent.replaceChildren(emptyState("⚠", "Could not load your library", e.message));
    }
  })();
}

/** Book card with a reading-progress bar (used by Continue reading). */
function progressCard(b, p) {
  const pct = Math.max(0, Math.min(100, Math.round((p.progress_percent || 0) * 100)));
  const cover = b.cover_url
    ? el("img", { src: b.cover_url, alt: b.title, loading: "lazy", onerror: (e) => { e.target.replaceWith(el("div", { class: "initials", text: initialsOf(b.title) })); } })
    : el("div", { class: "initials", text: initialsOf(b.title) });
  return el("button", { class: "book", onclick: () => openBook(b.id) },
    el("div", { class: "book-cover" }, cover,
      el("span", { class: "fmt", text: pct + "%" }),
      el("div", { class: "bar" }, el("i", { style: "width:" + pct + "%" }))),
    el("h3", { text: b.title }),
    el("div", { class: "au", text: (b.authors && b.authors[0]) || "Unknown author" })
  );
}

/* ---- Books ---- */
async function pageBooks(v) {
  const importBtn = el("button", { class: "btn btn-import", id: "imp", onclick: doUpload }, "⬆  Import books");
  v.append(header("My Library", null, importBtn));

  const gridWrap = el("div", { id: "gridwrap" });
  const sortSel = el("select", { style: "background:var(--card);border:1px solid var(--border);color:var(--text);border-radius:999px;padding:8px 14px;font-size:13px;font-family:inherit" },
    el("option", { value: "recent", text: "Recently updated" }),
    el("option", { value: "added", text: "Recently added" }),
    el("option", { value: "title", text: "Title A–Z" }),
    el("option", { value: "author", text: "Author" })
  );
  sortSel.value = state.sort;
  sortSel.addEventListener("change", () => { state.sort = sortSel.value; localStorage.setItem("bc_sort", state.sort); load(); });

  // Shelves must be loaded BEFORE the chips are built, otherwise the row
  // renders with only "All shelves" and never picks up the real names.
  await loadShelves();
  const shelfRow = shelfChips(() => load());

  v.append(
    el("div", { class: "toolbar" },
      el("div", { class: "search-row" }, searchBar("Search title or description", load), sortSel),
      formatChips(() => load()),
      shelfRow
    ),
    gridWrap
  );
  load();

  // Re-create the chip row in place after a shelf is added/removed.
  const refreshShelfRow = () => shelfRow.replaceWith(shelfChips(() => load()));

  async function load() {
    gridWrap.innerHTML = "";
    const msg = el("div", { class: "empty" }, el("div", { class: "ic", text: "▤" }), el("p", { text: "Loading…" }));
    gridWrap.append(msg);
    const qs = new URLSearchParams({ limit: "200", sort: state.sort });
    if (state.search.trim()) qs.set("q", state.search.trim());
    if (state.format) qs.set("format", state.format);
    if (state.shelf) qs.set("shelf_id", state.shelf);
    try {
      const data = await api("/books?" + qs.toString());
      state.books = data.items || [];
      const cnt = $("#view .page-head .count");
      if (cnt) cnt.textContent = state.books.length + (state.books.length === 1 ? " book" : " books");
      gridWrap.innerHTML = "";
      if (!state.books.length) {
        const filtered = state.search.trim() || state.format || state.shelf;
        gridWrap.append(emptyState("▤",
          filtered ? "No matching books" : "Your library is empty",
          filtered ? "Try a different search, or clear the format and shelf filters." : "Use the Import books button to add EPUB, PDF or CBZ files.",
          filtered ? el("button", { class: "btn btn-ghost", onclick: () => { state.search = ""; state.format = null; state.shelf = null; render(); } }, "Clear filters") : null));
      } else {
        const pos = await positionsFor(state.books);
        const g = el("div", { class: "grid" });
        state.books.forEach((b) => g.append(bookCard(b, pos[b.id])));
        gridWrap.append(g);
      }
    } catch (e) {
      gridWrap.replaceChildren(emptyState("⚠", "Could not load books", e.message));
    }
  }
}

/* ---- Upload (3-step flow the API supports) ---- */
async function doUpload() {
  const input = el("input", { type: "file", accept: ".epub,.pdf,.cbz,.cbr", multiple: "multiple" });
  input.addEventListener("change", async () => {
    const files = Array.from(input.files || []);
    for (const f of files) await uploadOne(f);
  });
  input.click();
}

async function uploadOne(file) {
  const t = toast(`Uploading ${file.name}…`);
  try {
    const buf = await file.arrayBuffer();
    let sha;
    if (window.crypto && crypto.subtle) {
      const h = await crypto.subtle.digest("SHA-256", buf);
      sha = Array.from(new Uint8Array(h)).map((b) => b.toString(16).padStart(2, "0")).join("");
    } else {
      // SubtleCrypto is unavailable over plain HTTP on non-localhost hosts.
      throw new Error("Uploads need HTTPS or localhost (secure context) for SHA-256");
    }
    const ext = (file.name.split(".").pop() || "").toLowerCase();
    const ctype = ext === "epub" ? "application/epub+zip" : ext === "pdf" ? "application/pdf" : ext === "cbz" ? "application/vnd.comicbook+zip" : "application/vnd.comicbook-rar";
    const init = await api("/books/initiate-upload", {
      method: "POST",
      json: { filename: file.name, size_bytes: file.size, sha256: sha, content_type: ctype },
    });
    await api("/books/" + init.book_id + "/file", {
      method: "PUT",
      headers: { "Content-Type": ctype },
      body: buf,
    });
    await api("/books/" + init.book_id + "/complete-upload", { method: "POST" });
    t.remove();
    toast("Imported " + file.name);
    if (state.route === "books") render();
  } catch (e) {
    t.remove();
    toast(e.message, true);
  }
}

/* ---- Book detail modal ---- */
async function openBook(id, opts) {
  opts = opts || {};
  // Close any modal already open before opening a new one.
  document.querySelector(".modal-bg")?.remove();
  const bg = el("div", { class: "modal-bg" });
  bg.dataset.bookId = id;
  bg.addEventListener("click", (e) => { if (e.target === bg) close(); });
  const close = () => {
    bg.remove();
    if (location.hash.startsWith("#/book/")) history.replaceState(null, "", "#/" + state.route);
  };
  document.addEventListener("keydown", function onKey(e) {
    if (e.key === "Escape" && document.body.contains(bg)) { close(); document.removeEventListener("keydown", onKey); }
  });
  if (!opts.fromHash) history.replaceState(null, "", "#/book/" + encodeURIComponent(id));
  bg.append(el("div", { class: "modal", style: "padding:40px;text-align:center;color:var(--muted)" }, "Loading…"));
  document.body.append(bg);

  let b;
  try { b = await api("/books/" + id); }
  catch (e) { bg.replaceChildren(el("div", { class: "modal", style: "padding:34px" }, el("h2", { text: "Could not load book" }), el("p", { class: "desc", text: e.message }))); return; }

  const cover = b.cover_url
    ? el("img", { src: b.cover_url, alt: b.title, onerror: (e) => e.target.replaceWith(el("div", { class: "initials", text: initialsOf(b.title) })) })
    : el("div", { class: "initials", text: initialsOf(b.title) });

  const stat = (k, v) => el("div", { class: "stat" }, el("div", { class: "k", text: k }), el("div", { class: "v", text: v || "—" }));

  const dl = el("button", {
    class: "btn btn-primary",
    onclick: async () => {
      dl.disabled = true;
      try {
        const u = await api("/books/" + id + "/file-url?download=true");
        window.open(u.url, "_blank");
      } catch (e) { toast(e.message, true); }
      finally { dl.disabled = false; }
    },
  }, "⬇  Download");

  const del = el("button", {
    class: "btn btn-danger",
    onclick: async () => {
      if (!confirm('Delete "' + b.title + '"? This cannot be undone.')) return;
      try { await api("/books/" + id, { method: "DELETE" }); close(); toast("Deleted"); render(); }
      catch (e) { toast(e.message, true); }
    },
  }, "Delete");

  const statusLine = b.status && b.status !== "ready" ? el("p", { style: "color:var(--muted);font-size:13px;margin-top:10px", text: "Status: " + b.status + (b.status_message ? " — " + b.status_message : "") }) : null;

  /* ---- edit mode: PATCH /books/{id} (title, authors, description, shelves) ---- */
  let editBtn;
  const modal = el("div", { class: "modal" },
    el("div", { class: "modal-hero" }, el("button", { class: "back", onclick: close, title: "Back" }, "‹")),
    el("div", { class: "modal-body" },
      el("div", { class: "modal-cover" }, cover),
      el("h2", { text: b.title }),
      el("div", { class: "au", text: (b.authors && b.authors.length ? b.authors.join(", ") : "Unknown author") }),
      el("div", { class: "stat-row" },
        stat("Format", (b.format || "").toUpperCase()),
        stat("Pages", b.page_count ? b.page_count + " pgs" : "—"),
        stat("Language", b.language ? b.language.toUpperCase() : "—"),
        b.publisher ? stat("Publisher", b.publisher) : null
      ),
      statusLine,
      el("div", { class: "desc" },
        el("h3", { text: "Introduction" }),
        el("p", { text: b.description || "No description available." })
      ),
      el("div", { class: "modal-actions" }, dl, editBtn, del)
    )
  );

  const fTitle = el("input", { type: "text", value: b.title, placeholder: "Title" });
  const fAuthors = el("input", { type: "text", value: (b.authors || []).join(", "), placeholder: "Authors, comma separated" });
  const fDesc = el("textarea", { placeholder: "Description" });
  fDesc.value = b.description || "";

  let shelves = [];
  try { shelves = await api("/shelves").catch(() => []); } catch (_) {}
  const current = new Set(b.shelf_ids || []);
  const shelfBox = el("div", { class: "chips" });
  shelves.forEach((sh) => {
    const cb = el("input", { type: "checkbox", style: "margin-right:6px" });
    cb.checked = current.has(sh.id);
    cb.dataset.shelfId = sh.id;
    shelfBox.append(el("label", {
      class: "chip", style: "display:inline-flex;align-items:center;cursor:pointer",
    }, cb, sh.name));
  });

  const saveBtn = el("button", { class: "btn btn-primary", onclick: async () => {
    saveBtn.disabled = true;
    const old = saveBtn.textContent;
    saveBtn.textContent = "Saving…";
    try {
      const shelfIds = Array.from(shelfBox.querySelectorAll("input:checked")).map((i) => i.dataset.shelfId);
      await api("/books/" + id, {
        method: "PATCH",
        json: {
          title: fTitle.value.trim() || b.title,
          authors: fAuthors.value.split(",").map((a) => a.trim()).filter(Boolean),
          description: fDesc.value.trim(),
          shelf_ids: shelfIds,
        },
      });
      toast("Saved");
      close();
      render();
    } catch (e) { toast(e.message, true); saveBtn.disabled = false; saveBtn.textContent = old; }
  } }, "Save changes");

  const form = el("div", { hidden: "hidden", style: "margin-top:18px;padding-top:18px;border-top:1px solid var(--border)" },
    el("div", { class: "field" }, el("label", { text: "Title" }), fTitle),
    el("div", { class: "field" }, el("label", { text: "Authors" }), fAuthors),
    el("div", { class: "field" }, el("label", { text: "Description" }), fDesc),
    shelves.length ? el("div", { class: "field" }, el("label", { text: "Shelves" }), shelfBox) : null,
    el("div", { style: "display:flex;gap:9px" }, saveBtn,
      el("button", { class: "btn btn-ghost", onclick: () => form.setAttribute("hidden", "hidden") }, "Cancel"))
  );
  editBtn = el("button", {
    class: "btn btn-ghost",
    onclick: () => { form.removeAttribute("hidden"); editBtn.disabled = true; },
  }, "✎  Edit");

  // BM-1: bookmark the book's current reading position (falls back to the start
  // of the book when nothing has been read yet). The web client has no reader,
  // so this is the only way to create a bookmark from here.
  const bmBtn = el("button", { class: "btn btn-ghost", onclick: async () => {
    bmBtn.disabled = true;
    const was = bmBtn.textContent;
    bmBtn.textContent = "Saving…";
    try {
      const all = await api("/books?limit=200").then((d) => d.items || []).catch(() => []);
      const ids = all.slice(0, 200).map((b) => b.id);
      const pos = ids.length
        ? await api("/positions?book_ids=" + encodeURIComponent(ids.join(","))).catch(() => [])
        : [];
      const here = (pos || []).find((p) => p.book_id === id);
      const loc = (here && here.locator) || { href: "/" };
      await api("/bookmarks", {
        method: "POST",
        json: { book_id: id, locator: loc, label: "" },
      });
      toast(here ? "Bookmarked at " + Math.round(here.progress_percent * 100) + "%" : "Bookmarked");
      bmBtn.textContent = "🔖 Bookmarked";
    } catch (e) { toast(e.message, true); bmBtn.disabled = false; bmBtn.textContent = was; }
  } }, "🔖  Bookmark");

  // `editBtn` is referenced inside the modal literal above but only assigned
  // here, so insert it into the action row explicitly — el() drops the
  // undefined child at build time.
  const actions = modal.querySelector(".modal-actions");
  actions.insertBefore(editBtn, del);
  actions.insertBefore(bmBtn, editBtn);
  actions.after(form);
  bg.replaceChildren(modal);
}

/* ---- Bookmarks ---- */
async function pageBookmarks(v) {
  v.append(header("Bookmarks", null, null));
  const wrap = el("div", {});
  v.append(wrap);
  try {
    state.bookmarks = await api("/bookmarks");
    if (!state.bookmarks.length) {
      wrap.append(emptyState("🔖", "No bookmarks yet", "Bookmark a page while reading on any device and it will show up here."));
      return;
    }
    const ids = [...new Set(state.bookmarks.map((x) => x.book_id))];
    const titles = {};
    await Promise.all(ids.map(async (bid) => {
      try { const b = await api("/books/" + bid); titles[bid] = b.title; } catch (_) { titles[bid] = "Unknown book"; }
    }));
    const rows = el("div", { class: "rows" });
    state.bookmarks.forEach((bm) => {
      const when = bm.created_at ? new Date(bm.created_at).toLocaleString() : "";
      const labelEl = el("div", { class: "t", text: titles[bm.book_id] || "Unknown book" });
      const sub = el("div", { class: "s", text: (bm.label || "Bookmark") + (when ? " · " + when : "") });

      // Click the sub-line to rename the bookmark (PATCH /bookmarks/{id}).
      sub.style.cursor = "text";
      sub.title = "Click to rename";
      sub.addEventListener("click", async () => {
        const next = prompt("Bookmark label:", bm.label || "");
        if (next === null) return;
        try {
          await api("/bookmarks/" + bm.id, { method: "PATCH", json: { label: next.trim() } });
          bm.label = next.trim();
          sub.textContent = (next.trim() || "Bookmark") + (when ? " · " + when : "");
          toast("Bookmark renamed");
        } catch (e) { toast(e.message, true); }
      });

      rows.append(el("div", { class: "row" },
        el("div", { class: "ic", text: "🔖" }),
        el("div", { class: "tx" }, labelEl, sub),
        el("button", { class: "btn btn-ghost btn-sm", onclick: () => openBook(bm.book_id) }, "Open"),
        el("button", {
          class: "btn btn-danger btn-sm",
          onclick: async () => { try { await api("/bookmarks/" + bm.id, { method: "DELETE" }); render(); } catch (e) { toast(e.message, true); } },
        }, "Delete")
      ));
    });
    wrap.append(rows);
  } catch (e) {
    wrap.append(emptyState("⚠", "Could not load bookmarks", e.message));
  }
}

/* ---- Profile ---- */
async function pageProfile(v) {
  v.append(header("Profile", state.user ? state.user.email : ""));

  const mkRow = (icon, title, sub, right) => el("div", { class: "row" },
    el("div", { class: "ic", text: icon }),
    el("div", { class: "tx" },
      el("div", { class: "t", text: title }),
      sub ? el("div", { class: "s", text: sub }) : null),
    right || null
  );

  // --- account block: display name editor ---
  const nameInput = el("input", { type: "text", value: state.user?.display_name || "", placeholder: "Your display name" });
  const nameSave = el("button", { class: "btn btn-primary btn-sm", onclick: async () => {
    nameSave.disabled = true;
    try {
      const u = await api("/me", { method: "PATCH", json: { display_name: nameInput.value.trim() } });
      state.user = u; localStorage.setItem("bc_user", JSON.stringify(u));
      toast("Profile updated");
      render();
    } catch (e) { toast(e.message, true); nameSave.disabled = false; }
  } }, "Save");

  v.append(
    el("h3", { style: "margin:0 0 12px;font-size:15px" }, "Account"),
    el("div", { class: "card", style: "background:var(--card);border:1px solid var(--border);border-radius:14px;padding:16px;max-width:620px" },
      el("div", { class: "field" }, el("label", { text: "Display name" }), nameInput),
      el("div", { class: "field" }, el("label", { text: "Email" }),
        el("input", { type: "email", value: state.user?.email || "", disabled: true, style: "opacity:.6" })),
      el("div", { style: "display:flex;gap:9px" }, nameSave)
    )
  );

  // --- library stats ---
  const stats = el("div", { class: "rows", style: "margin-top:22px;max-width:620px" },
    el("div", { class: "row" }, el("div", { class: "ic", text: "▤" }),
      el("div", { class: "tx" }, el("div", { class: "t", text: "Loading your library…" }))));
  v.append(el("h3", { style: "margin:26px 0 12px;font-size:15px" }, "Your library"), stats);

  (async () => {
    const [books, bms, sh, tg, dv] = await Promise.all([
      api("/books?limit=500").then((d) => (d.items || []).length).catch(() => 0),
      api("/bookmarks").then((d) => (d || []).length).catch(() => 0),
      api("/shelves").then((d) => (d || []).length).catch(() => 0),
      api("/tags").then((d) => (d || []).length).catch(() => 0),
      api("/devices").then((d) => (d || []).length).catch(() => 0),
    ]);
    stats.replaceChildren(
      mkRow("▤", "Books", books + (books === 1 ? " book" : " books") + " in your library"),
      mkRow("🔖", "Bookmarks", bms + (bms === 1 ? " page" : " pages") + " saved"),
      mkRow("🗂", "Shelves", sh + (sh === 1 ? " shelf" : " shelves")),
      mkRow("#", "Tags", tg + (tg === 1 ? " tag" : " tags")),
      mkRow("📱", "Devices", dv + (dv === 1 ? " device" : " devices") + " signed in")
    );
  })();

  // --- devices ---
  const devWrap = el("div", { class: "rows", style: "max-width:620px" });
  v.append(el("h3", { style: "margin:26px 0 12px;font-size:15px" }, "Devices"), devWrap);
  devWrap.append(el("div", { class: "row" }, el("div", { class: "ic", text: "📱" }),
    el("div", { class: "tx" }, el("div", { class: "t", text: "Loading devices…" }))));
  try {
    const devs = await api("/devices");
    if (!devs.length) {
      devWrap.replaceChildren(el("p", { style: "color:var(--muted);font-size:13.5px", text: "No signed-in devices." }));
    } else {
      const rows = el("div", { class: "rows" });
      devs.forEach((d) => rows.append(el("div", { class: "row" },
        el("div", { class: "ic", text: "📱" }),
        el("div", { class: "tx" },
          el("div", { class: "t", text: d.name }),
          el("div", { class: "s", text: d.platform + " · v" + d.app_version + " · " + (d.last_seen_at ? "last seen " + new Date(d.last_seen_at).toLocaleString() : "never seen") })),
        el("button", {
          class: "btn btn-danger btn-sm",
          onclick: async () => { try { await api("/devices/" + d.id, { method: "DELETE" }); toast("Device revoked"); render(); } catch (e) { toast(e.message, true); } },
        }, "Revoke")
      )));
      devWrap.replaceChildren(rows);
    }
  } catch (e) {
    devWrap.replaceChildren(el("p", { style: "color:var(--muted);font-size:13.5px", text: e.message }));
  }

  v.append(
    el("h3", { style: "margin:26px 0 12px;font-size:15px" }, "Session"),
    el("div", { style: "max-width:620px" },
      el("button", { class: "btn btn-danger btn-block", onclick: () => signOut() }, "⏻  Sign out"))
  );
}

/* ---------------- render ---------------- */
function render() {
  if (!state.token) return renderAuth();
  // Honour a deep link on first paint: #/book/<id> and #/<tab>.
  const h = readHash();
  if (h) {
    if (h.route !== state.route) { state.route = h.route; localStorage.setItem("bc_route", h.route); }
    renderShell();
    if (h.bookId) openBook(h.bookId, { fromHash: true });
    return;
  }
  renderShell();
}

document.addEventListener("DOMContentLoaded", render);
if (document.readyState !== "loading") render();
