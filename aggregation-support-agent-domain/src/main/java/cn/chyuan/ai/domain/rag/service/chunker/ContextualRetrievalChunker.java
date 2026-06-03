package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Contextual Retrieval 分块器 — Anthropic提出的方案
 * <p>
 * 核心思想：
 * <ul>
 *   <li>不改变chunk本身，而是在向量化之前把缺失的上下文补进去</li>
 *   <li>让LLM看着整篇原始文档，为每一个chunk生成一段背景说明</li>
 *   <li>把生成的Context前置拼到chunk前面</li>
 *   <li>把这个「Context + chunk」整体去做Embedding和BM25索引</li>
 * </ul>
 * <p>
 * 解决的问题：
 * chunk被单独拿出来后就失去了语境，向量只能捕捉它「看到」的文字里的语义，
 * 看不到的那部分背景信息自然没法编码进向量里。
 * <p>
 * 示例：
 * <pre>
 * 原始chunk：此条款自 2024 年 1 月 1 日起生效，适用于所有企业版订阅用户。
 *
 * 生成的Context：这段内容说明了企业用户专属客服和技术顾问服务条款的生效日期和适用范围。
 *
 * 拼接后向量化：这段内容说明了企业用户专属客服和技术顾问服务条款的生效日期和适用范围。
 *              此条款自 2024 年 1 月 1 日起生效，适用于所有企业版订阅用户。
 * </pre>
 * <p>
 * 效果：结合BM25混合检索，检索失败率降低约49%
 */
@Slf4j
@Component
public class ContextualRetrievalChunker {

    @Autowired(required = false)
    private ChatModel chatModel;

    /** 发送给LLM的最大文档长度 */
    @Value("${document.chunk.contextual.max-doc-length}")
    private int maxDocLength;

    /** Context最大长度 */
    @Value("${document.chunk.contextual.max-context-length}")
    private int maxContextLength;

    /** 是否使用批量处理（降低成本） */
    @Value("${document.chunk.contextual.batch-enabled}")
    private boolean batchEnabled;

    /**
     * 使用Contextual Retrieval方式切割文档
     *
     * @param parsedDocument 解析后的文档
     * @param fileName       文件名
     * @return 包含Context的chunk列表
     */
    public List<DocumentChunkEntity> chunk(ParsedDocumentVO parsedDocument, String fileName) {
        log.info("开始Contextual Retrieval切割: fileName={}", fileName);

        // 1. 先按常规方式分块
        List<DocumentChunkEntity> originalChunks = createOriginalChunks(parsedDocument, fileName);

        if (originalChunks.isEmpty()) {
            return originalChunks;
        }

        // 2. 获取完整文档内容（用于生成Context）
        String fullDocument = parsedDocument.getTextContent();

        // 3. 为每个chunk生成Context并拼接
        List<DocumentChunkEntity> contextualChunks = new ArrayList<>();

        if (batchEnabled) {
            // 批量处理：一次处理多个chunk
            contextualChunks = batchGenerateContext(fullDocument, originalChunks);
        } else {
            // 逐个处理
            for (DocumentChunkEntity chunk : originalChunks) {
                String context = generateContext(fullDocument, chunk.getContent());
                String contextualContent = context + "\n" + chunk.getContent();
                contextualChunks.add(buildContextualChunk(chunk, contextualContent, context));
            }
        }

        log.info("Contextual Retrieval切割完成: fileName={}, chunkCount={}",
                fileName, contextualChunks.size());
        return contextualChunks;
    }

    /**
     * 创建原始chunks
     */
    private List<DocumentChunkEntity> createOriginalChunks(ParsedDocumentVO parsedDocument, String fileName) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        if (parsedDocument.getSections() != null && !parsedDocument.getSections().isEmpty()) {
            int chunkIndex = 0;
            for (ParsedDocumentVO.DocumentSection section : parsedDocument.getSections()) {
                String content = section.getContent();
                if (content == null || content.trim().isEmpty()) continue;

                chunks.add(buildOriginalChunk(content, chunkIndex++, fileName,
                        parsedDocument.getExtension(), section.getTitle()));
            }
        } else {
            String content = parsedDocument.getTextContent();
            if (content != null && !content.trim().isEmpty()) {
                chunks.add(buildOriginalChunk(content, 0, fileName,
                        parsedDocument.getExtension(), null));
            }
        }

        return chunks;
    }

    /**
     * 批量生成Context（降低成本）
     */
    private List<DocumentChunkEntity> batchGenerateContext(String fullDocument, List<DocumentChunkEntity> chunks) {
        List<DocumentChunkEntity> result = new ArrayList<>();

        // 将chunks分批处理
        int batchSize = 5;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<DocumentChunkEntity> batch = chunks.subList(i, end);

            // 批量生成Context
            List<String> contexts = batchGenerateContexts(fullDocument, batch);

            // 拼接Context和原始chunk
            for (int j = 0; j < batch.size(); j++) {
                DocumentChunkEntity chunk = batch.get(j);
                String context = contexts.get(j);
                String contextualContent = context + "\n" + chunk.getContent();
                result.add(buildContextualChunk(chunk, contextualContent, context));
            }
        }

        return result;
    }

    /**
     * 批量生成Contexts
     */
    private List<String> batchGenerateContexts(String fullDocument, List<DocumentChunkEntity> chunks) {
        if (chatModel == null) {
            log.warn("ChatModel未配置，返回空Context");
            return chunks.stream().map(c -> "").collect(Collectors.toList());
        }

        StringBuilder chunksDescription = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            chunksDescription.append(String.format("Chunk %d:\n%s\n\n", i + 1, chunks.get(i).getContent()));
        }

        String prompt = """
                请为以下每个chunk生成一段简短的背景说明（Context）。
                
                要求：
                1. 每个Context 1-2句话，说明这个chunk在整篇文档中讲的是什么
                2. 帮助理解chunk的主题和背景
                3. 不要重复chunk中的具体内容
                4. 以JSON数组格式返回，顺序与chunk对应
                
                完整文档（摘要）：
                %s
                
                需要生成Context的chunks：
                %s
                
                请返回JSON数组格式，如：["Context 1", "Context 2", "Context 3"]
                """.formatted(truncate(fullDocument, maxDocLength), chunksDescription.toString());

        try {
            String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();

            String jsonStr = extractJsonArray(result);
            com.alibaba.fastjson.JSONArray jsonArray = com.alibaba.fastjson.JSON.parseArray(jsonStr);

            List<String> contexts = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                if (i < jsonArray.size()) {
                    contexts.add(jsonArray.getString(i));
                } else {
                    contexts.add("");
                }
            }
            return contexts;

        } catch (Exception e) {
            log.warn("批量Context生成失败: {}", e.getMessage());
            return chunks.stream().map(c -> "").collect(Collectors.toList());
        }
    }

    /**
     * 为单个chunk生成Context
     */
    private String generateContext(String fullDocument, String chunkContent) {
        if (chatModel == null) {
            log.warn("ChatModel未配置，返回空Context");
            return "";
        }

        String prompt = """
                请为以下chunk生成一段简短的背景说明（Context）。
                
                要求：
                1. 1-2句话，说明这个chunk在整篇文档中处于什么位置、讲的是什么
                2. 帮助理解chunk的主题和背景
                3. 不要重复chunk中的具体内容
                4. 只返回Context内容，不要添加前缀
                
                完整文档（摘要）：
                %s
                
                当前chunk：
                %s
                
                Context：
                """.formatted(truncate(fullDocument, maxDocLength), truncate(chunkContent, 500));

        try {
            String context = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            return truncate(context.trim(), maxContextLength);
        } catch (Exception e) {
            log.warn("Context生成失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 构建包含Context的chunk
     */
    private DocumentChunkEntity buildContextualChunk(DocumentChunkEntity original,
                                                     String contextualContent, String context) {
        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        metadata.put("chunkType", "contextual");
        metadata.put("originalContent", original.getContent());
        metadata.put("context", context);

        return DocumentChunkEntity.builder()
                .id(original.getId() + "_ctx")
                .content(contextualContent)
                .metadata(metadata)
                .build();
    }

    /**
     * 构建原始chunk
     */
    private DocumentChunkEntity buildOriginalChunk(String content, int chunkIndex,
                                                    String fileName, String extension, String title) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("_extension", extension);
        metadata.put("chunkIndex", chunkIndex);
        if (title != null) {
            metadata.put("title", title);
        }

        return DocumentChunkEntity.builder()
                .id(fileName + "_chunk_" + chunkIndex)
                .content(content)
                .metadata(metadata)
                .build();
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * 提取JSON数组
     */
    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

}
