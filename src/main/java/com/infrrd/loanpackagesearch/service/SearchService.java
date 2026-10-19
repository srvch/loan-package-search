package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.model.PackageManifest;
import com.infrrd.loanpackagesearch.model.SearchHit;
import com.infrrd.loanpackagesearch.model.SearchResponse;
import com.infrrd.loanpackagesearch.model.SearchTimings;
import jakarta.annotation.PostConstruct;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates "is the index fresh" -> "parse query" -> "search" ->
 * "build snippets", with per-stage timings and an LRU cache of recent
 * (query, page, pageSize, indexVersion) results so repeat searches skip
 * straight to a cache hit.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final PackageRepository packageRepository;
    private final LuceneIndexService indexService;
    private final int cacheCapacity;

    private final Map<String, SearchResponse> cache;

    public SearchService(PackageRepository packageRepository,
                          LuceneIndexService indexService,
                          @Value("${app.query-cache-size}") int cacheCapacity) {
        this.packageRepository = packageRepository;
        this.indexService = indexService;
        this.cacheCapacity = cacheCapacity;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SearchResponse> eldest) {
                return size() > SearchService.this.cacheCapacity;
            }
        };
    }

    @PostConstruct
    void initialIndex() {
        ensureFresh();
    }

    /** Rebuilds the Lucene index if package.json changed since the last load. Returns ms spent reindexing (0 if not needed). */
    private long ensureFresh() {
        if (indexService.indexVersion() > 0 && !packageRepository.isStale()) {
            return 0;
        }
        long start = System.nanoTime();
        PackageManifest manifest = packageRepository.currentManifest();
        indexService.rebuild(manifest, packageRepository.dataDir());
        synchronized (cache) {
            cache.clear(); // stale results reference the old index version anyway, but be explicit
        }
        return msSince(start);
    }

    public SearchResponse search(String queryText, int page, int pageSize) {
        long totalStart = System.nanoTime();

        long staleCheckStart = System.nanoTime();
        long reindexMs = ensureFresh();
        long staleCheckMs = msSince(staleCheckStart) - reindexMs;

        String cacheKey = indexService.indexVersion() + "|" + page + "|" + pageSize + "|" + queryText;
        synchronized (cache) {
            SearchResponse cached = cache.get(cacheKey);
            if (cached != null) {
                SearchTimings t = new SearchTimings(staleCheckMs, reindexMs, 0, 0, 0, msSince(totalStart), true);
                return new SearchResponse(cached.query(), cached.total(), cached.page(), cached.pageSize(), cached.hits(), t);
            }
        }

        long parseStart = System.nanoTime();
        Query query = parseQuery(queryText);
        long queryParseMs = msSince(parseStart);

        IndexSearcher searcher = indexService.searcher();
        int fetchLimit = Math.max(1, (page + 1) * pageSize);
        long searchStart = System.nanoTime();
        TopDocs topDocs;
        try {
            topDocs = searcher.search(query, fetchLimit);
        } catch (IOException e) {
            throw new RuntimeException("Search execution failed", e);
        }
        long luceneSearchMs = msSince(searchStart);

        long snippetStart = System.nanoTime();
        List<SearchHit> hits = buildHits(searcher, topDocs, query, page, pageSize);
        long snippetGenMs = msSince(snippetStart);

        int total = (int) topDocs.totalHits.value;
        SearchTimings timings = new SearchTimings(staleCheckMs, reindexMs, queryParseMs, luceneSearchMs, snippetGenMs,
                msSince(totalStart), false);
        SearchResponse response = new SearchResponse(queryText, total, page, pageSize, hits, timings);

        synchronized (cache) {
            cache.put(cacheKey, response);
        }
        return response;
    }

    private Query parseQuery(String queryText) {
        QueryParser parser = new QueryParser(LuceneIndexService.FIELD_CONTENT, indexService.analyzer());
        parser.setDefaultOperator(QueryParser.Operator.AND);
        parser.setAllowLeadingWildcard(false);
        try {
            return parser.parse(queryText);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid search query: " + e.getMessage(), e);
        }
    }

    private List<SearchHit> buildHits(IndexSearcher searcher, TopDocs topDocs, Query query, int page, int pageSize) {
        List<SearchHit> hits = new ArrayList<>();
        // Escape-then-wrap formatter: the highlighter hands us raw page text, which may
        // contain characters from the OCR dump; escape it before it reaches the browser's
        // innerHTML instead of trusting SimpleHTMLFormatter's unescaped passthrough.
        org.apache.lucene.search.highlight.Formatter formatter = (originalText, tokenGroup) -> {
            String escaped = escapeHtml(originalText);
            return (tokenGroup != null && tokenGroup.getTotalScore() > 0) ? "<mark>" + escaped + "</mark>" : escaped;
        };
        Highlighter highlighter = new Highlighter(formatter, new QueryScorer(query));
        highlighter.setTextFragmenter(new SimpleFragmenter(160));

        int from = page * pageSize;
        int to = Math.min(topDocs.scoreDocs.length, from + pageSize);
        for (int i = from; i < to; i++) {
            ScoreDoc sd = topDocs.scoreDocs[i];
            try {
                Document doc = searcher.storedFields().document(sd.doc);
                String content = doc.get(LuceneIndexService.FIELD_CONTENT);
                String snippet = buildSnippet(highlighter, content);
                hits.add(new SearchHit(
                        doc.get(LuceneIndexService.FIELD_DOC_ID),
                        doc.get(LuceneIndexService.FIELD_DOC_TITLE),
                        doc.get(LuceneIndexService.FIELD_DOCUMENT_TYPE),
                        Integer.parseInt(doc.get(LuceneIndexService.FIELD_VERSION)),
                        doc.get(LuceneIndexService.FIELD_LABEL),
                        Integer.parseInt(doc.get(LuceneIndexService.FIELD_PAGE_NUMBER)),
                        Integer.parseInt(doc.get(LuceneIndexService.FIELD_PAGE_COUNT)),
                        sd.score,
                        snippet
                ));
            } catch (IOException e) {
                log.warn("Failed to load stored fields for doc {}", sd.doc, e);
            }
        }
        return hits;
    }

    private String buildSnippet(Highlighter highlighter, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            String best = highlighter.getBestFragment(indexService.analyzer(), LuceneIndexService.FIELD_CONTENT, content);
            if (best != null) {
                return best;
            }
        } catch (IOException | InvalidTokenOffsetsException e) {
            log.warn("Highlighting failed, falling back to truncated snippet", e);
        }
        String truncated = content.length() > 160 ? content.substring(0, 160) + "..." : content;
        return escapeHtml(truncated);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
