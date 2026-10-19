package com.infrrd.loanpackagesearch.model;

/** One physical document inside a loan package, as declared in package.json. */
public record DocumentEntry(
        String id,
        String title,
        String documentType,
        int version,
        String label,
        int pageCount,
        String textDir
) {
}
