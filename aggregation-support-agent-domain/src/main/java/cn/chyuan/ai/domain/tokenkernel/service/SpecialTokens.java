package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 特殊令牌与模板（工单 0744 CJ5，transformers 思想）。
 * CLS·SEP·PAD·UNK 注册/单序列与句对模板拼接/特殊令牌整段优先不切分。
 */
public final class SpecialTokens {

    private final String cls;
    private final String sep;
    private final String pad;
    private final String unk;
    private final List<String> registered;

    public SpecialTokens(String cls, String sep, String pad, String unk) {
        this.cls = cls;
        this.sep = sep;
        this.pad = pad;
        this.unk = unk;
        this.registered = List.of(cls, sep, pad, unk);
    }

    public static SpecialTokens bert() {
        return new SpecialTokens("[CLS]", "[SEP]", "[PAD]", "[UNK]");
    }

    public String cls() {
        return cls;
    }

    public String sep() {
        return sep;
    }

    public String pad() {
        return pad;
    }

    public String unk() {
        return unk;
    }

    /** 单序列模板：[CLS] A [SEP] */
    public List<String> templateSingle(List<String> tokens) {
        List<String> out = new ArrayList<>();
        out.add(cls);
        out.addAll(tokens);
        out.add(sep);
        return out;
    }

    /** 句对模板：[CLS] A [SEP] B [SEP] */
    public List<String> templatePair(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>();
        out.add(cls);
        out.addAll(first);
        out.add(sep);
        out.addAll(second);
        out.add(sep);
        return out;
    }

    /** 特殊令牌整段优先切分：文本按注册令牌切段（令牌原样保留不参与子词切分） */
    public List<String> splitProtect(String text) {
        if (text == null) {
            throw new IllegalArgumentException("输入不可为 null");
        }
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int hit = -1;
            int hitLen = 0;
            for (String token : registered) {
                if (text.startsWith(token, i) && token.length() > hitLen) {
                    hit = i;
                    hitLen = token.length();
                }
            }
            if (hit >= 0) {
                segments.add(text.substring(i, i + hitLen));
                i += hitLen;
            } else {
                int next = firstSpecial(text, i + 1);
                if (next < 0) {
                    next = text.length();
                }
                segments.add(text.substring(i, next));
                i = next;
            }
        }
        return segments;
    }

    private int firstSpecial(String text, int from) {
        int at = Integer.MAX_VALUE;
        for (String token : registered) {
            int at2 = text.indexOf(token, from);
            if (at2 >= 0 && at2 < at) {
                at = at2;
            }
        }
        return at == Integer.MAX_VALUE ? -1 : at;
    }

    public boolean isSpecial(String token) {
        return registered.contains(token);
    }
}
