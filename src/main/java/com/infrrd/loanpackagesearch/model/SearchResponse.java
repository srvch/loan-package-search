package com.infrrd.loanpackagesearch.model;

import java.util.List;

public record SearchResponse(
        String query,
        int total,
        int page,
        int pageSize,
        List<SearchHit> hits,
        SearchTimings timings
) {
}
