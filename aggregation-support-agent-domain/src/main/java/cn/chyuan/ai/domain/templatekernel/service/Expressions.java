package cn.chyuan.ai.domain.templatekernel.service;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 表达式求值与过滤器（工单 0627 BW2，jinja2 表达式思想）。
 * 字面量（字符串/整数/小数/布尔/null）/变量路径与属性访问/
 * 过滤器管道 upper·lower·length·default·join·round·trim·first·sort·safe/
 * 比较与 and·or·not 短路/算术四则/宏调用（scope 内宏函数）/
 * 未知变量默认 null（严格模式拒绝）。
 */
public final class Expressions {

    /** 求值上下文：变量作用域 + 宏调用 + 属性访问端口 */
    public interface Scope {
        Object variable(String name);
    }

    /** 过滤器接口 */
    public interface Filter {
        Object apply(Object input, List<Object> args);
    }

    private final Map<String, Filter> filters;
    private final boolean strict;
    private final java.util.function.BiFunction<Object, String, Object> propertyAccess;

    public Expressions(Map<String, Filter> filters, boolean strict,
                       java.util.function.BiFunction<Object, String, Object> propertyAccess) {
        this.filters = Map.copyOf(filters);
        this.strict = strict;
        this.propertyAccess = propertyAccess;
    }

    /** 求值入口（递归下降） */
    public Object eval(String expr, Scope scope) {
        Parser parser = new Parser(expr, this, scope);
        Object value = parser.parseOr();
        parser.expectEnd();
        return value;
    }

    boolean strict() {
        return strict;
    }

    Object property(Object target, String name) {
        return propertyAccess.apply(target, name);
    }

    Filter filter(String name) {
        return filters.get(name);
    }

    boolean hasFilter(String name) {
        return filters.containsKey(name);
    }

    /** 递归下降分析+求值一体（模板表达式为一次性的受控子集） */
    private static final class Parser {
        private final String src;
        private int pos;
        private final Expressions owner;
        private final Scope scope;

        Parser(String src, Expressions owner, Scope scope) {
            this.src = src == null ? "" : src;
            this.owner = owner;
            this.scope = scope;
        }

        Object parseOr() {
            Object left = parseAnd();
            while (peekWord("or")) {
                Object right = parseAnd();
                left = truthy(left) ? left : right;
            }
            return left;
        }

        Object parseAnd() {
            Object left = parseNot();
            while (peekWord("and")) {
                Object right = parseNot();
                if (!truthy(left)) {
                    return Boolean.FALSE;
                }
                left = right;
            }
            return left;
        }

        Object parseNot() {
            if (peekWord("not")) {
                return !truthy(parseNot());
            }
            return parseCompare();
        }

        Object parseCompare() {
            Object left = parseAdd();
            String op = peekCompare();
            if (op != null) {
                Object right = parseAdd();
                return compare(left, op, right);
            }
            return left;
        }

        Object parseAdd() {
            Object left = parseMul();
            while (true) {
                skipSpace();
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    char op = src.charAt(pos++);
                    Object right = parseMul();
                    left = arithmetic(left, op, right);
                } else {
                    return left;
                }
            }
        }

        Object parseMul() {
            Object left = parseUnary();
            while (true) {
                skipSpace();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/'
                        || src.charAt(pos) == '%')) {
                    char op = src.charAt(pos++);
                    Object right = parseUnary();
                    left = arithmetic(left, op, right);
                } else {
                    return left;
                }
            }
        }

        Object parseUnary() {
            skipSpace();
            if (pos < src.length() && src.charAt(pos) == '-') {
                pos++;
                return arithmetic(0, '-', parseUnary());
            }
            return parsePipe();
        }

        Object parsePipe() {
            Object value = parsePrimary();
            while (true) {
                skipSpace();
                if (pos < src.length() && src.charAt(pos) == '|') {
                    pos++;
                    skipSpace();
                    String name = readIdentifier();
                    List<Object> args = new java.util.ArrayList<>();
                    skipSpace();
                    if (pos < src.length() && src.charAt(pos) == '(') {
                        pos++;
                        skipSpace();
                        if (pos < src.length() && src.charAt(pos) != ')') {
                            args.add(parseOr());
                            skipSpace();
                            while (pos < src.length() && src.charAt(pos) == ',') {
                                pos++;
                                args.add(parseOr());
                                skipSpace();
                            }
                        }
                        expect(')');
                    }
                    Filter filter = owner.filter(name);
                    if (filter == null) {
                        throw new IllegalArgumentException("未知过滤器：" + name);
                    }
                    value = filter.apply(value, args);
                } else {
                    return value;
                }
            }
        }

        Object parsePrimary() {
            skipSpace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("表达式意外结束");
            }
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                Object value = parseOr();
                skipSpace();
                expect(')');
                return value;
            }
            if (c == '\'' || c == '"') {
                return readString(c);
            }
            if (Character.isDigit(c)) {
                return readNumber();
            }
            String ident = readIdentifier();
            if (ident.isEmpty()) {
                throw new IllegalArgumentException("无法解析的表达式片段：" + src.substring(pos));
            }
            if (ident.equals("true")) {
                return Boolean.TRUE;
            }
            if (ident.equals("false")) {
                return Boolean.FALSE;
            }
            if (ident.equals("null")) {
                return null;
            }
            Object value = resolveIdent(ident);
            skipSpace();
            if (pos < src.length() && src.charAt(pos) == '(') {
                pos++;
                List<Object> args = new java.util.ArrayList<>();
                skipSpace();
                if (pos < src.length() && src.charAt(pos) != ')') {
                    args.add(parseOr());
                    skipSpace();
                    while (pos < src.length() && src.charAt(pos) == ',') {
                        pos++;
                        args.add(parseOr());
                        skipSpace();
                    }
                }
                expect(')');
                if (!(value instanceof TemplateRenderer.MacroValue macro)) {
                    throw new IllegalArgumentException("不可调用对象：" + ident);
                }
                return macro.invoke(args);
            }
            while (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                String property = readIdentifier();
                value = owner.property(value, property);
            }
            return value;
        }

        private Object resolveIdent(String ident) {
            Object value = scope.variable(ident);
            if (value == null && owner.strict()) {
                throw new IllegalArgumentException("未知变量（严格模式）：" + ident);
            }
            return value;
        }

        private String readIdentifier() {
            skipSpace();
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos))
                    || src.charAt(pos) == '_' || src.charAt(pos) >= 0x4E00)) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private String readString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != quote) {
                sb.append(src.charAt(pos++));
            }
            expect(quote);
            return sb.toString();
        }

        private Number readNumber() {
            int start = pos;
            boolean dot = false;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                if (src.charAt(pos) == '.') {
                    if (dot) {
                        break;
                    }
                    dot = true;
                }
                pos++;
            }
            String num = src.substring(start, pos);
            return dot ? (Number) Double.parseDouble(num) : (Number) Long.parseLong(num);
        }

        private boolean peekWord(String word) {
            skipSpace();
            if (src.startsWith(word, pos)
                    && (pos + word.length() >= src.length()
                    || !Character.isLetterOrDigit(src.charAt(pos + word.length())))) {
                pos += word.length();
                return true;
            }
            return false;
        }

        private String peekCompare() {
            for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                if (src.startsWith(op, pos)) {
                    pos += op.length();
                    return op;
                }
            }
            return null;
        }

        private void expect(char c) {
            skipSpace();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("期待 '" + c + "' 于位置 " + pos);
            }
            pos++;
        }

        void expectEnd() {
            skipSpace();
            if (pos < src.length()) {
                throw new IllegalArgumentException("表达式尾部多余内容：" + src.substring(pos));
            }
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private static boolean truthy(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s) {
                return !s.isEmpty();
            }
            if (value instanceof Number n) {
                return n.doubleValue() != 0d;
            }
            if (value instanceof java.util.Collection<?> c) {
                return !c.isEmpty();
            }
            return true;
        }

        private static Object compare(Object left, String op, Object right) {
            if (left == null || right == null) {
                return switch (op) {
                    case "==" -> left == right;
                    case "!=" -> left != right;
                    default -> Boolean.FALSE;
                };
            }
            if (left instanceof Number && right instanceof Number) {
                double a = ((Number) left).doubleValue();
                double b = ((Number) right).doubleValue();
                return switch (op) {
                    case "==" -> a == b;
                    case "!=" -> a != b;
                    case "<" -> a < b;
                    case "<=" -> a <= b;
                    case ">" -> a > b;
                    case ">=" -> a >= b;
                    default -> throw new IllegalArgumentException("未知比较：" + op);
                };
            }
            int cmp = String.valueOf(left).compareTo(String.valueOf(right));
            return switch (op) {
                case "==" -> cmp == 0;
                case "!=" -> cmp != 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                default -> throw new IllegalArgumentException("未知比较：" + op);
            };
        }

        private static Object arithmetic(Object left, char op, Object right) {
            double a = left instanceof Number n ? n.doubleValue() : parseDouble(left);
            double b = right instanceof Number n2 ? n2.doubleValue() : parseDouble(right);
            double r = switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> a / b;
                case '%' -> a % b;
                default -> throw new IllegalArgumentException("未知算子：" + op);
            };
            if (r == Math.rint(r) && !Double.isInfinite(r)) {
                return (long) r;
            }
            return r;
        }

        private static double parseDouble(Object value) {
            if (value == null) {
                return 0d;
            }
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非数值操作数：" + value);
            }
        }
    }
}
