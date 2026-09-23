package cn.chyuan.ai.domain.templatekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 模板渲染器（工单 0628 BW3 + 0629 BW4 + 0630 BW5，jinja2 渲染思想）。
 * for 迭代（loop.index/index0/first/last/length 变量）空集合安全；
 * if·elif·else 链；宏定义参数默认值与调用（同模板可见，多余参数拒绝）；
 * extends 单继承/同名 block 覆盖（最派生定义胜出）/块内 parent() 调父块/
 * 多层继承链解析（孙→子→父）/循环继承拒绝。
 */
public final class TemplateRenderer {

    /** AST 节点 */
    public sealed interface Node permits TextNode, OutputNode, IfNode, ForNode,
            MacroNode, BlockNode, ExtendsNode {
    }

    public record TextNode(String text) implements Node {
    }

    public record OutputNode(String expr) implements Node {
    }

    public record IfNode(List<Branch> branches) implements Node {

        public record Branch(String condition, List<Node> body) {

            public Branch {
                body = List.copyOf(body);
            }
        }

        public IfNode {
            branches = List.copyOf(branches);
        }
    }

    public record ForNode(String var, String iterableExpr, List<Node> body) implements Node {

        public ForNode {
            body = List.copyOf(body);
        }
    }

    public record MacroNode(String name, Map<String, String> params, List<Node> body) implements Node {

        public MacroNode {
            params = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params));
            body = List.copyOf(body);
        }
    }

    public record BlockNode(String name, List<Node> body) implements Node {

        public BlockNode {
            body = List.copyOf(body);
        }
    }

    public record ExtendsNode(String parent) implements Node {
    }

    /** 可调用宏值（宏定义或父块代理） */
    public static final class MacroValue {
        private final Function<List<Object>, Object> invoker;

        MacroValue(Function<List<Object>, Object> invoker) {
            this.invoker = invoker;
        }

        public Object invoke(List<Object> args) {
            return invoker.apply(args);
        }
    }

    private final Expressions expressions;
    private final Escaping escaping;

    public TemplateRenderer(Expressions expressions, Escaping escaping) {
        this.expressions = expressions;
        this.escaping = escaping;
    }

    private static final String[] IF_STOPS = {"elif", "else", "endif"};
    private static final String[] ENDIF_ONLY = {"endif"};

    /** 解析记号流为 AST */
    public List<Node> parse(List<TemplateLexer.Token> tokens) {
        List<Node> nodes = new ArrayList<>();
        parseNodes(tokens, new int[]{0}, null, nodes);
        return nodes;
    }

    private void parseNodes(List<TemplateLexer.Token> tokens, int[] cursor,
                            String[] stops, List<Node> out) {
        while (cursor[0] < tokens.size()) {
            TemplateLexer.Token token = tokens.get(cursor[0]);
            if (token.kind() == TemplateLexer.Kind.TEXT) {
                out.add(new TextNode(token.source()));
                cursor[0]++;
            } else if (token.kind() == TemplateLexer.Kind.VAR) {
                out.add(new OutputNode(token.source()));
                cursor[0]++;
            } else {
                String stmt = token.source();
                if (stops != null && matchesAny(stmt, stops)) {
                    return;
                }
                cursor[0]++;
                if (stmt.startsWith("if ")) {
                    List<IfNode.Branch> branches = new ArrayList<>();
                    List<Node> body = new ArrayList<>();
                    parseNodes(tokens, cursor, IF_STOPS, body);
                    branches.add(new IfNode.Branch(stmt.substring(3).trim(), body));
                    while (cursor[0] < tokens.size()
                            && tokens.get(cursor[0]).source().startsWith("elif")) {
                        String cond = tokens.get(cursor[0]).source().substring(4).trim();
                        cursor[0]++;
                        List<Node> elifBody = new ArrayList<>();
                        parseNodes(tokens, cursor, IF_STOPS, elifBody);
                        branches.add(new IfNode.Branch(cond, elifBody));
                    }
                    if (cursor[0] < tokens.size()
                            && tokens.get(cursor[0]).source().equals("else")) {
                        cursor[0]++;
                        List<Node> elseBody = new ArrayList<>();
                        parseNodes(tokens, cursor, ENDIF_ONLY, elseBody);
                        branches.add(new IfNode.Branch(null, elseBody));
                    }
                    expectTag(tokens, cursor, "endif");
                    out.add(new IfNode(branches));
                } else if (stmt.startsWith("for ")) {
                    String[] parts = stmt.substring(4).split(" in ", 2);
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("for 语句须为 'for x in expr'：" + stmt);
                    }
                    List<Node> body = new ArrayList<>();
                    parseNodes(tokens, cursor, new String[]{"endfor"}, body);
                    expectTag(tokens, cursor, "endfor");
                    out.add(new ForNode(parts[0].trim(), parts[1].trim(), body));
                } else if (stmt.startsWith("macro ")) {
                    String header = stmt.substring(6).trim();
                    int open = header.indexOf('(');
                    int close = header.lastIndexOf(')');
                    if (open < 0 || close < open) {
                        throw new IllegalArgumentException("宏签名非法：" + header);
                    }
                    String name = header.substring(0, open).trim();
                    Map<String, String> params = new LinkedHashMap<>();
                    for (String param : header.substring(open + 1, close).split(",")) {
                        if (param.isBlank()) {
                            continue;
                        }
                        String[] kv = param.split("=", 2);
                        params.put(kv[0].trim(), kv.length == 2 ? kv[1].trim() : "null");
                    }
                    List<Node> body = new ArrayList<>();
                    parseNodes(tokens, cursor, new String[]{"endmacro"}, body);
                    expectTag(tokens, cursor, "endmacro");
                    out.add(new MacroNode(name, params, body));
                } else if (stmt.startsWith("block ")) {
                    String name = stmt.substring(6).trim();
                    List<Node> body = new ArrayList<>();
                    parseNodes(tokens, cursor, new String[]{"endblock"}, body);
                    expectTag(tokens, cursor, "endblock");
                    out.add(new BlockNode(name, body));
                } else if (stmt.startsWith("extends ")) {
                    out.add(new ExtendsNode(unquote(stmt.substring(8).trim())));
                } else {
                    throw new IllegalArgumentException("未知标签：" + stmt);
                }
            }
        }
        if (stops != null) {
            throw new IllegalArgumentException("未闭合标签：期待 " + String.join("/", stops));
        }
    }

    private static boolean matchesAny(String stmt, String[] stops) {
        for (String stop : stops) {
            if (stmt.startsWith(stop)) {
                return true;
            }
        }
        return false;
    }

    private void expectTag(List<TemplateLexer.Token> tokens, int[] cursor, String tag) {
        if (cursor[0] >= tokens.size() || !tokens.get(cursor[0]).source().startsWith(tag)) {
            throw new IllegalArgumentException("未闭合标签：期待 " + tag);
        }
        cursor[0]++;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 直渲（无继承） */
    public String render(List<Node> nodes, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        renderNodes(nodes, new LinkedHashMap<>(data), null, sb);
        return sb.toString();
    }

    /** 继承渲染：extends 链根模板骨架 + 块体链（最派生胜出，parent() 沿链取父） */
    @SuppressWarnings("unchecked")
    public String renderWithInheritance(List<Node> templateAst,
                                        Function<String, List<Node>> loader,
                                        Map<String, Object> data) {
        List<List<Node>> chain = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        chain.add(templateAst);
        ExtendsNode rootExt = findExtends(templateAst);
        String current = rootExt == null ? null : rootExt.parent();
        int depthGuard = 0;
        while (current != null) {
            if (seen.contains(current) || ++depthGuard > 32) {
                throw new IllegalArgumentException("循环或超深继承拒绝：" + current);
            }
            seen.add(current);
            List<Node> nodes = loader.apply(current);
            chain.add(nodes);
            ExtendsNode parentExt = findExtends(nodes);
            current = parentExt == null ? null : parentExt.parent();
        }
        List<Node> root = chain.get(chain.size() - 1);
        Map<String, Object> scope = new LinkedHashMap<>(data);
        scope.put("__chain__", chain);
        StringBuilder sb = new StringBuilder();
        renderNodes(root, scope, null, sb);
        return sb.toString();
    }

    private ExtendsNode findExtends(List<Node> nodes) {
        for (Node node : nodes) {
            if (node instanceof ExtendsNode ext) {
                return ext;
            }
        }
        return null;
    }

    /** 节点渲染（作用域链） */
    @SuppressWarnings("unchecked")
    void renderNodes(List<Node> nodes, Map<String, Object> scope, Object unused, StringBuilder out) {
        for (Node node : nodes) {
            if (node instanceof TextNode text) {
                out.append(text.text());
            } else if (node instanceof OutputNode output) {
                Object value = expressions.eval(output.expr(), scope::get);
                if (value instanceof MacroValue) {
                    throw new IllegalArgumentException("宏不可直接输出：" + output.expr());
                }
                out.append(escaping.output(value));
            } else if (node instanceof IfNode ifNode) {
                for (IfNode.Branch branch : ifNode.branches()) {
                    if (branch.condition() == null
                            || truthy(expressions.eval(branch.condition(), scope::get))) {
                        renderNodes(branch.body(), scope, null, out);
                        break;
                    }
                }
            } else if (node instanceof ForNode forNode) {
                Object iterable = expressions.eval(forNode.iterableExpr(), scope::get);
                List<Object> items = new ArrayList<>();
                if (iterable instanceof Iterable<?> it) {
                    for (Object o : it) {
                        items.add(o);
                    }
                } else if (iterable != null) {
                    items.add(iterable);
                }
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> loopScope = new LinkedHashMap<>(scope);
                    loopScope.put(forNode.var(), items.get(i));
                    Map<String, Object> loop = new LinkedHashMap<>();
                    loop.put("index", i + 1);
                    loop.put("index0", i);
                    loop.put("first", i == 0);
                    loop.put("last", i == items.size() - 1);
                    loop.put("length", items.size());
                    loopScope.put("loop", loop);
                    renderNodes(forNode.body(), loopScope, null, out);
                }
            } else if (node instanceof MacroNode macro) {
                scope.put(macro.name(), macroValue(macro, scope));
            } else if (node instanceof BlockNode block) {
                List<List<Node>> chain = (List<List<Node>>) scope.get("__chain__");
                List<Node> body = blockBody(chain, block.name(), block.body());
                renderBlockBody(chain, block.name(), body, 0, scope, out);
            } else if (node instanceof ExtendsNode) {
                // 继承由入口处理，直渲忽略
            }
        }
    }

    /** 块体链：自最派生层向根层收集同名块体 */
    private List<Node> blockBody(List<List<Node>> chain, String name, List<Node> fallback) {
        for (List<Node> level : chain) {
            List<Node> body = findBlockBody(level, name);
            if (body != null) {
                return body;
            }
        }
        return fallback;
    }

    private int blockDepth(List<List<Node>> chain, String name) {
        for (int i = 0; i < chain.size(); i++) {
            if (findBlockBody(chain.get(i), name) != null) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private List<Node> findBlockBody(List<Node> nodes, String name) {
        for (Node node : nodes) {
            if (node instanceof BlockNode block && block.name().equals(name)) {
                return block.body();
            }
        }
        return null;
    }

    /** 渲染块体（depth 为链内层号；parent() 渲染下一更深定义） */
    private void renderBlockBody(List<List<Node>> chain, String name, List<Node> body,
                                 int depth, Map<String, Object> scope, StringBuilder out) {
        Map<String, Object> blockScope = new LinkedHashMap<>(scope);
        blockScope.put("parent", new MacroValue(args -> {
            List<Node> parentBody = blockBodyFrom(chain, name, depth + 1);
            StringBuilder sb = new StringBuilder();
            if (parentBody != null) {
                renderBlockBody(chain, name, parentBody, depth + 1, blockScope, sb);
            }
            return sb.toString();
        }));
        renderNodes(body, blockScope, null, out);
    }

    private List<Node> blockBodyFrom(List<List<Node>> chain, String name, int fromDepth) {
        for (int i = fromDepth; i < chain.size(); i++) {
            List<Node> body = findBlockBody(chain.get(i), name);
            if (body != null) {
                return body;
            }
        }
        return null;
    }

    private MacroValue macroValue(MacroNode macro, Map<String, Object> closure) {
        return new MacroValue(args -> {
            Map<String, Object> callScope = new LinkedHashMap<>(closure);
            List<String> names = new ArrayList<>(macro.params().keySet());
            if (args.size() > names.size()) {
                throw new IllegalArgumentException("宏 " + macro.name() + " 多余参数");
            }
            for (int i = 0; i < names.size(); i++) {
                String param = names.get(i);
                callScope.put(param, i < args.size()
                        ? args.get(i)
                        : expressions.eval(macro.params().get(param), callScope::get));
            }
            StringBuilder sb = new StringBuilder();
            renderNodes(macro.body(), callScope, null, sb);
            return sb.toString();
        });
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
}
