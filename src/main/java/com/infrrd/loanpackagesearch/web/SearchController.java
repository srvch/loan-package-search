package com.infrrd.loanpackagesearch.web;

import com.infrrd.loanpackagesearch.model.PageContentResponse;
import com.infrrd.loanpackagesearch.model.SearchResponse;
import com.infrrd.loanpackagesearch.model.TreeResponse;
import com.infrrd.loanpackagesearch.service.PageContentService;
import com.infrrd.loanpackagesearch.service.SearchService;
import com.infrrd.loanpackagesearch.service.TreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService searchService;
    private final TreeService treeService;
    private final PageContentService pageContentService;

    public SearchController(SearchService searchService, TreeService treeService, PageContentService pageContentService) {
        this.searchService = searchService;
        this.treeService = treeService;
        this.pageContentService = pageContentService;
    }

    @GetMapping("/api/tree")
    public TreeResponse tree() {
        return treeService.tree();
    }

    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam("q") String query,
                                  @RequestParam(value = "page", defaultValue = "0") int page,
                                  @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return searchService.search(query, Math.max(page, 0), Math.min(Math.max(pageSize, 1), 100));
    }

    @GetMapping("/api/documents/{docId}/pages/{pageNumber}")
    public PageContentResponse page(@PathVariable String docId, @PathVariable int pageNumber) {
        return pageContentService.getPage(docId, pageNumber);
    }
}
