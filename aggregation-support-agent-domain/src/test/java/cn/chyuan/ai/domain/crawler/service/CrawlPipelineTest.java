package cn.chyuan.ai.domain.crawler.service;

import cn.chyuan.ai.domain.crawler.service.FetchPort.FetchResult;
import cn.chyuan.ai.domain.crawler.service.FetchPort.ReplayFetchAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AY3/AY5/AY6/AY7/AY8 单测（工单 0421/0423/0424/0425/0426）：
 * 广度调度/正文抽取/链接抽取/状态机/抓取端口与组合管线。
 */
class CrawlPipelineTest {

    @Test
    void 广度调度分层优先级与上限拒绝() {
        BreadthScheduler scheduler = new BreadthScheduler(2, 3);
        assertTrue(scheduler.enqueue("f1", "u1", "a.com", 0, 0));
        assertTrue(scheduler.enqueue("f2", "u2", "a.com", 1, 0));
        assertTrue(scheduler.enqueue("f3", "u3", "a.com", 1, 5));
        // 重复指纹拒绝
        assertFalse(scheduler.enqueue("f1", "u1b", "a.com", 0, 0));
        // 深度上限拒绝
        assertFalse(scheduler.enqueue("f4", "u4", "a.com", 3, 0));
        // 容量上限拒绝
        assertFalse(scheduler.enqueue("f5", "u5", "a.com", 0, 0));
        // 出队：深度 0 先出；同深度优先级 5 先出
        assertEquals("f1", scheduler.poll().fingerprint());
        assertEquals("f3", scheduler.poll().fingerprint());
        assertEquals("f2", scheduler.poll().fingerprint());
        assertNull(scheduler.poll());
    }

    @Test
    void 正文抽取噪声剪除与标题() {
        ContentExtractor extractor = new ContentExtractor(20);
        String html = """
                <html><head><title>测试页面</title>
                <style>.x{color:red}</style></head>
                <body>
                <nav><a href="/home">首页</a></nav>
                <div>这是一段足够长的正文内容，包含许多有效文字用于通过文本密度评分的门槛，足够长。</div>
                <p><a href="/a">短链接</a></p>
                <script>var x=1;</script>
                </body></html>
                """;
        ContentExtractor.Extraction extraction = extractor.extract(html);
        assertEquals("测试页面", extraction.title());
        assertFalse(extraction.empty());
        assertTrue(extraction.content().contains("正文内容"));
        assertFalse(extraction.content().contains("var x=1"));
        assertFalse(extraction.content().contains("首页"));
        // 全噪声 → 空结果标记
        assertTrue(extractor.extract("<html><body><script>x</script></body></html>").empty());
    }

    @Test
    void 链接抽取相对解析过滤去重() {
        UrlNormalizer normalizer = new UrlNormalizer();
        LinkExtractor extractor = new LinkExtractor(normalizer);
        String html = """
                <a href="/docs/a">a</a>
                <a href="./b">b</a>
                <a href="../up/c">c</a>
                <a href="https://other.com/x">x</a>
                <a href="mailto:a@b.c">m</a>
                <a href="javascript:void(0)">j</a>
                <a href="#anchor">s</a>
                <a href="/docs/a?utm_source=rss">dup</a>
                """;
        List<String> links = extractor.extract(html, "http://ex.com/dir/page");
        // 归一去重：/docs/a 与 utm 版本同指纹，只留一条且保序
        assertEquals(List.of("http://ex.com/docs/a", "http://ex.com/dir/b", "http://ex.com/up/c",
                "https://other.com/x"), links);
    }

    @Test
    void 状态机转移与重试耗尽() {
        CrawlUrlStateMachine machine = new CrawlUrlStateMachine(2);
        // PENDING 重试两次仍 PENDING，第三次转 FAILED
        var t1 = machine.transition(CrawlUrlStateMachine.State.PENDING, CrawlUrlStateMachine.Event.MARK_RETRY, 0);
        assertTrue(t1.accepted());
        assertEquals(CrawlUrlStateMachine.State.PENDING, t1.to());
        var t2 = machine.transition(CrawlUrlStateMachine.State.PENDING, CrawlUrlStateMachine.Event.MARK_RETRY, 2);
        assertEquals(CrawlUrlStateMachine.State.FAILED, t2.to());
        // 成功转 FETCHED 后终态拒绝
        var t3 = machine.transition(CrawlUrlStateMachine.State.PENDING, CrawlUrlStateMachine.Event.MARK_FETCHED, 0);
        assertEquals(CrawlUrlStateMachine.State.FETCHED, t3.to());
        assertFalse(machine.transition(CrawlUrlStateMachine.State.FETCHED,
                CrawlUrlStateMachine.Event.MARK_RETRY, 0).accepted());
        assertFalse(machine.transition(CrawlUrlStateMachine.State.FAILED,
                CrawlUrlStateMachine.Event.MARK_FETCHED, 0).accepted());
        // 非法重试上限
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CrawlUrlStateMachine(-1));
    }

    @Test
    void 录制回放与组合管线() {
        AtomicLong now = new AtomicLong(1_000);
        UrlNormalizer normalizer = new UrlNormalizer();
        // 合成三页站点：/link 页链接到 /a /b，/private 禁抓
        String linkPage = """
                <html><head><title>入口</title></head><body>
                <div><a href="/a">A</a><a href="/b">B</a><a href="/private">P</a></div>
                </body></html>
                """;
        String aPage = "<html><body><p>" + "A 页正文内容，足够长以通过抽取门槛的评分要求，继续填充文字。" + "</p></body></html>";
        String bPage = "<html><body><p>" + "B 页正文内容，足够长以通过抽取门槛的评分要求，继续填充文字。" + "</p></body></html>";
        ReplayFetchAdapter fetcher = new ReplayFetchAdapter(now::get)
                .record("http://site.com/link", 200, linkPage)
                .record("http://site.com/a", 200, aPage)
                .record("http://site.com/b", 200, bPage);
        RobotsJudge robots = new RobotsJudge("User-agent: *\nDisallow: /private\n");
        BreadthScheduler scheduler = new BreadthScheduler(4, 16);
        TokenBucketThrottle throttle = new TokenBucketThrottle(8, 100, now::get);
        ContentExtractor extractor = new ContentExtractor(20);
        LinkExtractor linkExtractor = new LinkExtractor(normalizer);

        // 未录制 URL → NOT_RECORDED 失败
        FetchResult miss = fetcher.fetch("http://site.com/none");
        assertTrue(miss.notRecorded());

        // 组合管线：robots 过滤 → 调度 → 节流 → 抓取 → 抽取/链接
        scheduler.enqueue(normalizer.normalize("http://site.com/link").fingerprint(),
                "http://site.com/link", "site.com", 0, 0);
        int fetched = 0;
        BreadthScheduler.Entry entry;
        while ((entry = scheduler.poll()) != null) {
            if (!robots.judge("Crawler", entry.url().replace("http://site.com", "")).allowed()) {
                continue;
            }
            assertTrue(throttle.tryAcquire(entry.domain()).granted());
            FetchResult result = fetcher.fetch(entry.url());
            assertTrue(result.ok());
            fetched++;
            ContentExtractor.Extraction extraction = extractor.extract(result.body());
            if (extraction.title().equals("入口")) {
                for (String link : linkExtractor.extract(result.body(), entry.url())) {
                    if (!robots.judge("Crawler", link.replace("http://site.com", "")).allowed()) {
                        continue;
                    }
                    scheduler.enqueue(normalizer.normalize(link).fingerprint(), link, "site.com",
                            entry.depth() + 1, 0);
                }
            }
        }
        // /a /b 抓取成功；/private 被 robots 拦下从未入队抓取
        assertEquals(3, fetched);
        assertFalse(scheduler.seen(normalizer.normalize("http://site.com/private").fingerprint()));
        assertTrue(fetched > 0 && now.get() >= 1_000);
    }
}
