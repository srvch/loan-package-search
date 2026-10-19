package com.infrrd.loanpackagesearch.model;

public record PageContentResponse(
        String docId,
        String docTitle,
        int pageNumber,
        int pageCount,
        String text,
        boolean textMissing
) {
}
