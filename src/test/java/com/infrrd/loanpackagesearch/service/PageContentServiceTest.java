package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.TestFixtures;
import com.infrrd.loanpackagesearch.model.PageContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;

import static com.infrrd.loanpackagesearch.TestFixtures.DocSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageContentServiceTest {

    @TempDir
    Path dataDir;

    private PageContentService service;

    @BeforeEach
    void setUp() throws IOException {
        TestFixtures.writePackage(dataDir,
                new DocSpec("doc-a", "Doc A", "Doc A", 1, null, "page one text", null));
        service = new PageContentService(new PackageRepository(dataDir.toString()));
    }

    @Test
    void returnsPageTextWhenPresent() {
        PageContentResponse res = service.getPage("doc-a", 1);
        assertThat(res.text()).isEqualTo("page one text");
        assertThat(res.textMissing()).isFalse();
    }

    @Test
    void degradesGracefullyWhenTextFileIsMissing() {
        PageContentResponse res = service.getPage("doc-a", 2);
        assertThat(res.textMissing()).isTrue();
        assertThat(res.text()).isEmpty();
    }

    @Test
    void outOfRangePageIsRejected() {
        assertThatThrownBy(() -> service.getPage("doc-a", 99))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unknownDocumentIsRejected() {
        assertThatThrownBy(() -> service.getPage("nope", 1))
                .isInstanceOf(ResponseStatusException.class);
    }
}
