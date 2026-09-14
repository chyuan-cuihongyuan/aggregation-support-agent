package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.ResearchReportVO;
import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用对齐校验器（工单 0350 AR4，gpt-researcher citation 校验思想）。
 * 报告拆断言句 → 引用锚点 [n] 存在性 + 断言与所引来源内容关键词重叠支撑度
 * → 无锚点/锚点无效/支撑不足三类标记 + 对齐率。domain 纯函数。
 */
public class CitationAligner {

    private final double supportThreshold;

    public CitationAligner(double supportThreshold) {
        if (supportThreshold < 0 || supportThreshold > 1) {
            throw new IllegalArgumentException("支撑度阈值应在 [0,1]");
        }
        this.supportThreshold = supportThreshold;
    }

    /** 对齐报告 */
    public record Alignment(String assertion, String issue, List<Integer> citations) {

        public static final String OK = "OK";
        public static final String NO_ANCHOR = "NO_ANCHOR";
        public static final String INVALID_ANCHOR = "INVALID_ANCHOR";
        public static final String WEAK_SUPPORT = "WEAK_SUPPORT";
    }

    /** 校验章节正文（断言[n] 标注形态） */
    public List<Alignment> align(String content, List<ResearchReportVO.CitationVO> citations,
                                 List<SearchHitVO> sources) {
        List<Alignment> out = new ArrayList<>();
        Set<Integer> validNos = new HashSet<>();
        if (citations != null) {
            citations.forEach(c -> validNos.add(c.getNo()));
        }
        for (String sentence : split(content)) {
            List<Integer> nos = extractAnchors(sentence);
            String bare = sentence.replaceAll("\\[\\d+]", "").trim();
            if (nos.isEmpty()) {
                out.add(new Alignment(sentence, Alignment.NO_ANCHOR, nos));
                continue;
            }
            boolean invalid = nos.stream().anyMatch(no -> !validNos.contains(no));
            if (invalid) {
                out.add(new Alignment(sentence, Alignment.INVALID_ANCHOR, nos));
                continue;
            }
            // 支撑度：断言与所引来源内容的关键词重叠（任一所引来源达标即支撑）
            boolean supported = false;
            for (Integer no : nos) {
                SearchHitVO source = findSource(sources, citations, no);
                if (source != null && support(bare, source) >= supportThreshold) {
                    supported = true;
                    break;
                }
            }
            out.add(new Alignment(sentence, supported ? Alignment.OK : Alignment.WEAK_SUPPORT, nos));
        }
        return out;
    }

    /** 对齐率：OK 断言占比（空内容为 0） */
    public double alignmentRate(List<Alignment> alignments) {
        if (alignments.isEmpty()) {
            return 0;
        }
        long ok = alignments.stream().filter(a -> Alignment.OK.equals(a.issue())).count();
        return (double) ok / alignments.size();
    }

    private SearchHitVO findSource(List<SearchHitVO> sources,
                                   List<ResearchReportVO.CitationVO> citations, int no) {
        if (citations != null) {
            for (ResearchReportVO.CitationVO citation : citations) {
                if (citation.getNo() == no && sources != null) {
                    for (SearchHitVO source : sources) {
                        if (source.getUrl().equals(citation.getUrl())) {
                            return source;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 支撑度：断言与来源内容的词级重叠率（标题与摘要以空格拼接后分词） */
    static double support(String assertion, SearchHitVO source) {
        Set<String> assertionWords = words(assertion);
        if (assertionWords.isEmpty()) {
            return 0;
        }
        Set<String> sourceWords = words(source.getTitle() + " " + source.getSnippet());
        long hit = assertionWords.stream().filter(sourceWords::contains).count();
        return (double) hit / assertionWords.size();
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String token : text.toLowerCase().split("[^\\p{IsHan}a-z0-9]+")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    static List<Integer> extractAnchors(String sentence) {
        List<Integer> nos = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(sentence);
        while (matcher.find()) {
            nos.add(Integer.parseInt(matcher.group(1)));
        }
        return nos;
    }

    static List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return Arrays.stream(content.split("(?<=[。！？.!?\n])"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
