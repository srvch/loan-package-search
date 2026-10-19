package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.TestFixtures;
import com.infrrd.loanpackagesearch.model.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static com.infrrd.loanpackagesearch.TestFixtures.DocSpec;
import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    @TempDir
    Path dataDir;
    @TempDir
    Path indexDir;

    private PackageRepository packageRepository;
    private LuceneIndexService indexService;
    private SearchService searchService;

    @BeforeEach
    void setUp() throws IOException {
        TestFixtures.writePackage(dataDir,
                new DocSpec("doc-a", "Closing Disclosure", "Closing Disclosure", 1, null,
                        "This is the cash to close disclosure. Total cash to close is required at signing.",
                        "The borrower must sign all closing documents before funding. Signature required."),
                new DocSpec("doc-b", "Escrow Disclosure", "Escrow Disclosure", 1, null,
                        "Escrow disclosure account details and annual analysis for property taxes and insurance.",
                        null /* missing OCR text for page 2 */));

        packageRepository = new PackageRepository(dataDir.toString());
        indexService = new LuceneIndexService(indexDir.toString());
        searchService = new SearchService(packageRepository, indexService, 200);
        searchService.initialIndex();
    }

    @Test
    void phraseQueryMatchesOnlyExactPhrase() {
        SearchResponse res = searchService.search("\"cash to close\"", 0, 10);
        assertThat(res.total()).isEqualTo(1);
        assertThat(res.hits().get(0).docId()).isEqualTo("doc-a");
    }

    @Test
    void andRequiresBothTerms() {
        SearchResponse both = searchService.search("sign AND closing", 0, 10);
        assertThat(both.total()).isEqualTo(1);

        SearchResponse onlyOneTermPresent = searchService.search("sign AND escrow", 0, 10);
        assertThat(onlyOneTermPresent.total()).isZero();
    }

    @Test
    void orMatchesEitherTerm() {
        SearchResponse res = searchService.search("escrow OR signature", 0, 10);
        assertThat(res.total()).isEqualTo(2);
    }

    @Test
    void notExcludesTerm() {
        SearchResponse res = searchService.search("disclosure NOT escrow", 0, 10);
        assertThat(res.hits()).extracting(h -> h.docId()).containsOnly("doc-a");
    }

    @Test
    void wildcardMatchesPrefix() {
        SearchResponse res = searchService.search("sign*", 0, 10);
        List<String> docIds = res.hits().stream().map(h -> h.docId()).toList();
        assertThat(docIds).contains("doc-a");
    }

    @Test
    void paginationSlicesResultsConsistently() {
        SearchResponse page0 = searchService.search("disclosure", 0, 1);
        SearchResponse page1 = searchService.search("disclosure", 1, 1);
        assertThat(page0.hits()).hasSize(1);
        assertThat(page1.hits()).hasSize(1);
        assertThat(page0.hits().get(0).docId()).isNotEqualTo(page1.hits().get(0).docId());
        assertThat(page0.total()).isEqualTo(2);
    }

    @Test
    void missingPageTextDoesNotBreakSearchOfOtherPages() {
        // doc-b page 2 has no text file at all; searching should still work, just
        // without that page contributing any hits.
        SearchResponse res = searchService.search("escrow", 0, 10);
        assertThat(res.total()).isEqualTo(1);
        assertThat(res.hits().get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    void repeatSearchIsServedFromCache() {
        SearchResponse first = searchService.search("closing", 0, 10);
        SearchResponse second = searchService.search("closing", 0, 10);
        assertThat(first.timings().servedFromCache()).isFalse();
        assertThat(second.timings().servedFromCache()).isTrue();
        assertThat(second.total()).isEqualTo(first.total());
    }

    @Test
    void packageJsonEditIsReflectedOnNextSearch() throws IOException, InterruptedException {
        SearchResponse before = searchService.search("brand new clause about refinancing", 0, 10);
        assertThat(before.total()).isZero();

        Path newDocDir = dataDir.resolve("docs").resolve("doc-c");
        Files.createDirectories(newDocDir);
        Files.writeString(newDocDir.resolve("page-1.txt"), "Brand new clause about refinancing terms.");

        String updated = """
                {"loanId":"loan-test","loanName":"Test Loan","documents":[
                  {"id":"doc-a","title":"Closing Disclosure","documentType":"Closing Disclosure","version":1,"label":null,"pageCount":2,"textDir":"docs/doc-a"},
                  {"id":"doc-b","title":"Escrow Disclosure","documentType":"Escrow Disclosure","version":1,"label":null,"pageCount":2,"textDir":"docs/doc-b"},
                  {"id":"doc-c","title":"Refinance Addendum","documentType":"Refinance Addendum","version":1,"label":null,"pageCount":1,"textDir":"docs/doc-c"}
                ]}
                """;
        Path manifest = dataDir.resolve("package.json");
        // ensure the filesystem mtime actually advances past the original write
        Thread.sleep(10);
        Files.writeString(manifest, updated, StandardOpenOption.TRUNCATE_EXISTING);

        SearchResponse after = searchService.search("brand new clause about refinancing", 0, 10);
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.hits().get(0).docId()).isEqualTo("doc-c");
    }
}
