package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.model.valobj.SearchHitVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 来源可信度评分器（工单 0349 AR3，gpt-researcher source scoring 思想）。
 * 域名权威表（可配置分级）×新鲜度衰减 ×指纹去重（重复内容保留最高分）。
 * domain 纯函数。
 */
public class SourceScorer {

    /** 权威分级默认分 */
    private static final Map<String, Double> DEFAULT_AUTHORITY = Map.of(
            "official", 1.0, "media", 0.7, "ugc", 0.4, "unknown", 0.3);

    private final Map<String, Double> authorityByDomain;
    private final long nowMs;
    private final long freshnessHalfLifeMs;

    public SourceScorer(Map<String, Double> authorityByDomain, long nowMs, long freshnessHalfLifeMs) {
        this.authorityByDomain = authorityByDomain == null ? Map.of() : authorityByDomain;
        this.nowMs = nowMs;
        this.freshnessHalfLifeMs = Math.max(1, freshnessHalfLifeMs);
    }

    /** 评分+去重：返回按分数降序的去重结果（指纹一致保留最高分） */
    public List<SearchHitVO> score(List<SearchHitVO> hits, Map<String, Long> publishedAtMs) {
        Map<String, SearchHitVO> best = new LinkedHashMap<>();
        if (hits == null) {
            return List.of();
        }
        for (SearchHitVO hit : hits) {
            double authority = authorityByDomain.getOrDefault(
                    domainOf(hit.getUrl()), DEFAULT_AUTHORITY.getOrDefault(hit.getAuthority(), 0.3));
            Long published = publishedAtMs == null ? null : publishedAtMs.get(hit.getUrl());
            double freshness = freshness(published);
            double score = round(authority * freshness);
            SearchHitVO scored = SearchHitVO.builder()
                    .title(hit.getTitle()).url(hit.getUrl()).snippet(hit.getSnippet())
                    .authority(hit.getAuthority())
                    .fingerprint(hit.getFingerprint() == null ? fingerprint(hit.getSnippet()) : hit.getFingerprint())
                    .score(score)
                    .build();
            String key = scored.getFingerprint();
            SearchHitVO previous = best.get(key);
            if (previous == null || score > previous.getScore()) {
                best.put(key, scored);
            }
        }
        List<SearchHitVO> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(SearchHitVO::getScore).reversed()
                .thenComparing(SearchHitVO::getUrl, Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    /** 新鲜度：半衰期指数衰减（无发表时间按 1.0） */
    double freshness(Long publishedAtMs) {
        if (publishedAtMs == null) {
            return 1.0;
        }
        long age = Math.max(0, nowMs - publishedAtMs);
        return Math.pow(0.5, (double) age / freshnessHalfLifeMs);
    }

    static String domainOf(String url) {
        if (url == null) {
            return "";
        }
        String body = url.replaceFirst("^https?://", "");
        int slash = body.indexOf('/');
        return slash < 0 ? body : body.substring(0, slash);
    }

    static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    static String fingerprint(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
