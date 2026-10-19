package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.model.DocumentEntry;
import com.infrrd.loanpackagesearch.model.DocumentTypeGroup;
import com.infrrd.loanpackagesearch.model.PackageManifest;
import com.infrrd.loanpackagesearch.model.TreeResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the browse tree: loan -> document type -> versions -> page count. */
@Service
public class TreeService {

    private final PackageRepository packageRepository;

    public TreeService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    public TreeResponse tree() {
        PackageManifest manifest = packageRepository.currentManifest();

        Map<String, List<DocumentEntry>> byType = manifest.documents().stream()
                .collect(Collectors.groupingBy(DocumentEntry::documentType, LinkedHashMap::new, Collectors.toList()));

        List<DocumentTypeGroup> groups = byType.entrySet().stream()
                .map(e -> new DocumentTypeGroup(
                        e.getKey(),
                        e.getValue().stream()
                                .sorted(Comparator.comparingInt(DocumentEntry::version))
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());

        return new TreeResponse(manifest.loanId(), manifest.loanName(), groups);
    }
}
