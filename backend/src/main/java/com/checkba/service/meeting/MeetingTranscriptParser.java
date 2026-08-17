package com.checkba.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 听悟结果 JSON → 本产品的紧凑结构。纯函数，可单测。
 *
 * <p>解析刻意宽松：听悟的结果文件字段随版本演进（如 Summarization 的键名有过变体），
 * 抽不到的字段一律跳过而不是抛错——转写正文是主产物，增值结果缺了不影响主流程。
 */
public final class MeetingTranscriptParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MeetingTranscriptParser() {
    }

    /** 转写段落。speaker 是听悟说话人编号（字符串），start/end 为毫秒。 */
    public record Segment(String speaker, long start, long end, String text) {
    }

    /**
     * 解析听悟 Transcription 结果：Transcription.Paragraphs[].{SpeakerId, Words[].{Start,End,Text}}。
     * 相邻同说话人段落不合并——听悟的分段本身就带语义停顿，保留它对纪要引用更友好。
     */
    public static List<Segment> parseSegments(String transcriptionJson) {
        List<Segment> segments = new ArrayList<>();
        JsonNode root = readTree(transcriptionJson);
        if (root == null) return segments;
        JsonNode paragraphs = root.path("Transcription").path("Paragraphs");
        if (!paragraphs.isArray()) return segments;
        for (JsonNode p : paragraphs) {
            String speaker = p.path("SpeakerId").asText("");
            if (speaker.isEmpty()) speaker = "1";
            JsonNode words = p.path("Words");
            if (!words.isArray() || words.isEmpty()) continue;
            StringBuilder text = new StringBuilder();
            long start = Long.MAX_VALUE;
            long end = 0;
            for (JsonNode w : words) {
                text.append(w.path("Text").asText(""));
                long ws = w.path("Start").asLong(-1);
                long we = w.path("End").asLong(-1);
                if (ws >= 0) start = Math.min(start, ws);
                if (we >= 0) end = Math.max(end, we);
            }
            String t = text.toString().trim();
            if (t.isEmpty()) continue;
            segments.add(new Segment(speaker, start == Long.MAX_VALUE ? 0 : start, end, t));
        }
        return segments;
    }

    /**
     * 解析本机 asr-service 的 OpenAI 兼容响应：{@code {"segments":[{start,end,text}]}}，
     * 时间戳是<b>秒（浮点）</b>，转成与听悟一致的毫秒。
     *
     * <p><b>speaker 恒为 "1"</b>：faster-whisper 没有说话人分离，本地档就是没有这个能力
     * （引 pyannote 要 HF token + 许可协议 + 额外几百 MB 模型，与零配置冲突）。
     * 界面上必须写明这条取舍，不能让用户以为两档等价。
     *
     * <p>没有 segments 字段但有 text 时退化成一整段——上游换了形态也不至于把整份转写丢掉。
     */
    public static List<Segment> parseLocalSegments(String json) {
        List<Segment> segments = new ArrayList<>();
        JsonNode root = readTree(json);
        if (root == null) return segments;
        JsonNode arr = root.path("segments");
        if (arr.isArray()) {
            for (JsonNode s : arr) {
                String text = s.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                segments.add(new Segment("1",
                        Math.round(s.path("start").asDouble(0) * 1000),
                        Math.round(s.path("end").asDouble(0) * 1000),
                        text));
            }
            if (!segments.isEmpty()) return segments;
        }
        String whole = root.path("text").asText("").trim();
        if (!whole.isEmpty()) {
            segments.add(new Segment("1", 0, Math.round(root.path("duration").asDouble(0) * 1000), whole));
        }
        return segments;
    }

    /** 段落列表 → 落库 JSON（[{"speaker","start","end","text"}]）。 */
    public static String segmentsToJson(List<Segment> segments) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Segment s : segments) {
            ObjectNode o = arr.addObject();
            o.put("speaker", s.speaker());
            o.put("start", s.start());
            o.put("end", s.end());
            o.put("text", s.text());
        }
        return arr.toString();
    }

    /**
     * 三份增值结果 → 紧凑 summaryJson：
     * {"chapters":[{"title","summary"}],"summary":"...","qa":[{"question","answer"}],
     *  "todos":["..."],"keywords":["..."]}
     * 任一入参为 null/不可解析则对应字段缺省。
     */
    public static String buildSummaryJson(String autoChaptersJson, String summarizationJson,
                                          String meetingAssistanceJson) {
        ObjectNode out = MAPPER.createObjectNode();

        JsonNode chaptersRoot = readTree(autoChaptersJson);
        if (chaptersRoot != null) {
            JsonNode chapters = chaptersRoot.path("AutoChapters");
            if (chapters.isArray() && !chapters.isEmpty()) {
                ArrayNode arr = out.putArray("chapters");
                for (JsonNode c : chapters) {
                    ObjectNode o = arr.addObject();
                    o.put("title", c.path("Headline").asText(c.path("Title").asText("")));
                    o.put("summary", c.path("Summary").asText(""));
                }
            }
        }

        JsonNode summarizationRoot = readTree(summarizationJson);
        if (summarizationRoot != null) {
            JsonNode s = summarizationRoot.path("Summarization");
            String paragraph = firstText(s, "ParagraphSummary", "Paragraph");
            if (!paragraph.isEmpty()) out.put("summary", paragraph);
            JsonNode qa = firstNode(s, "QuestionsAnsweringSummary", "QuestionsAnswering");
            if (qa != null && qa.isArray() && !qa.isEmpty()) {
                ArrayNode arr = out.putArray("qa");
                for (JsonNode item : qa) {
                    ObjectNode o = arr.addObject();
                    o.put("question", item.path("Question").asText(""));
                    o.put("answer", item.path("Answer").asText(""));
                }
            }
        }

        JsonNode assistanceRoot = readTree(meetingAssistanceJson);
        if (assistanceRoot != null) {
            JsonNode ma = assistanceRoot.path("MeetingAssistance");
            List<String> todos = collectTexts(firstNode(ma, "Actions"));
            if (!todos.isEmpty()) {
                ArrayNode arr = out.putArray("todos");
                todos.forEach(arr::add);
            }
            List<String> keywords = collectTexts(firstNode(ma, "Keywords", "KeyInformation"));
            if (!keywords.isEmpty()) {
                ArrayNode arr = out.putArray("keywords");
                keywords.forEach(arr::add);
            }
        }

        return out.isEmpty() ? null : out.toString();
    }

    /** 数组元素可能是纯字符串，也可能是 {"Text"/"Content"/"KeySentence": "..."} 对象——都收。 */
    private static List<String> collectTexts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode item : arr) {
            String v;
            if (item.isTextual()) {
                v = item.asText("");
            } else {
                v = firstText(item, "Text", "Content", "KeySentence", "Keyword");
            }
            if (!v.isBlank()) out.add(v.trim());
        }
        return out;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null) return "";
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText().trim();
        }
        return "";
    }

    private static JsonNode firstNode(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (!v.isMissingNode() && !v.isNull()) return v;
        }
        return null;
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
