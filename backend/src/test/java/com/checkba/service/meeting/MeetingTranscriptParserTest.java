package com.checkba.service.meeting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 听悟结果解析：正常结构、缺字段、坏 JSON 三路都要稳。 */
class MeetingTranscriptParserTest {

    private static final String TRANSCRIPTION = """
            {"TaskId":"t1","Transcription":{"Paragraphs":[
              {"ParagraphId":"p1","SpeakerId":"1","Words":[
                {"Start":1000,"End":1500,"Text":"各位"},
                {"Start":1500,"End":2200,"Text":"下午好"}]},
              {"ParagraphId":"p2","SpeakerId":"2","Words":[
                {"Start":3000,"End":4200,"Text":"我们先过一下合同条款"}]},
              {"ParagraphId":"p3","SpeakerId":"1","Words":[]}
            ]}}
            """;

    @Test
    @DisplayName("段落解析：拼词、取起止毫秒、保留说话人编号，空段落跳过")
    void parseSegments() {
        List<MeetingTranscriptParser.Segment> segments = MeetingTranscriptParser.parseSegments(TRANSCRIPTION);
        assertEquals(2, segments.size(), "空 Words 的段落应被跳过");
        assertEquals("1", segments.get(0).speaker());
        assertEquals(1000, segments.get(0).start());
        assertEquals(2200, segments.get(0).end());
        assertEquals("各位下午好", segments.get(0).text());
        assertEquals("2", segments.get(1).speaker());
        assertEquals("我们先过一下合同条款", segments.get(1).text());
    }

    @Test
    @DisplayName("落库 JSON 往返：segmentsToJson 的结构可被面板/工具直接消费")
    void segmentsRoundTrip() {
        String json = MeetingTranscriptParser.segmentsToJson(
                MeetingTranscriptParser.parseSegments(TRANSCRIPTION));
        assertTrue(json.contains("\"speaker\":\"1\""));
        assertTrue(json.contains("\"text\":\"各位下午好\""));
        assertTrue(json.contains("\"start\":1000"));
    }

    @Test
    @DisplayName("坏 JSON / null / 空串 一律返回空列表而不是抛错")
    void parseSegmentsTolerant() {
        assertTrue(MeetingTranscriptParser.parseSegments(null).isEmpty());
        assertTrue(MeetingTranscriptParser.parseSegments("").isEmpty());
        assertTrue(MeetingTranscriptParser.parseSegments("not json").isEmpty());
        assertTrue(MeetingTranscriptParser.parseSegments("{\"Transcription\":{}}").isEmpty());
    }

    @Test
    @DisplayName("增值结果聚合：章节/摘要/问答/待办/关键词各取所长")
    void buildSummaryJson() {
        String chapters = """
                {"AutoChapters":[{"Headline":"合同条款","Summary":"讨论了付款节奏"}]}
                """;
        String summarization = """
                {"Summarization":{"ParagraphSummary":"双方就合同主要条款交换意见。",
                  "QuestionsAnsweringSummary":[{"Question":"付款期限？","Answer":"30日内"}]}}
                """;
        String assistance = """
                {"MeetingAssistance":{"Actions":[{"Text":"下周提供修订稿"}],
                  "Keywords":["合同","付款"]}}
                """;
        String out = MeetingTranscriptParser.buildSummaryJson(chapters, summarization, assistance);
        assertTrue(out.contains("合同条款"));
        assertTrue(out.contains("双方就合同主要条款交换意见"));
        assertTrue(out.contains("30日内"));
        assertTrue(out.contains("下周提供修订稿"));
        assertTrue(out.contains("付款"));
    }

    @Test
    @DisplayName("增值结果全缺时返回 null（面板据此隐藏摘要区）")
    void buildSummaryJsonAllMissing() {
        assertNull(MeetingTranscriptParser.buildSummaryJson(null, null, null));
        assertNull(MeetingTranscriptParser.buildSummaryJson("bad", "", null));
    }

    @Test
    @DisplayName("本机 asr-service 结果：秒转毫秒、speaker 恒为 1（本地档没有说话人分离）")
    void parseLocalSegments() {
        List<MeetingTranscriptParser.Segment> segments = MeetingTranscriptParser.parseLocalSegments("""
                {"language":"zh","duration":8.4,"text":"合并全文",
                 "segments":[{"id":0,"start":0.0,"end":4.25,"text":" 各位下午好 "},
                             {"id":1,"start":4.25,"end":8.4,"text":"我们先过一下合同条款"},
                             {"id":2,"start":8.4,"end":8.4,"text":"   "}]}""");

        assertEquals(2, segments.size(), "纯空白的段落应被跳过");
        assertEquals("1", segments.get(0).speaker());
        assertEquals(0, segments.get(0).start());
        assertEquals(4250, segments.get(0).end(), "0.  秒要按四舍五入转毫秒");
        assertEquals("各位下午好", segments.get(0).text(), "前后空白要去掉");
        assertEquals(8400, segments.get(1).end());
    }

    @Test
    @DisplayName("本机结果缺 segments 时退化成一整段，不把整份转写丢掉")
    void parseLocalSegmentsFallsBackToWholeText() {
        List<MeetingTranscriptParser.Segment> segments =
                MeetingTranscriptParser.parseLocalSegments("{\"text\":\"整段转写\",\"duration\":12.0}");

        assertEquals(1, segments.size());
        assertEquals("整段转写", segments.get(0).text());
        assertEquals(12000, segments.get(0).end());

        assertTrue(MeetingTranscriptParser.parseLocalSegments("坏 JSON").isEmpty());
        assertTrue(MeetingTranscriptParser.parseLocalSegments("{\"text\":\"\"}").isEmpty(),
                "空正文不能冒充成功");
    }
}
