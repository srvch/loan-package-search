package com.infrrd.loanpackagesearch.model;

public record SearchHit(
        String docId,
        String docTitle,
        String documentType,
        int version,
        String label,
        int pageNumber,
        int pageCount,
        float score,
        String snippet
) {
}
