package cn.chyuan.ai.domain.rag.service.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 句子分割工具类 — 提供按句子边界分割文本的公共方法
 * <p>
 * 被以下类复用：
 * <ul>
 *   <li>SemanticChunker — 语义分块</li>
 *   <li>ParentChildChunker — Parent-Child分块</li>
 *   <li>SentenceWindowRetriever — 句子窗口检索</li>
 *   <li>RagService — 基础RAG服务</li>
 * </ul>
 */
public final class SentenceUtils {

    /** 混合句子结束符 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；.!?;\\n]");

    private SentenceUtils() {
    }

    /**
     * 按句子边界分割文本
     *
     * @param text 待分割文本
     * @return 句子列表
     */
    public static List<String> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sentences;
        }

        String[] parts = SENTENCE_END.split(text, -1);
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            current.append(parts[i]);

            int pos = getEndPosition(parts, i);
            if (pos < text.length()) {
                char endChar = text.charAt(pos);
                if (isSentenceEnd(endChar)) {
                    current.append(endChar);
                    String sentence = current.toString().trim();
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    current = new StringBuilder();
                }
            }
        }

        if (current.length() > 0) {
            String sentence = current.toString().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    /**
     * 计算分割位置
     *
     * @param parts  分割后的片段数组
     * @param index  当前索引
     * @return 原始字符串中的位置
     */
    public static int getEndPosition(String[] parts, int index) {
        int pos = 0;
        for (int i = 0; i <= index; i++) {
            pos += parts[i].length();
            if (i < index) {
                pos++;
            }
        }
        return pos;
    }

    /**
     * 判断是否为句子结束符
     *
     * @param c 字符
     * @return 是否为句子结束符
     */
    public static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' ||
               c == '.' || c == '!' || c == '?' || c == ';' || c == '\n';
    }
}
