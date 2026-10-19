#!/usr/bin/env python3
"""
Throwaway seed-data generator (NOT part of the shipped Spring Boot app).

Splits the OCR "layout" text dump of the sample loan package into per-page
text files plus a package.json manifest describing loan -> document -> version -> page.

Heuristic: a line matching "Page (\d+) of (\d+)" is a page-footer. A footer
with page number 1 starts a new logical document. Pages within a document
are split at successive footer lines. Document title is inferred from the
first non-blank line of the document's first page.

Paths below are hardcoded to the machine this was generated on (one-off
script, not meant to be rerun elsewhere) -- adjust SRC/OUT_DIR to regenerate.
"""
import json
import re
import sys
from pathlib import Path

SRC = Path("/Users/saurav/Downloads/Loan Package Search Assignment ZIP/package_seed20260911_layout (2).txt")
OUT_DIR = Path("/Users/saurav/Downloads/loan-package-search/data/seed")
DOCS_DIR = OUT_DIR / "docs"

PAGE_RE = re.compile(r"Page\s+(\d+)\s+of\s+(\d+)")

def main():
    lines = SRC.read_text(encoding="utf-8", errors="replace").split("\n")

    markers = []  # (line_idx, page_num, total_pages)
    for i, line in enumerate(lines):
        m = PAGE_RE.search(line)
        if m:
            markers.append((i, int(m.group(1)), int(m.group(2))))

    if not markers:
        print("No page markers found", file=sys.stderr)
        sys.exit(1)

    # Group markers into documents: a page_num == 1 marker starts a new document.
    docs = []  # each: {"start_marker_idx": i, "markers": [...]}
    current = None
    for marker in markers:
        _, page_num, _ = marker
        if page_num == 1 or current is None:
            current = {"markers": [marker]}
            docs.append(current)
        else:
            current["markers"].append(marker)

    # Compute line ranges for each document: start = prev doc's end+1 (0 for first),
    # end = line index of this doc's last marker (inclusive).
    doc_ranges = []
    prev_end = -1
    for d in docs:
        start_line = prev_end + 1
        end_line = d["markers"][-1][0]
        doc_ranges.append((start_line, end_line, d["markers"]))
        prev_end = end_line
    # extend last doc to EOF
    start_line, end_line, mk = doc_ranges[-1]
    doc_ranges[-1] = (start_line, len(lines) - 1, mk)

    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    manifest_docs = []

    for d_idx, (doc_start, doc_end, mks) in enumerate(doc_ranges, start=1):
        page_count = mks[-1][1]
        # per-page split points within [doc_start, doc_end]
        page_bounds = []
        prev_page_end = doc_start - 1
        for marker_line, page_num, total in mks:
            page_bounds.append((prev_page_end + 1, marker_line))
            prev_page_end = marker_line
        # last page runs to doc_end if doc_end > last marker line (trailing content)
        if doc_end > mks[-1][0]:
            last_start, last_line = page_bounds[-1]
            page_bounds[-1] = (last_start, doc_end)

        # title: first non-blank, reasonably long line within the doc
        title = None
        for ln in lines[doc_start:min(doc_start + 15, doc_end + 1)]:
            stripped = ln.strip()
            if len(stripped) >= 8:
                title = re.sub(r"\s{2,}", " ", stripped)[:80]
                break
        if not title:
            title = f"Document {d_idx}"

        doc_dir_name = f"doc-{d_idx:03d}"
        doc_dir = DOCS_DIR / doc_dir_name
        doc_dir.mkdir(exist_ok=True)

        for page_num, (p_start, p_end) in enumerate(page_bounds, start=1):
            text = "\n".join(lines[p_start:p_end + 1]).strip()
            (doc_dir / f"page-{page_num}.txt").write_text(text, encoding="utf-8")

        manifest_docs.append({
            "id": doc_dir_name,
            "title": title,
            "documentType": title,
            "version": 1,
            "label": None,
            "pageCount": page_count,
            "textDir": f"docs/{doc_dir_name}",
        })

    # Known special case from manual inspection of this sample: doc-001 and
    # doc-002 are the same Uniform Residential Loan Application form, filed
    # once per co-borrower. Tag them as two versions of one document type to
    # demonstrate type/version grouping (see README for why this is manual).
    by_id = {d["id"]: d for d in manifest_docs}
    if "doc-001" in by_id and "doc-002" in by_id:
        by_id["doc-001"].update({
            "documentType": "Uniform Residential Loan Application",
            "version": 1,
            "label": "John Doe",
        })
        by_id["doc-002"].update({
            "documentType": "Uniform Residential Loan Application",
            "version": 2,
            "label": "Jane Doe",
        })

    manifest = {
        "loanId": "loan-001",
        "loanName": "ABC Mortgage - 1234567890 - John & Jane Doe",
        "documents": manifest_docs,
    }
    (OUT_DIR / "package.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"Documents: {len(manifest_docs)}")
    print(f"Total pages: {sum(d['pageCount'] for d in manifest_docs)}")

if __name__ == "__main__":
    main()
