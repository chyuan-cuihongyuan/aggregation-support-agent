package cn.chyuan.ai.domain.speech.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 逆文本归一化 ITN（工单 0381 AU3）。
 * 口头形式→书面形式：中文数字→阿拉伯数字、百分比、日期、单位读法；
 * 规则表可配置（按优先级顺序应用）；无规则命中原样透传。纯函数。
 */
public class InverseTextNormalizer {

    /** 归一规则：名称 + 匹配模式 + 替换器 */
    public record Rule(String name, Pattern pattern, java.util.function.Function<java.util.regex.Matcher, String> replacer) {
    }

    private final List<Rule> rules;

    public InverseTextNormalizer(List<Rule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** 默认中文规则表（优先级从高到低） */
    public static InverseTextNormalizer defaults() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("percent", Pattern.compile("百分之([零一二三四五六七八九十百千]+点?[零一二三四五六七八九十]*)"),
                m -> chineseToNumber(m.group(1)) + "%"));
        rules.add(new Rule("date", Pattern.compile("([零一二三四五六七八九十百千]+)月([零一二三四五六七八九十百千]+)日"),
                m -> chineseToNumber(m.group(1)) + "月" + chineseToNumber(m.group(2)) + "日"));
        rules.add(new Rule("number", Pattern.compile("[零一二三四五六七八九十百千万]+"),
                m -> chineseToNumber(m.group())));
        rules.add(new Rule("meter", Pattern.compile("([一二三四五六七八九十百千]+)米"),
                m -> chineseToNumber(m.group(1)) + "米"));
        return new InverseTextNormalizer(rules);
    }

    /** 归一文本：按规则优先级依次替换 */
    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text;
        for (Rule rule : rules) {
            java.util.regex.Matcher matcher = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(rule.replacer().apply(matcher)));
            }
            matcher.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** 中文数字→阿拉伯数字（支持 零一二三四五六七八九十百千万 与 点分小数逐位） */
    static String chineseToNumber(String chinese) {
        if (chinese == null || chinese.isEmpty()) {
            return "0";
        }
        int dot = chinese.indexOf('点');
        if (dot >= 0) {
            String intPart = chineseToNumber(chinese.substring(0, dot));
            StringBuilder frac = new StringBuilder();
            for (char ch : chinese.substring(dot + 1).toCharArray()) {
                frac.append(switch (ch) {
                    case '零' -> '0';
                    case '一' -> '1';
                    case '二' -> '2';
                    case '三' -> '3';
                    case '四' -> '4';
                    case '五' -> '5';
                    case '六' -> '6';
                    case '七' -> '7';
                    case '八' -> '8';
                    case '九' -> '9';
                    default -> ch;
                });
            }
            return intPart + "." + frac;
        }
        Map<Character, Long> digit = new LinkedHashMap<>();
        digit.put('零', 0L);
        digit.put('一', 1L);
        digit.put('二', 2L);
        digit.put('三', 3L);
        digit.put('四', 4L);
        digit.put('五', 5L);
        digit.put('六', 6L);
        digit.put('七', 7L);
        digit.put('八', 8L);
        digit.put('九', 9L);
        long section = 0;
        long total = 0;
        long current = 0;
        for (char ch : chinese.toCharArray()) {
            if (digit.containsKey(ch)) {
                current = digit.get(ch);
            } else if (ch == '十') {
                section += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (ch == '百') {
                section += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else if (ch == '千') {
                section += (current == 0 ? 1 : current) * 1000;
                current = 0;
            } else if (ch == '万') {
                total = (total + section + current) * 10000;
                section = 0;
                current = 0;
            }
        }
        return String.valueOf(total + section + current);
    }
}
