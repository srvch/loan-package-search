package com.infrrd.loanpackagesearch.model;

/** Per-stage timings (ms) for one search request, for observability. */
public record SearchTimings(
        long staleCheckMs,
        long reindexMs,
        long queryParseMs,
        long luceneSearchMs,
        long snippetGenMs,
        long totalMs,
        boolean servedFromCache
) {
}
