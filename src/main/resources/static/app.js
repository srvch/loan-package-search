const state = { query: "", page: 0, pageSize: 10 };

const el = (id) => document.getElementById(id);

function escapeHtml(s) {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

async function loadTree() {
  const res = await fetch("/api/tree");
  const data = await res.json();
  const container = el("tree");
  container.innerHTML = "";
  for (const group of data.documentTypes) {
    const div = document.createElement("div");
    div.className = "tree-type";
    const name = document.createElement("div");
    name.className = "tree-type-name";
    name.textContent = `${group.documentType} (${group.versions.length})`;
    div.appendChild(name);
    for (const v of group.versions) {
      const vDiv = document.createElement("div");
      vDiv.className = "tree-version";
      const label = v.label ? ` — ${v.label}` : "";
      vDiv.textContent = `v${v.version}${label}, ${v.pageCount} pages`;
      vDiv.addEventListener("click", () => openPage(v.id, 1, v.title, v.pageCount));
      div.appendChild(vDiv);
    }
    container.appendChild(div);
  }
}

async function runSearch() {
  const q = el("query").value.trim();
  if (!q) return;
  state.query = q;
  const params = new URLSearchParams({ q, page: state.page, pageSize: state.pageSize });
  const res = await fetch(`/api/search?${params}`);
  const resultsEl = el("results");
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: "Search failed" }));
    resultsEl.innerHTML = `<div class="error">${escapeHtml(body.error || "Search failed")}</div>`;
    el("pagination").innerHTML = "";
    el("timings").textContent = "";
    return;
  }
  const data = await res.json();
  renderResults(data);
  renderPagination(data);
  renderTimings(data);
}

function renderResults(data) {
  const resultsEl = el("results");
  resultsEl.innerHTML = "";
  if (data.hits.length === 0) {
    resultsEl.innerHTML = `<div>No results for "${escapeHtml(data.query)}".</div>`;
    return;
  }
  for (const hit of data.hits) {
    const div = document.createElement("div");
    div.className = "hit";
    const label = hit.label ? ` — ${hit.label}` : "";
    div.innerHTML = `
      <div class="hit-title">${escapeHtml(hit.docTitle)}</div>
      <div class="hit-meta">${escapeHtml(hit.documentType)}${label} · page ${hit.pageNumber} of ${hit.pageCount} · score ${hit.score.toFixed(2)}</div>
      <div class="hit-snippet">${hit.snippet}</div>
    `;
    div.addEventListener("click", () => openPage(hit.docId, hit.pageNumber, hit.docTitle, hit.pageCount));
    resultsEl.appendChild(div);
  }
}

function renderPagination(data) {
  const pEl = el("pagination");
  pEl.innerHTML = "";
  const totalPages = Math.max(1, Math.ceil(data.total / data.pageSize));
  const prev = document.createElement("button");
  prev.textContent = "Prev";
  prev.disabled = state.page <= 0;
  prev.addEventListener("click", () => {
    state.page = Math.max(0, state.page - 1);
    runSearch();
  });
  const next = document.createElement("button");
  next.textContent = "Next";
  next.disabled = state.page >= totalPages - 1;
  next.addEventListener("click", () => {
    state.page += 1;
    runSearch();
  });
  const info = document.createElement("span");
  info.textContent = ` Page ${state.page + 1} of ${totalPages} (${data.total} results) `;
  pEl.appendChild(prev);
  pEl.appendChild(info);
  pEl.appendChild(next);
}

function renderTimings(data) {
  const t = data.timings;
  el("timings").textContent =
    `cache:${t.servedFromCache} stale:${t.staleCheckMs}ms reindex:${t.reindexMs}ms ` +
    `parse:${t.queryParseMs}ms search:${t.luceneSearchMs}ms snippet:${t.snippetGenMs}ms total:${t.totalMs}ms`;
}

let viewerDoc = null;

async function openPage(docId, pageNumber, docTitle, pageCount) {
  viewerDoc = { docId, pageNumber, docTitle, pageCount };
  await renderViewer();
}

async function renderViewer() {
  if (!viewerDoc) return;
  const { docId, pageNumber, docTitle, pageCount } = viewerDoc;
  const res = await fetch(`/api/documents/${encodeURIComponent(docId)}/pages/${pageNumber}`);
  const viewerEl = el("viewer");
  if (!res.ok) {
    viewerEl.textContent = "Failed to load page.";
    return;
  }
  const data = await res.json();
  viewerEl.textContent = data.textMissing
    ? `[OCR text not available for ${docTitle}, page ${pageNumber}]`
    : data.text;

  const navEl = el("viewer-nav");
  navEl.innerHTML = "";
  const prev = document.createElement("button");
  prev.textContent = "◀ Prev page";
  prev.disabled = pageNumber <= 1;
  prev.addEventListener("click", () => openPage(docId, pageNumber - 1, docTitle, pageCount));
  const next = document.createElement("button");
  next.textContent = "Next page ▶";
  next.disabled = pageNumber >= pageCount;
  next.addEventListener("click", () => openPage(docId, pageNumber + 1, docTitle, pageCount));
  const info = document.createElement("span");
  info.textContent = ` ${docTitle} — page ${pageNumber} of ${pageCount} `;
  navEl.appendChild(prev);
  navEl.appendChild(info);
  navEl.appendChild(next);
}

el("search-form").addEventListener("submit", (e) => {
  e.preventDefault();
  state.page = 0;
  runSearch();
});

loadTree();
