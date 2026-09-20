package cn.chyuan.ai.domain.jobkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * cron 表达式解析（工单 0496 BH1，quartz/xxl-job cron 子集思想）。
 * 六域子集（秒 分 时 日 月 周）+ `, - * / ?` 语法/域取值范围校验/
 * 非法域拒绝并报告出错字段与位置。
 */
public class CronParser {

    /** 解析后的 cron：六个域的合法秒/分/时/日/月/周取值集合 */
    public record CronExpression(String expression, Set<Integer> seconds, Set<Integer> minutes,
            Set<Integer> hours, Set<Integer> daysOfMonth, Set<Integer> months, Set<Integer> daysOfWeek,
            boolean dayOfMonthWildcard, boolean dayOfWeekWildcard) {
    }

    /** 解析异常：携带域名字段与位置 */
    public static final class CronParseException extends IllegalArgumentException {
        private final String field;
        private final int position;

        public CronParseException(String field, int position, String message) {
            super("cron 解析失败（域 " + field + " 位置 " + position + "）: " + message);
            this.field = field;
            this.position = position;
        }

        public String field() {
            return field;
        }

        public int position() {
            return position;
        }
    }

    private static final String[] FIELD_NAMES = {"second", "minute", "hour", "day-of-month", "month", "day-of-week"};
    private static final int[] MIN = {0, 0, 0, 1, 1, 1};
    private static final int[] MAX = {59, 59, 23, 31, 12, 7};

    /** 解析六域 cron 表达式（空格分隔；日/周域 ? 视为通配） */
    public CronExpression parse(String expression) {
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 6) {
            throw new CronParseException("expression", 0, "须六域（秒 分 时 日 月 周），实际 " + fields.length + " 域");
        }
        Set<Integer>[] values = new Set[6];
        boolean domWildcard = false;
        boolean dowWildcard = false;
        int cursor = 0;
        for (int f = 0; f < 6; f++) {
            String field = fields[f];
            if ("?".equals(field) && (f == 3 || f == 5)) {
                values[f] = Set.copyOf(range(MIN[f], MAX[f]));
                if (f == 3) {
                    domWildcard = true;
                } else {
                    dowWildcard = true;
                }
                cursor += 2;
                continue;
            }
            values[f] = parseField(field, f, cursor);
            cursor += field.length() + 1;
        }
        return new CronExpression(expression, values[0], values[1], values[2], values[3], values[4], values[5],
                domWildcard, dowWildcard);
    }

    /** 单域解析：逗号分隔列表，元素支持 `*`、`a`、`a-b`、`a/b`、`a-b/c` */
    private Set<Integer> parseField(String field, int fieldIndex, int basePosition) {
        Set<Integer> result = new java.util.TreeSet<>();
        for (String part : field.split(",")) {
            if (part.isEmpty()) {
                throw new CronParseException(FIELD_NAMES[fieldIndex], basePosition, "空列表元素");
            }
            int slash = part.indexOf('/');
            String rangePart = slash >= 0 ? part.substring(0, slash) : part;
            int step = slash >= 0 ? parseNumber(part.substring(slash + 1), fieldIndex, basePosition) : 1;
            if (step <= 0) {
                throw new CronParseException(FIELD_NAMES[fieldIndex], basePosition, "步长须 > 0: " + step);
            }
            int from;
            int to;
            if ("*".equals(rangePart)) {
                from = MIN[fieldIndex];
                to = MAX[fieldIndex];
            } else if (rangePart.contains("-")) {
                int dash = rangePart.indexOf('-');
                from = parseNumber(rangePart.substring(0, dash), fieldIndex, basePosition);
                to = parseNumber(rangePart.substring(dash + 1), fieldIndex, basePosition);
            } else {
                from = parseNumber(rangePart, fieldIndex, basePosition);
                to = slash >= 0 ? MAX[fieldIndex] : from;
            }
            if (from < MIN[fieldIndex] || to > MAX[fieldIndex] || from > to) {
                throw new CronParseException(FIELD_NAMES[fieldIndex], basePosition,
                        "取值越界或区间非法: " + from + "-" + to + "（合法 " + MIN[fieldIndex] + ".." + MAX[fieldIndex] + "）");
            }
            result.addAll(range(from, to, step));
        }
        return result;
    }

    private int parseNumber(String s, int fieldIndex, int position) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CronParseException(FIELD_NAMES[fieldIndex], position, "非数字: " + s);
        }
    }

    private List<Integer> range(int from, int to) {
        return range(from, to, 1);
    }

    private List<Integer> range(int from, int to, int step) {
        List<Integer> values = new ArrayList<>();
        for (int v = from; v <= to; v += step) {
            values.add(v);
        }
        return values;
    }
}
