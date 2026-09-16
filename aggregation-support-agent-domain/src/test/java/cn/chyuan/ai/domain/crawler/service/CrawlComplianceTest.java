package cn.chyuan.ai.domain.crawler.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AY1/AY2/AY4 单测（工单 0419/0420/0422）：robots 判定/URL 归一指纹/令牌桶节流。
 */
class CrawlComplianceTest {

    private static final String ROBOTS = """
            User-agent: *
            Disallow: /private
            Allow: /private/pub
            Crawl-delay: 1.5

            User-agent: botx
            Disallow: /secret$
            Disallow: /tmp/*/cache
            """;

    @Test
    void robots分组解析与最长匹配优先() {
        RobotsJudge judge = new RobotsJudge(ROBOTS);
        // * 组：/private 禁止
        assertFalse(judge.judge("AnyBot", "/private/admin").allowed());
        // 同长 Allow 优先：/private/pub 允许（Allow 与 Disallow 前缀重叠时 Allow 长 4）
        assertTrue(judge.judge("AnyBot", "/private/pub/page").allowed());
        // 无命中放行
        assertTrue(judge.judge("AnyBot", "/open/page").allowed());
        // crawl-delay 1.5s
        assertEquals(1500L, judge.crawlDelayMs("AnyBot"));
        // botx 精确组优先于 * 组
        assertFalse(judge.judge("botx", "/secret").allowed());
        assertTrue(judge.judge("botx", "/secret/page").allowed());
        // 通配 * 中段匹配
        assertFalse(judge.judge("botx", "/tmp/x/cache").allowed());
        // 空文本全放行
        assertTrue(new RobotsJudge("").judge("a", "/x").allowed());
    }

    @Test
    void url归一规则与指纹稳定() {
        UrlNormalizer normalizer = new UrlNormalizer();
        var a = normalizer.normalize("HTTP://Example.com:80/a//b/?utm_source=x&b=2&a=1#frag");
        assertEquals("http://example.com/a/b?a=1&b=2", a.canonical());
        var b = normalizer.normalize("http://example.com/a/b?a=1&b=2");
        assertEquals(a.fingerprint(), b.fingerprint());
        // https 443 默认端口剥离
        assertEquals("https://example.com/", normalizer.normalize("https://example.com:443").canonical());
        // 尾斜杠归一
        assertEquals("http://example.com/x", normalizer.normalize("http://example.com/x/").canonical());
        // 非 http scheme 拒绝、缺 host 拒绝
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("ftp://example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("http://"));
        // 不同 URL 指纹不同
        assertNotEquals(a.fingerprint(), normalizer.normalize("http://example.com/c").fingerprint());
        assertEquals(64, a.fingerprint().length());
    }

    @Test
    void 令牌桶填充扣减与域隔离() {
        AtomicLong now = new AtomicLong(1_000);
        TokenBucketThrottle throttle = new TokenBucketThrottle(2, 1.0, now::get);
        // 满桶连取 2 次成功，第 3 次拒绝并给等待估算
        assertTrue(throttle.tryAcquire("a.com").granted());
        assertTrue(throttle.tryAcquire("a.com").granted());
        TokenBucketThrottle.Attempt denied = throttle.tryAcquire("a.com");
        assertFalse(denied.granted());
        assertEquals(1000L, denied.waitMs());
        // 时间推进 1s 填充 1 个令牌
        now.set(2_000);
        assertTrue(throttle.tryAcquire("a.com").granted());
        // 域隔离：b.com 满桶不受 a.com 扣减影响
        assertEquals(2.0, throttle.available("b.com"), 1e-9);
        // 非法参数拒绝
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketThrottle(0, 1, now::get));
    }
}
