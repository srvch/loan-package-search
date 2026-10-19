package com.infrrd.loanpackagesearch.model;

import java.util.List;

/** Deserialized contents of package.json: a single loan package. */
public record PackageManifest(
        String loanId,
        String loanName,
        List<DocumentEntry> documents
) {
}
