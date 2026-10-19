# Loan Package Search

Full-text search over a loan package's documents — boolean operators, exact
phrases, wildcards, ranked results with snippets, and a page viewer, with
live reindexing when the package manifest changes.

**AI tool used:** Claude Code (Anthropic), for design, implementation, and this README.

> **Note on the assignment's AI-usage log:** the instructions reference an
> `AGENTS.md` that auto-writes a prompt log to `~/infrrd_loan_package_search/log.txt`.
> That file wasn't present among the materials shared for this exercise (only
> `Java_Loan_Package_Assignment.md`, the sample PDF, and the OCR layout `.txt`
> were provided — see "What's missing" below), so no such log was generated.

## How to run

```
mvn spring-boot:run
```

Then open http://localhost:8080. The seed loan package lives at
`data/seed/package.json` plus `data/seed/docs/<doc-id>/page-N.txt` — edit it
while the app is running and the next search/tree request will pick up the
change (see "Correctness under change" below).

Run the tests with `mvn test`.

## What's missing from the assignment materials

The one-page brief (`Java_Loan_Package_Assignment.md`) references a full
`Loan_Package_Text_Search_Assignment.zip` containing the real `ASSIGNMENT.md`
(detailed requirements/scoring), an `AGENTS.md`, and presumably a
`package.json` schema. That zip wasn't included in what was shared — only the
brief, the ~550-page sample PDF, and an OCR "layout" text dump were present.
Everything below (the `package.json` schema, the loan→document→version→page
model, the tree-grouping behavior) is my own design, built from the brief's
"Key Requirements at a Glance" section and the shape of the sample data. This
was called out and confirmed with the requester before starting.

## Design decisions and trade-offs

**Search engine: Apache Lucene, not a hand-rolled index.** A custom inverted
index would demonstrate more of the underlying mechanics, but costs
meaningfully more implementation time (tokenizer, postings, a boolean/phrase/
wildcard query parser, BM25 scoring, a highlighter) for a worse-tested result.
Lucene gives production-grade boolean/phrase/wildcard query syntax, BM25
ranking, and a highlighter largely for free, which better fits the assignment's
timebox. This was an explicit trade-off discussed with the requester
up front.

**One Lucene document per page.** Rather than one document per loan-package
file, each page is its own indexed unit (`docId`, `documentType`, `version`,
`label`, `pageNumber`, `pageCount`, `content`). This is what makes "land on
the exact page" a direct, single-lookup result rather than a secondary
within-document search after finding a match.

**`package.json` is a flat list of documents; the tree is computed, not
stored.** Each entry has a `documentType` and a `version` (default 1).
`/api/tree` groups the flat list by `documentType` on every call — cheap at
this scale (dozens of documents) and means there's exactly one source of
truth for a document's identity instead of keeping a flat index and a nested
tree in sync. The two Uniform Residential Loan Application copies (one per
co-borrower) are tagged as versions 1 and 2 of one type to demonstrate the
grouping; see "Known limitations" for why that tagging is manual rather than
inferred.

**On-disk `FSDirectory` index, wiped and rebuilt wholesale on change.**
Simpler than incremental updates, and defensible at this scale (the whole
sample re-indexes in under a second — see "Performance" below). Persistence
across restarts is explicitly out of scope, so there's no need for the index
to survive a process restart; wiping it on every rebuild also means the
index's size is bounded by "one package's worth of pages," not by how many
rebuilds have happened.

**Correctness under change: check `package.json`'s mtime at the top of every
request, not a background file watcher.** `PackageRepository.isStale()`
compares the file's last-modified time against the timestamp recorded at last
load; `SearchService.ensureFresh()` calls this before every search and
reindexes synchronously if it's stale. This satisfies "the next request must
reflect it" literally, is simple to reason about with a single reviewer using
the tool, and the reindex itself shows up as a timed stage (`reindexMs`) in
the response — turning the live-edit requirement into an observable, testable
code path rather than a background thread racing with searches.
`SearchServiceTest.packageJsonEditIsReflectedOnNextSearch` exercises this
directly: it edits `package.json` and a page file mid-test and asserts the
very next search finds the new content.

**Repeat-search speed: an LRU cache of (query, page, pageSize, index
version) → response**, capped at 200 entries (`app.query-cache-size`),
cleared whenever the index is rebuilt (so a stale cache entry can never be
served across a reindex). An identical repeat query returns in ~0ms,
`servedFromCache: true`, versus tens of milliseconds for a fresh Lucene
search — see numbers below.

**Observability: every search response carries per-stage timings**
(`staleCheckMs`, `reindexMs`, `queryParseMs`, `luceneSearchMs`,
`snippetGenMs`, `totalMs`, `servedFromCache`), shown in the UI's header strip
and usable directly for the performance claims in this README.

**Memory bound.** The Lucene index itself lives in `FSDirectory` (disk +
OS page cache), not the JVM heap, and is rebuilt in place rather than
accumulating historical versions, so its footprint tracks "current package
size," not "how long the process has been running." The one heap-resident
structure that could otherwise grow unbounded is the query cache, which is
why it's a capped LRU (200 entries) rather than an unbounded map — worst case
it holds 200 small result payloads, not a function of how many searches have
run.

## Performance numbers (measured)

Sample: 56 documents, 201 logical pages (see "known limitations" for why this
is lower than the ~560 physical PDF pages), ~1.4MB of OCR text. Measured on a
MacBook (Apple Silicon), `java -jar target/loan-package-search-0.1.0.jar`,
three cold-start runs:

| Run | Index build time (Root context ready → index rebuilt log line) |
|---|---|
| 1 | 753 ms |
| 2 | 985 ms |
| 3 | 746 ms |

Cold **total app startup** (JVM + Spring Boot + index build) was 2.5–2.9s
across the same runs — most of that is Spring Boot framework startup, not
indexing.

Per-request timings from the running app (`/api/search` response `timings`):

| Query | queryParseMs | luceneSearchMs | snippetGenMs | totalMs | 2nd identical call |
|---|---|---|---|---|---|
| `"cash to close"` (phrase) | 0 | 0–1 | ~15–20 | ~15–20 | **0ms, `servedFromCache: true`** |
| `income AND NOT employer` | 2 | 7 | 19 | 28 | 0ms, cached |

Snippet generation (the highlighter re-tokenizing matched pages) is
consistently the largest contributor to a cold search's time — more than
query parsing or the Lucene search itself — which is the kind of thing the
per-stage timings are meant to surface rather than hide behind one aggregate
number.

## Known limitations

- **Page-boundary detection is heuristic and incomplete.** The seed-data
  parser (`tools/split_layout.py` — throwaway, not part of the shipped app)
  splits the ~17,000-line OCR layout dump into documents/pages by matching
  `Page X of Y` footer lines. That pattern only appears on about 201 of the
  PDF's ~560 physical pages — forms like the Closing Disclosure, Form 1040,
  and the brokerage statements use different or no page-footer conventions,
  so their content gets folded into one oversized logical "page" per
  document (one is ~165KB of text) rather than split per physical page.
  This is a known, documented trade-off, not a silent bug: real OCR/layout
  analysis is explicitly out of scope, and the heuristic is good enough to
  exercise every required search behavior, it just under-splits a handful of
  forms. With more time, per-form footer patterns (page N / page N of M /
  "Page" as a standalone line / no footer at all) would need their own
  detection rules, or real per-page OCR metadata instead of a plain text
  dump.
- **Document type/version grouping for the two URLA copies is manually
  tagged**, not inferred, because the two borrowers' copies have different
  first-page headers and no shared machine-readable title; a real pipeline
  would get this from loan origination system metadata, not derived from OCR
  text.
- **Wildcards only support trailing `*`/`?`** (Lucene's
  `setAllowLeadingWildcard(false)`); `*sign` is rejected, only `sign*` works,
  matching the assignment's own example.
- **`StandardAnalyzer`'s default English stopword removal** interacts with
  phrase queries via position-increment gaps rather than true adjacency;
  not stress-tested against queries built entirely from stopwords.
- **Query cache invalidation is coarse**: the whole cache is cleared on any
  reindex, not just entries touching the changed document. Correct and
  simple; not optimal if only one document out of many changes often.
- **Single-package, single-process.** No multi-instance/scaling story, no
  concurrency load-testing beyond the synchronized rebuild path; fine for a
  single reviewer's tool, not evaluated for concurrent load.
- **No PDF rendering** — the page viewer shows OCR text only, matching the
  explicit "real OCR, PDF generation" out-of-scope note; the actual sample
  PDF is only used as the source the seed parser was built from.

## What I'd do next with more time

- Real per-form footer/boundary detection instead of one regex, to recover
  the ~360 physical pages currently folded into oversized logical pages.
- Word-level highlighting using token coordinates (listed as an optional
  extension; not attempted here).
- A small cache/index admin view (current size, hit rate, last rebuild time).
- Concurrency testing of the reindex-during-search path under load.

## Tests

`mvn test` runs:
- `SearchServiceTest` — phrase, AND/OR/NOT boolean semantics, wildcard,
  pagination, missing-text graceful degradation, cache-hit behavior, and the
  live `package.json`-edit-is-reflected-on-next-search path.
- `PackageRepositoryTest` — mtime-based staleness detection.
- `PageContentServiceTest` — page viewer graceful degradation for missing
  text files, and 404s for unknown documents/out-of-range pages.
