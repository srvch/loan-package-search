package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.model.DocumentEntry;
import com.infrrd.loanpackagesearch.model.PackageManifest;
import com.infrrd.loanpackagesearch.model.PageContentResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Serves raw page text for the page viewer, degrading gracefully when a file is missing. */
@Service
public class PageContentService {

    private final PackageRepository packageRepository;

    public PageContentService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    public PageContentResponse getPage(String docId, int pageNumber) {
        PackageManifest manifest = packageRepository.currentManifest();
        DocumentEntry doc = manifest.documents().stream()
                .filter(d -> d.id().equals(docId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown document: " + docId));

        if (pageNumber < 1 || pageNumber > doc.pageCount()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Page " + pageNumber + " out of range for " + docId + " (has " + doc.pageCount() + ")");
        }

        Path pageFile = packageRepository.dataDir().resolve(doc.textDir()).resolve("page-" + pageNumber + ".txt");
        Optional<String> text = readIfPresent(pageFile);

        return new PageContentResponse(doc.id(), doc.title(), pageNumber, doc.pageCount(),
                text.orElse(""), text.isEmpty());
    }

    private Optional<String> readIfPresent(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
