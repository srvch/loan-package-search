package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static com.infrrd.loanpackagesearch.TestFixtures.DocSpec;
import static org.assertj.core.api.Assertions.assertThat;

class PackageRepositoryTest {

    @TempDir
    Path dataDir;

    @Test
    void detectsEditsToPackageJsonByModificationTime() throws IOException, InterruptedException {
        TestFixtures.writePackage(dataDir, new DocSpec("doc-a", "Doc A", "Doc A", 1, null, "hello"));
        PackageRepository repo = new PackageRepository(dataDir.toString());

        assertThat(repo.isStale()).isFalse();
        assertThat(repo.currentManifest().documents()).hasSize(1);

        Thread.sleep(10);
        String updated = """
                {"loanId":"loan-test","loanName":"Test Loan","documents":[
                  {"id":"doc-a","title":"Doc A","documentType":"Doc A","version":1,"label":null,"pageCount":1,"textDir":"docs/doc-a"},
                  {"id":"doc-b","title":"Doc B","documentType":"Doc B","version":1,"label":null,"pageCount":1,"textDir":"docs/doc-b"}
                ]}
                """;
        Files.writeString(dataDir.resolve("package.json"), updated, StandardOpenOption.TRUNCATE_EXISTING);

        assertThat(repo.isStale()).isTrue();
        assertThat(repo.currentManifest().documents()).hasSize(2);
        assertThat(repo.isStale()).isFalse();
    }
}
