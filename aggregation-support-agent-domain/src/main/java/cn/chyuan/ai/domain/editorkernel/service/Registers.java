package cn.chyuan.ai.domain.editorkernel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 寄存器与宏（工单 0710 CF3，neovim 思想）。
 * 命名寄存器与匿名寄存器/行级与字符级拷贝粘贴/
 * 宏录制与回放（count 次）/递归宏拒绝/寄存器覆盖与清点。
 */
public final class Registers {

    /** 拷贝类型：字符级/行级 */
    public enum Type {CHAR, LINE}

    public record Content(Type type, String text) {
    }

    private static final char ANONYMOUS = '"';

    private final Map<Character, Content> registers = new LinkedHashMap<>();

    /** 拷贝入寄存器（a-z 或匿名 "），覆盖式写入 */
    public void copy(char reg, String text, Type type) {
        checkReg(reg);
        registers.put(reg, new Content(type, text));
        if (reg != ANONYMOUS) {
            registers.put(ANONYMOUS, new Content(type, text));
        }
    }

    public Content paste(char reg) {
        checkReg(reg);
        Content content = registers.get(reg);
        if (content == null) {
            throw new IllegalStateException("空寄存器: " + reg);
        }
        return content;
    }

    public boolean has(char reg) {
        checkReg(reg);
        return registers.containsKey(reg);
    }

    public int size() {
        return registers.size();
    }

    // ---- 宏 ----

    private Character recording;
    private final List<String> recorded = new ArrayList<>();
    private final Set<Character> playing = new HashSet<>();

    public void startRecord(char reg) {
        checkReg(reg);
        if (recording != null) {
            throw new IllegalStateException("已在录制寄存器: " + recording);
        }
        recording = reg;
        recorded.clear();
    }

    public void stopRecord() {
        if (recording == null) {
            throw new IllegalStateException("未在录制");
        }
        registers.put(recording, new Content(Type.CHAR, String.join("\n", recorded)));
        recording = null;
    }

    /** 录制期间记一键序列 */
    public void recordKey(String key) {
        if (recording == null) {
            throw new IllegalStateException("未在录制");
        }
        recorded.add(key);
    }

    public boolean recording() {
        return recording != null;
    }

    /** 回放 count 次：@x 键递归展开子宏，自引用/嵌套同寄存器拒绝；返回扁平化键序列 */
    public List<String> playback(char reg, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("回放次数须 > 0");
        }
        if (playing.contains(reg)) {
            throw new IllegalStateException("递归宏拒绝: " + reg);
        }
        List<String> flat = new ArrayList<>();
        playing.add(reg);
        try {
            for (int i = 0; i < count; i++) {
                expand(reg, flat);
            }
        } finally {
            playing.remove(reg);
        }
        return flat;
    }

    private void expand(char reg, List<String> out) {
        Content content = paste(reg);
        String[] keys = content.text().isEmpty() ? new String[]{""} : content.text().split("\n", -1);
        for (String key : keys) {
            if (key.length() == 2 && key.charAt(0) == '@' && key.charAt(1) >= 'a' && key.charAt(1) <= 'z') {
                char nested = key.charAt(1);
                if (playing.contains(nested)) {
                    throw new IllegalStateException("递归宏拒绝: " + nested);
                }
                playing.add(nested);
                try {
                    expand(nested, out);
                } finally {
                    playing.remove(nested);
                }
            } else {
                out.add(key);
            }
        }
    }

    private static void checkReg(char reg) {
        if (reg != ANONYMOUS && !(reg >= 'a' && reg <= 'z')) {
            throw new IllegalArgumentException("非法寄存器名: " + reg);
        }
    }
}
