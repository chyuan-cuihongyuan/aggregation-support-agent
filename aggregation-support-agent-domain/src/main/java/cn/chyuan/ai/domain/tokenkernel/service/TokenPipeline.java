package cn.chyuan.ai.domain.tokenkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 编码管线（工单 0746 CJ7，transformers 思想）。
 * 归一→预切→模型（BPE/WordPiece）→后处理（模板/mask）组合/
 * 编码解码往返（特殊令牌除外）/id↔token 双向。
 */
public final class TokenPipeline {

    /** 子词模型接口：BPE 与 WordPiece 双形态 */
    public interface SubwordModel {
        List<String> encodeWord(String word);

        Map<String, Integer> vocab();

        String unk();
    }

    private final Normalizer normalizer;
    private final PreTokenizer preTokenizer;
    private final SubwordModel model;
    private final SpecialTokens specials;
    private final PaddingTrimmer trimmer;

    public TokenPipeline(Normalizer normalizer, PreTokenizer preTokenizer, SubwordModel model,
                         SpecialTokens specials, PaddingTrimmer trimmer) {
        this.normalizer = normalizer;
        this.preTokenizer = preTokenizer;
        this.model = model;
        this.specials = specials;
        this.trimmer = trimmer;
    }

    /** 编码：归一→特殊令牌保护→预切→子词→id 化→模板→截断填充 */
    public PaddingTrimmer.Padded encode(String text, boolean withSpecialTokens) {
        String normalized = normalizer.normalize(text);
        List<String> segments = withSpecialTokens
                ? specials.splitProtect(normalized)
                : List.of(normalized);
        List<String> tokens = new ArrayList<>();
        for (String segment : segments) {
            if (specials.isSpecial(segment) && withSpecialTokens) {
                tokens.add(segment);
                continue;
            }
            for (PreTokenizer.Span span : preTokenizer.pretokenize(segment)) {
                tokens.addAll(model.encodeWord(span.text()));
            }
        }
        List<String> unkResolved = new ArrayList<>();
        for (String token : tokens) {
            if (specials.isSpecial(token) && withSpecialTokens) {
                unkResolved.add(token);
            } else if (model.vocab().containsKey(token)) {
                unkResolved.add(token);
            } else {
                unkResolved.add(specials.unk());
            }
        }
        List<String> trimmed = trimmer.truncate(unkResolved);
        Map<String, Integer> vocab = model.vocab();
        List<String> ids = new ArrayList<>();
        for (String token : trimmed) {
            ids.add(vocab.containsKey(token) ? String.valueOf(vocab.get(token)) : String.valueOf(vocab.getOrDefault(specials.unk(), 0)));
        }
        return trimmer.pad(ids, String.valueOf(vocab.getOrDefault(specials.pad(), 0)), null);
    }

    /** id→token 反查 */
    public String idToToken(int id) {
        for (Map.Entry<String, Integer> e : model.vocab().entrySet()) {
            if (e.getValue() == id) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("未知 id: " + id);
    }

    /** token→id */
    public int tokenToId(String token) {
        Integer id = model.vocab().get(token);
        if (id == null) {
            throw new IllegalArgumentException("未知 token: " + token);
        }
        return id;
    }

    /** 解码（id 序列→文本，跳过特殊令牌与 pad） */
    public String decode(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String token = idToToken(id);
            if (specials.isSpecial(token)) {
                continue;
            }
            if (token.startsWith(WordPiece.CONTINUATION)) {
                sb.append(token.substring(2));
            } else if (!sb.isEmpty() && !isPunctLike(token)) {
                sb.append(' ');
            }
            if (!token.startsWith(WordPiece.CONTINUATION)) {
                sb.append(token);
            }
        }
        return sb.toString().trim();
    }

    private static boolean isPunctLike(String token) {
        return !token.isEmpty() && PreTokenizer.isPunct(token.charAt(0));
    }

    public String unkToken() {
        return specials.unk();
    }

    public String padToken() {
        return specials.pad();
    }

    /** 批量填充：已编码结果补齐至目标长度 */
    public PaddingTrimmer.Padded repad(PaddingTrimmer.Padded p, int target) {
        String padId = String.valueOf(model.vocab().getOrDefault(specials.pad(), 0));
        List<String> ids = new ArrayList<>(p.ids());
        List<Integer> mask = new ArrayList<>(p.attentionMask());
        while (ids.size() < target) {
            if (trimmer.side() == PaddingTrimmer.PaddingSide.RIGHT) {
                ids.add(padId);
                mask.add(0);
            } else {
                ids.add(0, padId);
                mask.add(0, 0);
            }
        }
        return new PaddingTrimmer.Padded(ids, mask);
    }

    /** 外部词条直接成码（textkernel 联动形态） */
    public PaddingTrimmer.Padded repadTokens(List<String> tokens) {
        List<String> trimmed = trimmer.truncate(tokens);
        List<String> ids = new ArrayList<>();
        for (String token : trimmed) {
            int id = model.vocab().getOrDefault(token,
                    model.vocab().getOrDefault(specials.unk(), 0));
            ids.add(String.valueOf(id));
        }
        return trimmer.pad(ids, String.valueOf(model.vocab().getOrDefault(specials.pad(), 0)), null);
    }

    public SubwordModel model() {
        return model;
    }
}
