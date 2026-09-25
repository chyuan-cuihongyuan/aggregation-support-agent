package cn.chyuan.ai.domain.vmkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 语法树与递归下降解析（工单 0801 CQ1，cpython 编译器思想）。
 * 语句：赋值/表达式/if·else/while/break·continue/def/return/try·catch·finally；以 ; 分隔；残词拒绝。
 */
public final class Parser {

    /** 表达式 AST */
    public sealed interface Expr permits Num, Str, Bool, Var, Unary, Binary, Call {
    }

    public record Num(long value) implements Expr {
    }

    public record Str(String value) implements Expr {
    }

    public record Bool(boolean value) implements Expr {
    }

    public record Var(String name) implements Expr {
    }

    public record Unary(String op, Expr expr) implements Expr {
    }

    public record Binary(String op, Expr left, Expr right) implements Expr {
    }

    public record Call(String name, List<Expr> args) implements Expr {
    }

    /** 语句 AST */
    public sealed interface Stmt permits Assign, ExprStmt, If, While, Break, Continue, Def, Return, ThrowStmt, TryStmt {
    }

    public record Assign(String name, Expr value) implements Stmt {
    }

    public record ExprStmt(Expr expr) implements Stmt {
    }

    public record If(Expr cond, List<Stmt> then, List<Stmt> els) implements Stmt {
    }

    public record While(Expr cond, List<Stmt> body) implements Stmt {
    }

    public record Break() implements Stmt {
    }

    public record Continue() implements Stmt {
    }

    public record Def(String name, List<String> params, List<Stmt> body) implements Stmt {
    }

    public record Return(Expr value) implements Stmt {
    }

    public record ThrowStmt(Expr value) implements Stmt {
    }

    /** finallyStyle=true 为 try/finally（else 分支即 finally 体），false 为 try/catch */
    public record TryStmt(List<Stmt> body, String catchVar, List<Stmt> handler, boolean finallyStyle) implements Stmt {
    }

    private final List<Lexer.Token> tokens;
    private int at = 0;

    public Parser(String source) {
        this.tokens = new Lexer(source).lex();
    }

    public List<Stmt> parseProgram() {
        List<Stmt> out = new ArrayList<>();
        while (peek(Lexer.Kind.EOF) == null) {
            out.add(statement());
        }
        return out;
    }

    private Lexer.Token peek(Lexer.Kind kind) {
        Lexer.Token t = tokens.get(at);
        return t.kind() == kind ? t : null;
    }

    private Lexer.Token expect(Lexer.Kind kind, String text) {
        Lexer.Token t = tokens.get(at);
        if (t.kind() != kind || (text != null && !t.text().equals(text))) {
            throw new IllegalArgumentException("期望 " + (text == null ? kind : text) + " 实得 " + t + "，残词或语法错误");
        }
        at++;
        return t;
    }

    private boolean matchOp(String text) {
        if (peek(Lexer.Kind.OP) != null && tokens.get(at).text().equals(text)) {
            at++;
            return true;
        }
        return false;
    }

    private boolean matchKw(String text) {
        if (peek(Lexer.Kind.KEYWORD) != null && tokens.get(at).text().equals(text)) {
            at++;
            return true;
        }
        return false;
    }

    private List<Stmt> block() {
        expect(Lexer.Kind.OP, "{");
        List<Stmt> out = new ArrayList<>();
        while (peek(Lexer.Kind.OP) == null || !tokens.get(at).text().equals("}")) {
            if (peek(Lexer.Kind.EOF) != null) {
                throw new IllegalArgumentException("块未闭合");
            }
            out.add(statement());
        }
        expect(Lexer.Kind.OP, "}");
        return out;
    }

    private Stmt statement() {
        if (matchKw("if")) {
            Expr cond = expr();
            List<Stmt> then = block();
            List<Stmt> els = List.of();
            if (matchKw("else")) {
                els = block();
            }
            return new If(cond, then, els);
        }
        if (matchKw("while")) {
            return new While(expr(), block());
        }
        if (matchKw("break")) {
            semi();
            return new Break();
        }
        if (matchKw("continue")) {
            semi();
            return new Continue();
        }
        if (matchKw("return")) {
            Expr v = peek(Lexer.Kind.OP) != null && tokens.get(at).text().equals(";") ? null : expr();
            semi();
            return new Return(v);
        }
        if (matchKw("def")) {
            String name = expect(Lexer.Kind.IDENT, null).text();
            expect(Lexer.Kind.OP, "(");
            List<String> params = new ArrayList<>();
            if (!checkOp(")")) {
                params.add(expect(Lexer.Kind.IDENT, null).text());
                while (matchOp(",")) {
                    params.add(expect(Lexer.Kind.IDENT, null).text());
                }
            }
            expect(Lexer.Kind.OP, ")");
            return new Def(name, params, block());
        }
        if (matchKw("throw")) {
            Expr value = expr();
            semi();
            return new ThrowStmt(value);
        }
        if (matchKw("try")) {
            List<Stmt> body = block();
            if (matchKw("catch")) {
                expect(Lexer.Kind.OP, "(");
                String var = expect(Lexer.Kind.IDENT, null).text();
                expect(Lexer.Kind.OP, ")");
                return new TryStmt(body, var, block(), false);
            }
            if (matchKw("finally")) {
                return new TryStmt(body, null, block(), true);
            }
            throw new IllegalArgumentException("try 需 catch 或 finally");
        }
        // 赋值或表达式语句
        Lexer.Token t = tokens.get(at);
        if (t.kind() == Lexer.Kind.IDENT && tokens.get(at + 1).kind() == Lexer.Kind.OP
                && tokens.get(at + 1).text().equals("=")) {
            String name = expect(Lexer.Kind.IDENT, null).text();
            expect(Lexer.Kind.OP, "=");
            Expr value = expr();
            semi();
            return new Assign(name, value);
        }
        Expr e = expr();
        semi();
        return new ExprStmt(e);
    }

    private boolean checkOp(String text) {
        return peek(Lexer.Kind.OP) != null && tokens.get(at).text().equals(text);
    }

    private void semi() {
        if (matchOp(";")) {
            return;
        }
        if (checkOp("}")) {
            return;
        }
        throw new IllegalArgumentException("期望 ; 实得 " + tokens.get(at));
    }

    private Expr expr() {
        return orExpr();
    }

    private Expr orExpr() {
        Expr left = andExpr();
        while (matchOp("||")) {
            left = new Binary("||", left, andExpr());
        }
        return left;
    }

    private Expr andExpr() {
        Expr left = eqExpr();
        while (matchOp("&&")) {
            left = new Binary("&&", left, eqExpr());
        }
        return left;
    }

    private Expr eqExpr() {
        Expr left = relExpr();
        while (checkOp("==") || checkOp("!=")) {
            String op = tokens.get(at).text();
            at++;
            left = new Binary(op, left, relExpr());
        }
        return left;
    }

    private Expr relExpr() {
        Expr left = addExpr();
        while (checkOp("<") || checkOp("<=") || checkOp(">") || checkOp(">=")) {
            String op = tokens.get(at).text();
            at++;
            left = new Binary(op, left, addExpr());
        }
        return left;
    }

    private Expr addExpr() {
        Expr left = mulExpr();
        while (checkOp("+") || checkOp("-")) {
            String op = tokens.get(at).text();
            at++;
            left = new Binary(op, left, mulExpr());
        }
        return left;
    }

    private Expr mulExpr() {
        Expr left = unary();
        while (checkOp("*") || checkOp("/") || checkOp("%")) {
            String op = tokens.get(at).text();
            at++;
            left = new Binary(op, left, unary());
        }
        return left;
    }

    private Expr unary() {
        if (matchOp("!")) {
            return new Unary("!", unary());
        }
        if (matchOp("-")) {
            return new Unary("-", unary());
        }
        return primary();
    }

    private Expr primary() {
        Lexer.Token t = tokens.get(at);
        if (t.kind() == Lexer.Kind.INT) {
            at++;
            return new Num(t.num());
        }
        if (t.kind() == Lexer.Kind.STRING) {
            at++;
            return new Str(t.text());
        }
        if (t.kind() == Lexer.Kind.KEYWORD && (t.text().equals("true") || t.text().equals("false"))) {
            at++;
            return new Bool(t.text().equals("true"));
        }
        if (t.kind() == Lexer.Kind.IDENT) {
            at++;
            if (checkOp("(")) {
                at++;
                List<Expr> args = new ArrayList<>();
                if (!checkOp(")")) {
                    args.add(expr());
                    while (matchOp(",")) {
                        args.add(expr());
                    }
                }
                expect(Lexer.Kind.OP, ")");
                return new Call(t.text(), args);
            }
            return new Var(t.text());
        }
        if (matchOp("(")) {
            Expr inner = expr();
            expect(Lexer.Kind.OP, ")");
            return inner;
        }
        throw new IllegalArgumentException("意外记号 " + t);
    }
}
