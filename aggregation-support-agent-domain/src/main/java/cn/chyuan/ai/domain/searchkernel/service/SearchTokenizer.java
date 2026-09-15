package cn.chyuan.ai.domain.searchkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分词器子集（工单 0397 AW2，charabia 思想简化）。
 * 连续字母数字段为一个词、CJK 每字一词、空白标点切分；小写归一+全角折半。确定性纯函数。
 */
public class SearchTokenizer {

    /**
     * 分词：混合文本 → 词序列（英数段整段保留、CJK 单字切分）。
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder run = new StringBuilder();
        for (char raw : text.toCharArray()) {
            char ch = normalizeChar(raw);
            if (isCjk(ch)) {
                if (run.length() > 0) {
                    tokens.add(run.toString());
                    run.setLength(0);
                }
                tokens.add(String.valueOf(ch));
            } else if (Character.isLetterOrDigit(ch)) {
                run.append(ch);
            } else {
                if (run.length() > 0) {
                    tokens.add(run.toString());
                    run.setLength(0);
                }
            }
        }
        if (run.length() > 0) {
            tokens.add(run.toString());
        }
        return tokens;
    }

    /** 归一：小写 + 全角折半 */
    char normalizeChar(char ch) {
        if (ch >= 0xFF01 && ch <= 0xFF5E) {
            ch = (char) (ch - 0xFEE0);
        } else if (ch == 0x3000) {
            ch = ' ';
        }
        return Character.toLowerCase(ch);
    }

    /** CJK 统一表意文字区 */
    static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }
}
