package com.infrrd.loanpackagesearch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds a small, throwaway loan-package directory for tests: package.json + per-page text files. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public record DocSpec(String id, String title, String documentType, int version, String label, String... pages) {
    }

    /** Writes a package.json plus per-page .txt files under {@code root}. Returns the package.json path. */
    public static Path writePackage(Path root, DocSpec... docs) throws IOException {
        StringBuilder docsJson = new StringBuilder();
        for (int i = 0; i < docs.length; i++) {
            DocSpec d = docs[i];
            Path docDir = root.resolve("docs").resolve(d.id());
            Files.createDirectories(docDir);
            for (int p = 0; p < d.pages().length; p++) {
                String text = d.pages()[p];
                if (text != null) {
                    Files.writeString(docDir.resolve("page-" + (p + 1) + ".txt"), text);
                }
                // null page text => file intentionally omitted (missing-OCR scenario)
            }
            if (i > 0) {
                docsJson.append(",");
            }
            docsJson.append(String.format("""
                    {"id":"%s","title":"%s","documentType":"%s","version":%d,"label":%s,"pageCount":%d,"textDir":"docs/%s"}
                    """,
                    d.id(), d.title(), d.documentType(), d.version(),
                    d.label() == null ? "null" : "\"" + d.label() + "\"",
                    d.pages().length, d.id()));
        }
        String json = """
                {"loanId":"loan-test","loanName":"Test Loan","documents":[%s]}
                """.formatted(docsJson);
        Path manifest = root.resolve("package.json");
        Files.writeString(manifest, json);
        return manifest;
    }
}
