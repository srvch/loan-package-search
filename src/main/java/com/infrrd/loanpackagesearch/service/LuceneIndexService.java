package com.infrrd.loanpackagesearch.service;

import com.infrrd.loanpackagesearch.model.DocumentEntry;
import com.infrrd.loanpackagesearch.model.PackageManifest;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the on-disk Lucene index: one Lucene Document per page, rebuilt
 * wholesale whenever the loan package manifest changes. The index directory
 * is wiped and recreated on each rebuild (persistence across process
 * restarts is explicitly out of scope), which keeps the memory/disk
 * footprint bounded to "one package's worth of pages" rather than
 * accumulating across rebuilds.
 */
@Component
public class LuceneIndexService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexService.class);

    public static final String FIELD_DOC_ID = "docId";
    public static final String FIELD_DOCUMENT_TYPE = "documentType";
    public static final String FIELD_DOC_TITLE = "docTitle";
    public static final String FIELD_VERSION = "version";
    public static final String FIELD_LABEL = "label";
    public static final String FIELD_PAGE_NUMBER = "pageNumber";
    public static final String FIELD_PAGE_COUNT = "pageCount";
    public static final String FIELD_CONTENT = "content";

    private final Path indexDir;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    private FSDirectory directory;
    private volatile IndexSearcher searcher;
    private volatile DirectoryReader reader;
    private volatile int indexVersion = 0;

    public LuceneIndexService(@Value("${app.index-dir}") String indexDir) {
        this.indexDir = Path.of(indexDir);
    }

    public StandardAnalyzer analyzer() {
        return analyzer;
    }

    public int indexVersion() {
        return indexVersion;
    }

    public IndexSearcher searcher() {
        return searcher;
    }

    /**
     * Wipes and rebuilds the index from the given manifest, reading page text
     * relative to {@code dataDir}. Returns the number of pages indexed.
     */
    public synchronized int rebuild(PackageManifest manifest, Path dataDir) {
        try {
            if (directory == null) {
                Files.createDirectories(indexDir);
                directory = FSDirectory.open(indexDir);
            }
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            int pagesIndexed = 0;
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (DocumentEntry doc : manifest.documents()) {
                    pagesIndexed += indexDocument(writer, dataDir, doc);
                }
                writer.commit();
            }
            swapReader();
            indexVersion++;
            log.info("Lucene index rebuilt: {} documents, {} pages, version {}",
                    manifest.documents().size(), pagesIndexed, indexVersion);
            return pagesIndexed;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebuild Lucene index", e);
        }
    }

    private int indexDocument(IndexWriter writer, Path dataDir, DocumentEntry doc) throws IOException {
        Path textDir = dataDir.resolve(doc.textDir());
        int indexed = 0;
        for (int page = 1; page <= doc.pageCount(); page++) {
            Path pageFile = textDir.resolve("page-" + page + ".txt");
            String text;
            if (Files.exists(pageFile)) {
                text = Files.readString(pageFile);
            } else {
                // Graceful degradation: missing OCR text for this page. We still
                // index a stub record so the page stays browsable/navigable, it
                // just won't surface in text search results.
                log.warn("Missing text file for {} page {}: {}", doc.id(), page, pageFile);
                text = "";
            }
            writer.addDocument(toLuceneDocument(doc, page, text));
            indexed++;
        }
        return indexed;
    }

    private Document toLuceneDocument(DocumentEntry doc, int page, String text) {
        Document d = new Document();
        d.add(new StringField(FIELD_DOC_ID, doc.id(), Field.Store.YES));
        d.add(new StringField(FIELD_DOCUMENT_TYPE, doc.documentType(), Field.Store.YES));
        d.add(new StringField(FIELD_DOC_TITLE, doc.title(), Field.Store.YES));
        d.add(new StoredField(FIELD_VERSION, doc.version()));
        d.add(new IntPoint(FIELD_VERSION, doc.version()));
        d.add(new StringField(FIELD_LABEL, doc.label() == null ? "" : doc.label(), Field.Store.YES));
        d.add(new StoredField(FIELD_PAGE_NUMBER, page));
        d.add(new IntPoint(FIELD_PAGE_NUMBER, page));
        d.add(new StoredField(FIELD_PAGE_COUNT, doc.pageCount()));
        d.add(new TextField(FIELD_CONTENT, text, Field.Store.YES));
        return d;
    }

    private void swapReader() throws IOException {
        DirectoryReader newReader = DirectoryReader.open(directory);
        DirectoryReader old = this.reader;
        this.reader = newReader;
        this.searcher = new IndexSearcher(newReader);
        if (old != null) {
            old.close();
        }
    }

    @Override
    public void destroy() throws Exception {
        if (reader != null) {
            reader.close();
        }
        if (directory != null) {
            directory.close();
        }
    }
}
