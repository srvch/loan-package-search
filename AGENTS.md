# AGENTS.md

This file is normally meant to be read automatically by AI coding tools and
to drive a live session log. That setup wasn't available for this exercise —
see the note at the end — so this is a written-after-the-fact record instead:
what was built, the key decisions, and the reasoning behind them.

## What this project is

A Spring Boot service that indexes a loan package's documents (one Lucene
document per physical page) and exposes full-text search — boolean
AND/OR/NOT, exact phrases, trailing wildcards, BM25 ranking, highlighted
snippets — plus a document tree browser and a page viewer, with the index
kept in sync whenever `package.json` is edited while the service is running.

See `README.md` for the full design write-up, measured performance numbers,
and known limitations. This file is a shorter "how we got here" narrative.

## Design decisions, in the order they were made

1. **Scope check first.** The assignment brief references a fuller
   `ASSIGNMENT.md`/`AGENTS.md`/`package.json` schema bundled in a zip that
   wasn't actually present among the files shared for this exercise — only
   the one-page brief, the sample PDF, and an OCR layout `.txt` were
   available. This was confirmed explicitly before any design work started,
   rather than guessing silently.

2. **Custom inverted index vs. Apache Lucene.** Discussed as an explicit
   trade-off: a hand-rolled index demonstrates more of the underlying
   mechanics (tokenizer, postings, a boolean/phrase/wildcard parser, BM25),
   but costs roughly 3–4.5 extra hours against this assignment's timebox for
   a less battle-tested result. Lucene was chosen deliberately — it gives
   boolean/phrase/wildcard query syntax, BM25 ranking, and a highlighter
   largely for free, leaving more time for the parts that are harder to get
   from a library: the data model, live-reindex correctness, caching, and
   observability.

3. **Seed data had to be synthesized.** The sample OCR layout `.txt` has no
   machine-readable document/page boundaries — only human-oriented
   "Page X of Y" footer lines, and only on about a third of the PDF's ~560
   physical pages (other forms, like the Closing Disclosure or Form 1040,
   use different or no footer conventions). `tools/split_layout.py` (a
   throwaway script, not part of the shipped app) splits the layout dump
   into 56 documents / 201 logical pages using that footer heuristic, and
   emits the `package.json` + per-page `.txt` files the service actually
   reads. This is a documented, known-incomplete heuristic, not a hidden one
   — see the README's "Known limitations" section.

4. **`package.json` schema and the loan → document → version → page model**
   were designed from scratch (nothing provided specified this). Chose a
   flat list of documents, each carrying a `documentType` and `version`,
   with the browse tree computed by grouping on `documentType` at request
   time rather than stored as a separate nested structure — one source of
   truth instead of two data shapes to keep in sync.

5. **Correctness under change** (package.json edited while the service is
   running) is handled by checking the file's mtime at the top of every
   request rather than a background file watcher — simpler to reason about
   for a single-reviewer tool, and it turns the live-edit requirement into
   an observable, synchronously-timed code path (`reindexMs` in the
   response) instead of a background thread racing with searches. Verified
   both with an automated test and manually against the running service
   (edited `package.json`, confirmed the very next search reflected it).

6. **Performance work**: an on-disk `FSDirectory` index wiped and rebuilt
   wholesale on change (simple, bounded memory footprint, defensible at this
   corpus size), plus an LRU query-result cache (capped at 200 entries,
   cleared on every reindex) so repeat searches return near-instantly. Both
   measured, not assumed — see README for the actual numbers.

7. **Testing** focused on query semantics (AND/OR/NOT/phrase/wildcard),
   pagination, graceful degradation when a page's text file is missing, and
   the live `package.json`-edit-is-reflected-on-next-search path, since that
   last one is easy to get subtly wrong (e.g. caching across a reindex).

## About the missing AI-usage log

The assignment instructions describe an `AGENTS.md` that automatically
writes a session log to `~/infrrd_loan_package_search/log.txt`. That
mechanism depends on this file being present *before* the session starts, so
a coding tool can read and act on it from the first prompt. Since it wasn't
available here, no such log exists; this file was written after the fact,
by request, as the closest available substitute — a human-readable account
of the approach rather than an automated prompt-by-prompt log.
