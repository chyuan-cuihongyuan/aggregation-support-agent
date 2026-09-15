package cn.chyuan.ai.domain.speech.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕导出 SRT/VTT（工单 0384 AU6）。
 * 段（起止时间戳+文本+可选说话人）→ SRT（序号+逗号毫秒时间码）/VTT（WEBVTT 头+点号毫秒+cue 设置）；
 * 时间码格式化（小时进位/毫秒三位）；SRT 解析回读往返一致。纯函数。
 */
public class SubtitleExporter {

    /** 字幕条目 */
    public record Cue(int index, long startMs, long endMs, String text, String speaker) {
    }

    /** SRT 序列化 */
    public String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            sb.append(i + 1).append('\n')
                    .append(timecodeSrt(cue.startMs())).append(" --> ").append(timecodeSrt(cue.endMs())).append('\n')
                    .append(speakerPrefix(cue.speaker())).append(cue.text()).append('\n');
            if (i < cues.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** VTT 序列化 */
    public String toVtt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            sb.append(i + 1).append('\n')
                    .append(timecodeVtt(cue.startMs())).append(" --> ").append(timecodeVtt(cue.endMs())).append('\n')
                    .append(speakerPrefix(cue.speaker())).append(cue.text()).append('\n');
            if (i < cues.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** SRT 解析回读（往返一致） */
    public List<Cue> parseSrt(String srt) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = srt.strip().split("\n\n");
        for (String block : blocks) {
            String[] lines = block.strip().split("\n");
            if (lines.length < 2) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(lines[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (!lines[1].contains("-->")) {
                continue;
            }
            String[] times = lines[1].split("-->");
            long start = parseSrtTimecode(times[0].trim());
            long end = parseSrtTimecode(times[1].trim());
            String text = lines.length > 2 ? lines[2].strip() : "";
            String speaker = null;
            if (text.startsWith("<v ")) {
                int close = text.indexOf('>');
                speaker = text.substring(3, close);
                text = text.substring(close + 1);
            }
            cues.add(new Cue(index, start, end, text, speaker));
        }
        return cues;
    }

    /** SRT 时间码 HH:MM:SS,mmm */
    private String timecodeSrt(long ms) {
        return String.format("%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** VTT 时间码 HH:MM:SS.mmm */
    private String timecodeVtt(long ms) {
        return String.format("%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private long parseSrtTimecode(String timecode) {
        String[] parts = timecode.replace(",", ".").split(":");
        long hours = Long.parseLong(parts[0]);
        long minutes = Long.parseLong(parts[1]);
        double seconds = Double.parseDouble(parts[2]);
        return hours * 3_600_000 + minutes * 60_000 + Math.round(seconds * 1000);
    }

    private String speakerPrefix(String speaker) {
        return speaker == null || speaker.isBlank() ? "" : "<v " + speaker + ">";
    }
}
