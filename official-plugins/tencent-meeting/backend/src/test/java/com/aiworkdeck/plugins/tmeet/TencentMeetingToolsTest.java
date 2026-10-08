// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.aiworkdeck.plugins.tmeet;

import com.checkba.plugin.api.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class TencentMeetingToolsTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    FakeCli cli; FakeHost host; TencentMeetingTools plugin;
    @BeforeEach void setup() {
        cli = new FakeCli(); host = new FakeHost(); plugin = new TencentMeetingTools(cli, directory); plugin.setHost(host);
    }
    JsonNode call(String action, String args) throws Exception { return JSON.readTree(plugin.tencent_meeting_action(action, args)); }
    JsonNode finished(String action, String args) throws Exception {
        JsonNode pending = call(action, args); assertTrue(pending.path("success").asBoolean(), pending.toString());
        assertTrue(pending.path("data").path("pending").asBoolean());
        return call(action, "{\"jobId\":\"" + pending.path("data").path("jobId").asText() + "\"}");
    }
    String key() throws Exception { return call("list", "{}").path("data").path("meetings").get(0).path("key").asText(); }
    static String response(String data) { return "{\"message\":\"success\",\"data\":" + data + "}"; }
    static String meeting(String permission) {
        return "{\"meeting_id\":\"internal-id-secret\",\"meeting_code\":\"123-456-789\",\"subject\":\"测试会议\",\"start_time\":\"2026-10-01T12:00:00+08:00\",\"records\":[{\"record_file_id\":\"private-record\",\"permission_status\":\"" + permission + "\"}]}";
    }
    static TmeetCli.Result result(String data) { return new TmeetCli.Result(0, response(data)); }
    void dataFixture() {
        cli.answer = args -> {
            if (args.contains("list-ended")) return result("{\"meeting_info_list\":[" + meeting("can_view") + "]}");
            if (args.get(1).equals("list")) return result("{\"record_meetings\":[]}");
            if (args.contains("transcript-get")) return result("{\"minutes\":{\"paragraphs\":[{\"pid\":\"10\",\"start_time\":\"00:01\",\"speaker\":{\"user_name\":\"讲者\",\"user_id\":\"hidden-user\"},\"sentences\":[{\"words\":[{\"text\":\"你好\"}]}]}]},\"more\":false}");
            if (args.contains("smart-minutes")) return result("{\"meeting_minute\":{\"minute\":\"摘要正文\",\"todos\":[{\"content\":\"跟进事项\",\"executor_list\":[{\"nick\":\"讲者\",\"user_id\":\"hidden-user\"}]}]}}");
            throw new AssertionError(args);
        };
    }
    @Test void rejectsMissingIdentityInvalidArgumentsAndUnknownAction() throws Exception {
        host.context = new ToolCall(9L, null, null, null);
        assertFalse(call("status", "{}").path("success").asBoolean()); assertTrue(cli.calls.isEmpty());
        host.context = new ToolCall(9L, null, 1L, null);
        assertFalse(call("list", "[]").path("success").asBoolean());
        assertFalse(call("erase", "{}").path("success").asBoolean());
        host.allowProject = false;
        assertFalse(call("list", "{}").path("success").asBoolean());
    }
    @Test void statusSanitizesCredentialsAndMissingCliIsUnderstandable() throws Exception {
        cli.answer = args -> new TmeetCli.Result(0, args.contains("--version") ? "1.0" : "Logged in\nUserName: 测试用户\nOpenId: secret-id\nAccessToken: secret-token\nRefreshToken: secret-refresh");
        String output = call("status", "{}").toString();
        assertTrue(output.contains("测试用户")); assertFalse(output.contains("secret")); assertFalse(output.contains("Token"));
        cli.answer = args -> { throw new TmeetCli.Failure(TmeetCli.MISSING); };
        JsonNode status = call("status", "{}").path("data");
        assertFalse(status.path("cliAvailable").asBoolean()); assertTrue(status.path("message").asText().contains("安装"));
    }
    @Test void loginValidatesOfficialUrlAndLogoutRequiresConfirmation() throws Exception {
        assertTrue(call("login", "{}").path("success").asBoolean());
        cli.url = "https://meeting.tencent.com.attacker.example/auth";
        assertFalse(call("login", "{}").path("success").asBoolean());
        assertFalse(call("logout", "{}").path("success").asBoolean()); assertTrue(cli.calls.isEmpty());
        assertTrue(call("logout", "{\"confirmed\":true}").path("success").asBoolean()); assertTrue(cli.cancelled);
        assertFalse(TmeetCli.validAuthorizeUrl("javascript:alert(1)"));
        assertFalse(TmeetCli.validAuthorizeUrl("https://evil@meeting.tencent.com/a"));
    }
    @Test void configPersistsPerUserAndProjectWithoutTrustingCallerIds() throws Exception {
        assertEquals(30, call("config", "{}").path("data").path("lookbackDays").asInt());
        assertTrue(call("config", "{\"lookbackDays\":7,\"excludeKeywords\":[\"秘密\"],\"userId\":99,\"projectId\":999}").path("success").asBoolean());
        TencentMeetingTools reloaded = new TencentMeetingTools(cli, directory); reloaded.setHost(host);
        assertEquals(7, JSON.readTree(reloaded.tencent_meeting_action("config", "{}")).path("data").path("lookbackDays").asInt());
        host.context = new ToolCall(9L, null, 2L, null);
        assertEquals(30, call("config", "{}").path("data").path("lookbackDays").asInt());
        host.context = new ToolCall(10L, null, 1L, null);
        assertEquals(30, call("config", "{}").path("data").path("lookbackDays").asInt());
        assertFalse(call("config", "{\"lookbackDays\":0}").path("success").asBoolean());
        assertFalse(call("config", "{\"excludeKeywords\":[1]}").path("success").asBoolean());
    }
    @Test void syncPaginatesBothSourcesAndDoesNotFetchAnyTranscript() throws Exception {
        cli.answer = args -> {
            if (args.contains("list-ended")) return result(args.contains("--page-token") ? "{\"meeting_info_list\":[]}" : "{\"meeting_info_list\":[" + meeting("can_view") + "],\"next_page_token\":\"opaque-next\"}");
            if (args.get(1).equals("list")) return result(args.contains("--page-token") ? "{\"record_meetings\":[]}" : "{\"record_meetings\":[{\"meeting_id\":\"another-hidden\",\"meeting_code\":\"987\",\"subject\":\"录制\",\"record_files\":[{\"record_file_id\":\"another-file\",\"record_start_time\":\"2026-10-01\"}]}],\"next_page_token\":\"record-next\"}");
            throw new AssertionError(args);
        };
        JsonNode output = finished("sync", "{}").path("data");
        assertEquals(2, output.path("count").asInt()); assertTrue(output.path("complete").asBoolean()); assertEquals(4, cli.calls.size());
        assertTrue(cli.calls.get(1).contains("opaque-next")); assertTrue(cli.calls.get(3).contains("record-next"));
        String list = call("list", "{}").toString(); assertFalse(list.contains("hidden")); assertFalse(list.contains("internal-id")); assertFalse(list.contains("record_file"));
    }
    @Test void syncReportsFailureAndRepeatedCursorInsteadOfClaimingSuccess() throws Exception {
        cli.answer = args -> args.contains("list-ended") ? result("{\"meeting_info_list\":[],\"next_page_token\":\"repeat\"}") : new TmeetCli.Result(7, "SECRET raw failure");
        JsonNode data = finished("sync", "{}").path("data");
        assertFalse(data.path("complete").asBoolean()); assertEquals(2, data.path("errors").size()); assertFalse(data.toString().contains("SECRET"));
    }
    @Test void malformedProtocolAndTimeoutRemainVisibleAndSanitized() throws Exception {
        cli.answer = args -> { if (args.contains("list-ended")) return new TmeetCli.Result(0, "SECRET bad json"); throw new TmeetCli.Failure("腾讯会议请求超时，请稍后重试"); };
        JsonNode data = finished("sync", "{}").path("data");
        assertFalse(data.path("complete").asBoolean()); assertTrue(data.path("errors").toString().contains("超时")); assertFalse(data.toString().contains("SECRET"));
    }
    @Test void noPermissionNeverReadsTranscriptAndForeignKeysFail() throws Exception {
        cli.answer = args -> args.contains("list-ended") ? result("{\"meeting_info_list\":[" + meeting("can_apply") + "]}") : result("{\"record_meetings\":[]}");
        finished("sync", "{}");
        assertFalse(finished("detail", "{\"key\":\"" + key() + "\"}").path("success").asBoolean()); assertEquals(2, cli.calls.size());
        assertFalse(call("detail", "{\"key\":\"../../state.json\"}").path("success").asBoolean());
    }
    @Test void crossUserProjectAndJobLookupAreRejected() throws Exception {
        dataFixture(); finished("sync", "{}"); String key = key();
        JsonNode job = call("detail", "{\"key\":\"" + key + "\"}");
        host.context = new ToolCall(9L, null, 2L, null);
        assertFalse(call("detail", "{\"jobId\":\"" + job.path("data").path("jobId").asText() + "\"}").path("success").asBoolean());
        assertFalse(finished("detail", "{\"key\":\"" + key + "\"}").path("success").asBoolean());
        host.context = new ToolCall(10L, null, 1L, null);
        assertFalse(finished("detail", "{\"key\":\"" + key + "\"}").path("success").asBoolean());
    }
    @Test void detailReadsPagesAndReturnsOnlyPublicFields() throws Exception {
        dataFixture(); Function<List<String>, TmeetCli.Result> original = cli.answer;
        cli.answer = args -> {
            TmeetCli.Result value = original.apply(args);
            if (args.contains("transcript-get") && !args.contains("--pid")) return new TmeetCli.Result(0, value.output().replace("\"more\":false", "\"more\":true"));
            return value;
        };
        finished("sync", "{}"); JsonNode data = finished("detail", "{\"key\":\"" + key() + "\"}").path("data");
        assertEquals(2, data.path("paragraphs").size()); assertEquals("摘要正文", data.path("smartMinutes").asText());
        assertEquals("跟进事项", data.path("todos").get(0).path("content").asText());
        assertTrue(cli.calls.stream().anyMatch(c -> c.contains("--pid") && c.contains("11")));
        assertFalse(data.toString().contains("hidden-user")); assertFalse(data.toString().contains("internal-id"));
    }
    @Test void exportWritesUtf8MarkdownThroughHostAndRefusesPartialRead() throws Exception {
        dataFixture(); finished("sync", "{}"); String key = key();
        JsonNode exported = finished("export", "{\"key\":\"" + key + "\"}");
        assertTrue(exported.path("success").asBoolean()); assertEquals(99, exported.path("data").path("fileId").asInt());
        assertTrue(host.written.contains("你好")); assertTrue(host.written.contains("摘要正文")); assertFalse(host.written.contains("internal-id")); assertEquals(9, host.writeProject);
        Function<List<String>, TmeetCli.Result> original = cli.answer;
        cli.answer = args -> args.contains("smart-minutes") ? new TmeetCli.Result(1, "SECRET") : original.apply(args);
        host.written = null;
        assertFalse(finished("export", "{\"key\":\"" + key + "\"}").path("success").asBoolean()); assertNull(host.written);
    }
    @Test void missingRecordFilesUseMeetingLevelPermissionCheckedFallback() throws Exception {
        cli.answer = args -> {
            if (args.contains("list-ended")) return result("{\"meeting_info_list\":[]}");
            if (args.get(1).equals("list")) return result("{\"record_meetings\":[{\"meeting_id\":\"internal-id-secret\",\"subject\":\"测试会议\"}]}");
            if (args.contains("get")) return result("{\"meeting_info_list\":[" + meeting("can_apply") + "]}");
            throw new AssertionError(args);
        };
        assertTrue(finished("sync", "{}").path("data").path("complete").asBoolean());
        assertFalse(finished("detail", "{\"key\":\"" + key() + "\"}").path("success").asBoolean());
        assertEquals(3, cli.calls.size());
    }
    @Test void completeSyncRefreshesPermissionsKeepsKeysAndAppliesExclusions() throws Exception {
        dataFixture(); finished("sync", "{}"); String key = key();
        cli.answer = args -> args.contains("list-ended") ? result("{\"meeting_info_list\":[" + meeting("closed") + "]}") : result("{\"record_meetings\":[]}");
        finished("sync", "{}"); assertEquals(key, key());
        assertFalse(finished("detail", "{\"key\":\"" + key + "\"}").path("success").asBoolean());
        call("config", "{\"excludeKeywords\":[\"测试\"]}"); finished("sync", "{}");
        assertEquals(0, call("list", "{}").path("data").path("meetings").size());
    }
    @Test void failedSyncPreservesPreviousCompleteSnapshot() throws Exception {
        dataFixture(); finished("sync", "{}"); String key = key();
        cli.answer = args -> new TmeetCli.Result(1, "secret");
        assertFalse(finished("sync", "{}").path("data").path("complete").asBoolean()); assertEquals(key, key());
    }
    @Test void configAcceptsNinetyDaysAndRejectsLongerWindow() throws Exception {
        assertTrue(call("config", "{\"lookbackDays\":90}").path("success").asBoolean());
        assertFalse(call("config", "{\"lookbackDays\":91}").path("success").asBoolean());
        assertFalse(call("config", "{\"lookbackDays\":365}").path("success").asBoolean());
        assertEquals(90, call("config", "{}").path("data").path("lookbackDays").asInt());
    }
    @Test void hasMoreWithoutTokenPreservesPreviousSnapshotAndReportsIncomplete() throws Exception {
        dataFixture(); finished("sync", "{}"); String key = key();
        cli.answer = args -> args.contains("list-ended") ? result("{\"meeting_info_list\":[],\"has_more\":true}") : result("{\"record_meetings\":[]}");
        JsonNode data = finished("sync", "{}").path("data");
        assertFalse(data.path("complete").asBoolean()); assertEquals(1, data.path("errors").size());
        assertTrue(data.path("errors").get(0).asText().contains("分页标识")); assertEquals(key, key());
    }
    @Test void recordingRowStateBlocksPendingFilesEvenWhenEndedSourceListedThem() throws Exception {
        for (String state : List.of("录制中，请等待", "转码中，请稍后重试", "转码完成，可根据录制文件权限进行下一步")) {
            cli.calls.clear();
            cli.answer = args -> {
                if (args.contains("list-ended")) return result("{\"meeting_info_list\":[" + meeting("can_view") + "]}");
                if (args.get(1).equals("list")) return result("{\"record_meetings\":[{\"meeting_id\":\"internal-id-secret\",\"subject\":\"测试会议\",\"state\":\"" + state + "\",\"record_files\":[{\"record_file_id\":\"private-record\"}]}]}");
                if (args.contains("transcript-get")) return result("{\"minutes\":{\"paragraphs\":[]},\"more\":false}");
                if (args.contains("smart-minutes")) return result("{\"meeting_minute\":{\"minute\":\"\"}}");
                throw new AssertionError(args);
            };
            assertTrue(finished("sync", "{}").path("data").path("complete").asBoolean());
            JsonNode detail = finished("detail", "{\"key\":\"" + key() + "\"}");
            if (state.contains("完成")) { assertTrue(detail.path("success").asBoolean()); assertEquals(4, cli.calls.size()); }
            else { assertFalse(detail.path("success").asBoolean()); assertEquals(2, cli.calls.size()); }
        }
    }
    static class FakeCli implements TmeetCli.Runner {
        List<List<String>> calls = new ArrayList<>(); boolean cancelled; String url = "https://meeting.tencent.com/authorize?code=fixture";
        Function<List<String>, TmeetCli.Result> answer = args -> new TmeetCli.Result(0, "ok");
        public TmeetCli.Result run(List<String> args, Duration timeout) { calls.add(List.copyOf(args)); return answer.apply(args); }
        public String login(long userId) { return url; }
        public void cancelLogin(long userId) { cancelled = true; }
    }
    static class FakeHost implements PluginHost {
        ToolCall context = new ToolCall(9L, null, 1L, null); boolean allowProject = true; String written; long writeProject;
        Map<String, JobStatus> statuses = new HashMap<>();
        public String pluginId() { return "tencent-meeting"; }
        public ToolCall call() { return context; }
        public com.checkba.plugin.api.Files files() {
            return (com.checkba.plugin.api.Files) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{com.checkba.plugin.api.Files.class}, (p, method, args) -> {
                if (!allowProject) throw new IllegalArgumentException("no access");
                return switch (method.getName()) {
                    case "list" -> List.of();
                    case "createFolderPath" -> new FileInfo(98, "腾讯会议", null, true, "", 0, "腾讯会议", null, null, null);
                    case "write" -> { writeProject = (Long) args[0]; written = new String(((InputStream) args[3]).readAllBytes(), StandardCharsets.UTF_8); yield new FileInfo(99, (String) args[2], 98L, false, "md", 1, "腾讯会议/" + args[2], null, null, null); }
                    default -> throw new AssertionError(method);
                };
            });
        }
        public Jobs jobs() { return new Jobs() {
            public JobHandle start(String kind, String title, JobBody body) {
                String id = UUID.randomUUID().toString(); ToolCall captured = context;
                JobContext ctx = new JobContext() {
                    public void progress(long done, long total, String message) {}
                    public void checkCancelled() {}
                    public ToolCall call() { return captured; }
                    public void result(String result) { statuses.put(id, new JobStatus(id, kind, title, "done", 1, 1, "", result, null)); }
                };
                try { body.run(ctx); } catch (Exception e) { throw new RuntimeException(e); }
                return new JobHandle(id);
            }
            public JobStatus status(String id) { return statuses.get(id); }
            public void cancel(String id) {}
        }; }
        public Text text() { return null; } public com.checkba.plugin.api.Tags tags() { return null; } public Evidence evidence() { return null; }
        public Docs docs() { return null; } public Settings settings() { return null; } public Llm llm() { return null; }
    }
}
