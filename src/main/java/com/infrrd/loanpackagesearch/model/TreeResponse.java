package com.infrrd.loanpackagesearch.model;

import java.util.List;

public record TreeResponse(
        String loanId,
        String loanName,
        List<DocumentTypeGroup> documentTypes
) {
}
