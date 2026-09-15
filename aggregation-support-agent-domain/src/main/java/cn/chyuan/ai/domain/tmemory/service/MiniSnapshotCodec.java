package cn.chyuan.ai.domain.tmemory.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 时序记忆快照迷你编解码（工单 0369 AS8）。
 * 快照 JSON 结构自描述（边数组字段固定），写出确定性（按 ingestSeq 排序、字段定序），
 * 解析为本快照专用平面结构（字符串/长整数/双精度/可空字段），往返等价。零依赖。
 */
public final class MiniSnapshotCodec {

    private MiniSnapshotCodec() {
    }

    /** 快照平面边结构 */
    public record SnapEdge(String edgeId, String subject, String predicate, String object,
                           long validFrom, Long validTo, String invalidReason, long ingestSeq,
                           double confidence, String source, String kind, int accessCount, double score) {
    }

    /** 快照整体：活跃边 + 归档边 + 元数据 */
    public record Snapshot(String name, long createdAtMs, List<SnapEdge> activeEdges, List<SnapEdge> archivedEdges) {
    }

    /** 序列化：字段定序 + 边按 ingestSeq 排序 → 同输入同输出 */
    public static String encode(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escape(snapshot.name()))
                .append("\",\"createdAtMs\":").append(snapshot.createdAtMs())
                .append(",\"activeEdges\":[").append(edges(snapshot.activeEdges()))
                .append("],\"archivedEdges\":[").append(edges(snapshot.archivedEdges()))
                .append("]}");
        return sb.toString();
    }

    private static String edges(List<SnapEdge> edges) {
        List<SnapEdge> sorted = new ArrayList<>(edges == null ? List.of() : edges);
        sorted.sort((a, b) -> Long.compare(a.ingestSeq(), b.ingestSeq()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            SnapEdge e = sorted.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"edgeId\":\"").append(escape(e.edgeId()))
                    .append("\",\"subject\":\"").append(escape(e.subject()))
                    .append("\",\"predicate\":\"").append(escape(e.predicate()))
                    .append("\",\"object\":\"").append(escape(e.object()))
                    .append("\",\"validFrom\":").append(e.validFrom())
                    .append(",\"validTo\":").append(e.validTo() == null ? "null" : e.validTo())
                    .append(",\"invalidReason\":").append(e.invalidReason() == null ? "null" : "\"" + escape(e.invalidReason()) + "\"")
                    .append(",\"ingestSeq\":").append(e.ingestSeq())
                    .append(",\"confidence\":").append(e.confidence())
                    .append(",\"source\":\"").append(escape(e.source()))
                    .append("\",\"kind\":\"").append(escape(e.kind()))
                    .append("\",\"accessCount\":").append(e.accessCount())
                    .append(",\"score\":").append(e.score())
                    .append('}');
        }
        return sb.toString();
    }

    /** 解析（本编解码自描述结构） */
    public static Snapshot decode(String json) {
        Parsed p = new Parsed(json);
        p.expect('{');
        String name = null;
        long createdAt = 0;
        List<SnapEdge> active = List.of();
        List<SnapEdge> archived = List.of();
        while (p.peek() != '}') {
            String key = p.readString();
            p.expect(':');
            switch (key) {
                case "name" -> name = p.readString();
                case "createdAtMs" -> createdAt = p.readLong();
                case "activeEdges" -> active = p.readEdgeArray();
                case "archivedEdges" -> archived = p.readEdgeArray();
                default -> throw new IllegalStateException("未知快照字段: " + key);
            }
            if (p.peek() == ',') {
                p.advance();
            }
        }
        return new Snapshot(name, createdAt, active, archived);
    }

    /** 极小 JSON 游标（对象/数组/字符串/数/null，按本编解码需要裁剪） */
    private static final class Parsed {
        private final String src;
        private int pos;

        Parsed(String src) {
            this.src = src;
        }

        char peek() {
            skipBlank();
            return src.charAt(pos);
        }

        void advance() {
            pos++;
        }

        void expect(char ch) {
            skipBlank();
            if (src.charAt(pos) != ch) {
                throw new IllegalStateException("期望 " + ch + " 于 " + pos);
            }
            pos++;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char ch = src.charAt(pos++);
                if (ch == '"') {
                    break;
                }
                if (ch == '\\') {
                    char esc = src.charAt(pos++);
                    sb.append(switch (esc) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> esc;
                    });
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        long readLong() {
            skipBlank();
            int start = pos;
            while (pos < src.length() && "+-0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            return Long.parseLong(src.substring(start, pos));
        }

        double readDouble() {
            skipBlank();
            int start = pos;
            while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        Long readNullableLong() {
            skipBlank();
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return readLong();
        }

        String readNullableString() {
            skipBlank();
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return readString();
        }

        List<SnapEdge> readEdgeArray() {
            expect('[');
            List<SnapEdge> out = new ArrayList<>();
            while (peek() != ']') {
                out.add(readEdge());
                if (peek() == ',') {
                    advance();
                }
            }
            expect(']');
            return out;
        }

        private SnapEdge readEdge() {
            expect('{');
            String edgeId = null, subject = null, predicate = null, object = null;
            long validFrom = 0, ingestSeq = 0;
            Long validTo = null;
            String invalidReason = null;
            double confidence = 0, score = 0;
            String source = null, kind = null;
            int accessCount = 0;
            while (peek() != '}') {
                String key = readString();
                expect(':');
                switch (key) {
                    case "edgeId" -> edgeId = readString();
                    case "subject" -> subject = readString();
                    case "predicate" -> predicate = readString();
                    case "object" -> object = readString();
                    case "validFrom" -> validFrom = readLong();
                    case "validTo" -> validTo = readNullableLong();
                    case "invalidReason" -> invalidReason = readNullableString();
                    case "ingestSeq" -> ingestSeq = readLong();
                    case "confidence" -> confidence = readDouble();
                    case "source" -> source = readString();
                    case "kind" -> kind = readString();
                    case "accessCount" -> accessCount = (int) readLong();
                    case "score" -> score = readDouble();
                    default -> throw new IllegalStateException("未知边字段: " + key);
                }
                if (peek() == ',') {
                    advance();
                }
            }
            expect('}');
            return new SnapEdge(edgeId, subject, predicate, object, validFrom, validTo,
                    invalidReason, ingestSeq, confidence, source, kind, accessCount, score);
        }

        private void skipBlank() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
