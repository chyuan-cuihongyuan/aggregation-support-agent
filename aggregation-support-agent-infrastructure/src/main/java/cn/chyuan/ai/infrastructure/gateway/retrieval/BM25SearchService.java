package cn.chyuan.ai.infrastructure.gateway.retrieval;

import cn.chyuan.ai.domain.rag.model.valobj.VectorSearchResultVO;
import cn.chyuan.ai.domain.rag.service.retrieval.IBM25SearchService;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25检索服务实现 — 使用Lucene实现基于词频的关键词检索
 */
@Slf4j
@ConditionalOnMissingBean(ElasticsearchBM25SearchService.class)
@Service
public class BM25SearchService implements IBM25SearchService {

    private Directory memoryIndex;
    private Analyzer analyzer;
    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static final String FIELD_ID = "docId";
    private static final String FIELD_CONTENT = "content";

    @PostConstruct
    public void init() {
        try {
            memoryIndex = new ByteBuffersDirectory();
            analyzer = new StandardAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            indexWriter = new IndexWriter(memoryIndex, config);
            searcherManager = new SearcherManager(indexWriter, false, false, null);
            log.info("BM25内存索引初始化完成");
        } catch (IOException e) {
            log.error("BM25索引初始化失败", e);
            throw new RuntimeException("BM25索引初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
            if (indexWriter != null) {
                indexWriter.close();
            }
            if (memoryIndex != null) {
                memoryIndex.close();
            }
            log.info("BM25索引已关闭");
        } catch (IOException e) {
            log.warn("关闭BM25索引异常", e);
        }
    }

    @Override
    public List<VectorSearchResultVO> search(String query, int topK) {
        log.info("BM25检索: query={}, topK={}", query, topK);

        lock.readLock().lock();
        try {
            // 检查索引是否有文档
            if (indexWriter.getDocStats().numDocs == 0) {
                log.warn("BM25索引为空，跳过检索");
                return Collections.emptyList();
            }

            // 从SearcherManager获取IndexSearcher（复用Reader）
            IndexSearcher searcher = searcherManager.acquire();
            try {
                // 使用BM25Similarity（Lucene默认）
                searcher.setSimilarity(new BM25Similarity());

                // 构建查询
                QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
                Query parsedQuery = parser.parse(QueryParser.escape(query));

                // 执行检索
                TopDocs topDocs = searcher.search(parsedQuery, topK);

                // 构建结果
                List<VectorSearchResultVO> results = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document doc = searcher.doc(scoreDoc.doc);
                    String docId = doc.get(FIELD_ID);
                    String content = doc.get(FIELD_CONTENT);

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("docId", docId);
                    metadata.put("retrievalType", "bm25");

                    results.add(VectorSearchResultVO.builder()
                            .content(content)
                            .score(scoreDoc.score)
                            .metadata(metadata)
                            .build());
                }

                log.info("BM25检索完成: resultCount={}", results.size());
                return results;
            } finally {
                searcherManager.release(searcher);
            }

        } catch (Exception e) {
            log.error("BM25检索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void addDocument(String docId, String content) {
        lock.writeLock().lock();
        try {
            Document doc = new Document();
            doc.add(new TextField(FIELD_ID, docId, Field.Store.YES));
            doc.add(new TextField(FIELD_CONTENT, content, Field.Store.YES));
            indexWriter.addDocument(doc);
            indexWriter.commit();
            searcherManager.maybeRefresh();
            log.debug("BM25添加文档: docId={}", docId);
        } catch (IOException e) {
            log.error("BM25添加文档失败: docId={}", docId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void addDocuments(Map<String, String> documents) {
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, String> entry : documents.entrySet()) {
                Document doc = new Document();
                doc.add(new TextField(FIELD_ID, entry.getKey(), Field.Store.YES));
                doc.add(new TextField(FIELD_CONTENT, entry.getValue(), Field.Store.YES));
                indexWriter.addDocument(doc);
            }
            indexWriter.commit();
            searcherManager.maybeRefresh();
            log.info("BM25批量添加文档: count={}", documents.size());
        } catch (IOException e) {
            log.error("BM25批量添加文档失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeDocument(String docId) {
        lock.writeLock().lock();
        try {
            indexWriter.deleteDocuments(new Term(FIELD_ID, docId));
            indexWriter.commit();
            searcherManager.maybeRefresh();
            log.debug("BM25删除文档: docId={}", docId);
        } catch (IOException e) {
            log.error("BM25删除文档失败: docId={}", docId, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearIndex() {
        lock.writeLock().lock();
        try {
            indexWriter.deleteAll();
            indexWriter.commit();
            log.info("BM25索引已清空");
        } catch (IOException e) {
            log.error("清空BM25索引失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int getDocumentCount() {
        lock.readLock().lock();
        try {
            return (int) indexWriter.getDocStats().numDocs;
        } finally {
            lock.readLock().unlock();
        }
    }

}
