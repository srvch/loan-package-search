package com.infrrd.loanpackagesearch.model;

import java.util.List;

/** Tree view grouping: all versions of one document type within a loan. */
public record DocumentTypeGroup(
        String documentType,
        List<DocumentEntry> versions
) {
}
