package cn.chyuan.ai.domain.vcskernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * tree 层级快照（工单 0611 BU2，git tree 思想）。
 * 路径条目（name/oid/类型 blob·tree）/条目按名排序保证摘要稳定/
 * Merkle 根摘要逐级哈希/未变子树 oid 共享（只存引用）/
 * 空树与重名条目拒绝/扁平路径 ↔ 层级树互转。
 */
public final class TreeObject {

    /** 桶条目：名称+对象 oid+类型 */
    public record Entry(String name, String oid, String type) {

        public Entry {
            if (name == null || name.isBlank() || name.contains("/")) {
                throw new IllegalArgumentException("条目名非法：" + name);
            }
            if (!TYPE_BLOB.equals(type) && !TYPE_TREE.equals(type)) {
                throw new IllegalArgumentException("条目类型非法：" + type);
            }
        }
    }

    public static final String TYPE_TREE = "tree";
    public static final String TYPE_BLOB = "blob";

    private final Map<String, List<Entry>> trees = new LinkedHashMap<>();

    /** 单层树序列化摘要（条目按名排序，摘要稳定） */
    public static String oid(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("空树拒绝");
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(java.util.Comparator.comparing(Entry::name));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(sorted.get(i - 1).name())) {
                throw new IllegalArgumentException("重名条目拒绝：" + sorted.get(i).name());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : sorted) {
            sb.append(e.type()).append(' ').append(e.name()).append('\0').append(e.oid()).append('\n');
        }
        return TYPE_TREE + ":" + BlobStore.hex(
                BlobStore.digest((TYPE_TREE + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** 由扁平文件 map（路径→blob oid）构建层级树，返回根 oid（未变子树共享引用） */
    public synchronized String put(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("快照至少一个文件");
        }
        Node root = new Node();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            if (path == null || path.isBlank() || path.startsWith("/") || path.endsWith("/")) {
                throw new IllegalArgumentException("路径非法：" + path);
            }
            String[] segments = path.split("/");
            Node cur = root;
            for (int i = 0; i < segments.length - 1; i++) {
                if (segments[i].isBlank()) {
                    throw new IllegalArgumentException("路径非法：" + path);
                }
                cur = cur.children.computeIfAbsent(segments[i], k -> new Node());
            }
            String leaf = segments[segments.length - 1];
            Node existing = cur.children.putIfAbsent(leaf, leafNode(e.getValue()));
            if (existing != null) {
                throw new IllegalArgumentException("重名路径拒绝：" + path);
            }
        }
        return buildDir(root);
    }

    private static final class Node {
        final TreeMap<String, Node> children = new TreeMap<>();
        String blobOid;
    }

    private static Node leafNode(String blobOid) {
        Node node = new Node();
        node.blobOid = blobOid;
        return node;
    }

    private String buildDir(Node node) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Node> child : node.children.entrySet()) {
            Node value = child.getValue();
            if (value.blobOid != null && !value.children.isEmpty()) {
                throw new IllegalArgumentException("文件与目录同名冲突：" + child.getKey());
            }
            if (value.blobOid != null) {
                entries.add(new Entry(child.getKey(), value.blobOid, TYPE_BLOB));
            } else {
                entries.add(new Entry(child.getKey(), buildDir(value), TYPE_TREE));
            }
        }
        String treeOid = oid(entries);
        trees.put(treeOid, entries);
        return treeOid;
    }

    /** 扁平化：根 oid → 路径→blob oid（含子树内容） */
    public synchronized Map<String, String> files(String rootOid) {
        Map<String, String> out = new TreeMap<>();
        collect(rootOid, "", out);
        return out;
    }

    private void collect(String treeOid, String prefix, Map<String, String> out) {
        List<Entry> entries = trees.get(treeOid);
        if (entries == null) {
            throw new IllegalArgumentException("树对象缺失：" + treeOid);
        }
        for (Entry e : entries) {
            String path = prefix.isEmpty() ? e.name() : prefix + "/" + e.name();
            if (TYPE_BLOB.equals(e.type())) {
                out.put(path, e.oid());
            } else {
                collect(e.oid(), path, out);
            }
        }
    }

    /** 树对象数（子树共享计数口径） */
    public synchronized int treeCount() {
        return trees.size();
    }
}
