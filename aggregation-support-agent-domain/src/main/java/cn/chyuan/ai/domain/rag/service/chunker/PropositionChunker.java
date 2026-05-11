package cn.chyuan.ai.domain.rag.service.chunker;

import cn.chyuan.ai.domain.rag.model.entity.DocumentChunkEntity;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 命题化切割器 — 用LLM将文档分解为独立的「命题」
 * <p>
 * 核心思想：
 * <ul>
 *   <li>不按文本位置切割，而是用LLM把文档分解成一条条独立的「命题」</li>
 *   <li>每个命题是一个完整、自包含的陈述句</li>
 *   <li>包含了表达这个事实所需的全部上下文</li>
 *   <li>单独拿出来就能看懂，不依赖上下文</li>
 * </ul>
 * <p>
 * 示例：
 * <pre>
 * 原文：企业用户享有优先客服通道，响应时间不超过 2 小时，并可申请专属技术顾问服务。
 *
 * 分解为命题：
 * 1. 企业用户享有优先客服通道。
 * 2. 企业用户的客服响应时间不超过 2 小时。
 * 3. 企业用户可以申请专属技术顾问服务。
 * </pre>
 * <p>
 * 优势：每个chunk语义密度最高、最独立，检索精度非常好
 * 代价：需要额外LLM调用，成本较高
 */
@Slf4j
@Component
public class PropositionChunker {

    @Autowired(required = false)
    private ChatModel chatModel;

    /** 每次发送给LLM的最大文本长度 */
    @Value("${document.chunk.proposition.max-input-length:2000}")
    private int maxInputLength;

    /** 命题最大长度 */
    @Value("${document.chunk.proposition.max-proposition-length:200}")
    private int maxPropositionLength;

    /**
     * 使用命题化方式切割文档
     *
     * @param parsedDocument 解析后的文档
     * @param fileName       文件名
     * @return 命题chunk列表
     */
    public List<DocumentChunkEntity> chunk(ParsedDocumentVO parsedDocument, String fileName) {
        log.info("开始命题化切割: fileName={}", fileName);

        List<DocumentChunkEntity> allChunks = new ArrayList<>();
        int chunkIndex = 0;

        if (parsedDocument.getSections() != null && !parsedDocument.getSections().isEmpty()) {
            // 按章节处理
            for (ParsedDocumentVO.DocumentSection section : parsedDocument.getSections()) {
                String content = section.getContent();
                if (content == null || content.trim().isEmpty()) continue;

                List<String> propositions = extractPropositions(content);
                for (String proposition : propositions) {
                    DocumentChunkEntity chunk = buildChunkEntity(
                            proposition, chunkIndex++, fileName,
                            parsedDocument.getExtension(), section.getTitle());
                    allChunks.add(chunk);
                }
            }
        } else {
            // 按全文处理
            String content = parsedDocument.getTextContent();
            List<String> propositions = extractPropositions(content);
            for (String proposition : propositions) {
                DocumentChunkEntity chunk = buildChunkEntity(
                        proposition, chunkIndex++, fileName,
                        parsedDocument.getExtension(), null);
                allChunks.add(chunk);
            }
        }

        // 更新totalChunks
        int total = allChunks.size();
        for (DocumentChunkEntity chunk : allChunks) {
            chunk.getMetadata().put("totalChunks", total);
        }

        log.info("命题化切割完成: fileName={}, propositionCount={}", fileName, allChunks.size());
        return allChunks;
    }

    /**
     * 从文本中提取命题
     */
    private List<String> extractPropositions(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 如果文本较短，直接处理
        if (text.length() <= maxInputLength) {
            return callLLMForPropositions(text);
        }

        // 文本较长，分段处理
        List<String> segments = splitByLength(text, maxInputLength);
        List<String> allPropositions = new ArrayList<>();

        for (String segment : segments) {
            List<String> propositions = callLLMForPropositions(segment);
            allPropositions.addAll(propositions);
        }

        return allPropositions;
    }

    /**
     * 调用LLM提取命题
     */
    private List<String> callLLMForPropositions(String text) {
        if (chatModel == null) {
            log.warn("ChatModel未配置，返回原始文本作为命题");
            List<String> fallback = new ArrayList<>();
            fallback.add(text);
            return fallback;
        }

        String prompt = """
                请将以下文本分解为独立的命题（Proposition）。
                
                命题要求：
                1. 每个命题是一个完整、自包含的陈述句
                2. 包含表达这个事实所需的全部上下文
                3. 单独拿出来就能看懂，不依赖上下文
                4. 只包含一个核心事实
                5. 保持原文的准确信息，不要添加或修改内容
                
                示例：
                原文：企业用户享有优先客服通道，响应时间不超过 2 小时，并可申请专属技术顾问服务。
                命题：
                - 企业用户享有优先客服通道。
                - 企业用户的客服响应时间不超过 2 小时。
                - 企业用户可以申请专属技术顾问服务。
                
                请以JSON数组格式返回命题列表，如：["命题1", "命题2", "命题3"]
                
                原文：
                %s
                
                JSON数组：
                """.formatted(truncate(text, maxInputLength));

        try {
            String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();

            // 提取JSON数组
            String jsonStr = extractJsonArray(result);
            JSONArray jsonArray = JSON.parseArray(jsonStr);

            List<String> propositions = jsonArray.toJavaList(String.class);

            // 过滤和验证
            return propositions.stream()
                    .filter(p -> p != null && !p.trim().isEmpty())
                    .map(String::trim)
                    .filter(p -> p.length() <= maxPropositionLength)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("命题提取失败，使用原始文本: {}", e.getMessage());
            // 降级：返回原始文本
            List<String> fallback = new ArrayList<>();
            fallback.add(text);
            return fallback;
        }
    }

    /**
     * 从响应中提取JSON数组
     */
    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 按长度分割文本
     */
    private List<String> splitByLength(String text, int maxLength) {
        List<String> segments = new ArrayList<>();
        int length = text.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + maxLength, length);

            // 尝试在句子边界分割
            if (end < length) {
                int lastPeriod = text.lastIndexOf('。', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int splitPoint = Math.max(lastPeriod, lastNewline);

                if (splitPoint > start + maxLength / 2) {
                    end = splitPoint + 1;
                }
            }

            segments.add(text.substring(start, end));
            start = end;
        }

        return segments;
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * 构建chunk实体
     */
    private DocumentChunkEntity buildChunkEntity(String content, int chunkIndex,
                                                  String fileName, String extension, String title) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", fileName);
        metadata.put("_file_name", fileName);
        metadata.put("_extension", extension);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("chunkType", "proposition");
        if (title != null) {
            metadata.put("title", title);
        }

        return DocumentChunkEntity.builder()
                .id(fileName + "_prop_" + chunkIndex)
                .content(content)
                .metadata(metadata)
                .build();
    }

}
