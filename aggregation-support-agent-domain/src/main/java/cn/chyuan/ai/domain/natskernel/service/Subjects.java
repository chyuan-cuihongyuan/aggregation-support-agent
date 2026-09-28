package cn.chyuan.ai.domain.natskernel.service;

/**
 * subject 词法（工单 0944 EB1，nats-server 思想）。
 * token 划分/空 token 与非法字符拒绝/`>` 仅可居尾。
 */
public final class Subjects {

    private Subjects() {
    }

    /** 词法校验并切分 token：点分非空、字符限字母数字与 * > - _ 、`>` 仅可居尾 */
    public static String[] parse(String subject) {
        if (subject == null || subject.isEmpty()) {
            throw new IllegalArgumentException("subject 为空");
        }
        String[] tokens = subject.split("\\.", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                throw new IllegalArgumentException("空 token: " + subject);
            }
            for (int j = 0; j < token.length(); j++) {
                char c = token.charAt(j);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '*' || c == '>' || c == '-' || c == '_';
                if (!ok) {
                    throw new IllegalArgumentException("非法字符 '" + c + "': " + subject);
                }
            }
            if (token.contains(">") && (!token.equals(">") || i != tokens.length - 1)) {
                throw new IllegalArgumentException("> 仅可独立居尾: " + subject);
            }
        }
        return tokens;
    }

    /** 是否含通配符（* 或 >） */
    public static boolean hasWildcard(String subject) {
        for (String token : parse(subject)) {
            if (token.equals("*") || token.equals(">")) {
                return true;
            }
        }
        return false;
    }

    /** 字面 subject（无通配符）才为 true */
    public static boolean isLiteral(String subject) {
        return !hasWildcard(subject);
    }
}
