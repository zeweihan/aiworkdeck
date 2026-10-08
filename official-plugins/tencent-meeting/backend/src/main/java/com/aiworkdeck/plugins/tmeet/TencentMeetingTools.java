// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.aiworkdeck.plugins.tmeet;

import com.checkba.plugin.api.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.aiworkdeck.plugins.tmeet.TmeetCli.Failure;

/** Downloadable plugin: only public host SPI, no access to Spring services or desktop sessions. */
public class TencentMeetingTools implements HostAware {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PAGES = 100;
    private final TmeetCli.Runner cli;
    private final Path root;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, String> jobOwners = new ConcurrentHashMap<>();
    private PluginHost host;

    public TencentMeetingTools() {
        this(new TmeetCli.NativeRunner(), Path.of(System.getProperty("user.home"), ".aiworkdeck", "plugin-data", "tencent-meeting"));
    }
    TencentMeetingTools(TmeetCli.Runner cli, Path root) { this.cli = cli; this.root = root; }
    @Override public void setHost(PluginHost host) { this.host = host; }

    @Tool("腾讯会议下载插件。action为status/login/logout/config/sync/list/detail/export；json是JSON对象。sync/detail/export返回pending及jobId时，用同action和{jobId}继续查询。只使用不透明key，不接收内部会议ID。")
    public String tencent_meeting_action(@P("操作名") String action, @P("JSON参数对象，默认{}") String json) {
        try {
            ToolCall call = identity();
            JsonNode args = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
            if (args == null || !args.isObject()) throw new Failure("参数必须是 JSON 对象");
            if (Set.of("sync", "detail", "export").contains(action == null ? "" : action)) {
                requireProject(call);
                if (args.has("jobId")) return poll(action, args.path("jobId").asText(), call).toString();
                return ok(startJob(action, args.deepCopy(), call)).toString();
            }
            Object result = switch (action == null ? "" : action) {
                case "status" -> status();
                case "login" -> login(call);
                case "logout" -> logout(call, args);
                case "config", "list" -> {
                    requireProject(call);
                    synchronized (lock(call)) {
                        ObjectNode state = readState(call);
                        yield action.equals("config") ? config(call, state, args) : Map.of("meetings", summaries(state));
                    }
                }
                default -> throw new Failure("不支持的腾讯会议操作");
            };
            return ok(result).toString();
        } catch (Failure e) { return error(e.getMessage()).toString(); }
        catch (Exception e) { return error("操作未完成，请检查项目权限、CLI 状态或稍后重试").toString(); }
    }
    private ToolCall identity() {
        ToolCall call = host == null ? null : host.call();
        if (call == null || call.userId() == null || call.userId() <= 0) throw new Failure("请先登录 AI WorkDeck");
        return call;
    }
    private void requireProject(ToolCall call) {
        if (call.projectId() == null || call.projectId() <= 0) throw new Failure("请先打开一个项目");
        // The public SPI checks project membership; never trust user-supplied projectId/userId.
        host.files().list(call.projectId(), null, false);
    }
    private String scope(ToolCall call) { return call.userId() + "/" + call.projectId(); }
    private Object lock(ToolCall call) { return locks.computeIfAbsent(scope(call), ignored -> new Object()); }
    private Object startJob(String action, JsonNode args, ToolCall call) {
        if (!action.equals("sync")) validateKey(args.path("key").asText());
        JobHandle handle = host.jobs().start("tencent-meeting-" + action, "腾讯会议：" + switch (action) {
            case "sync" -> "同步会议"; case "detail" -> "读取逐字稿"; default -> "归档逐字稿";
        }, ctx -> {
            try {
                ctx.checkCancelled();
                ToolCall snapshot = ctx.call();
                if (snapshot == null || !Objects.equals(snapshot.userId(), call.userId()) || !Objects.equals(snapshot.projectId(), call.projectId()))
                    throw new Failure("任务身份已失效，请重新操作");
                Object result;
                synchronized (lock(snapshot)) {
                    ObjectNode state = readState(snapshot);
                    result = switch (action) {
                        case "sync" -> sync(snapshot, state, ctx);
                        case "detail" -> detail(state, args.path("key").asText(), ctx);
                        default -> export(snapshot, state, args.path("key").asText(), ctx);
                    };
                }
                ctx.result(ok(result).toString());
            } catch (Failure e) { ctx.result(error(e.getMessage()).toString()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); ctx.result(error("任务已取消").toString()); }
            catch (Exception e) { ctx.result(error("腾讯会议任务未完成，请稍后重试").toString()); }
        });
        jobOwners.put(handle.jobId(), scope(call) + "/" + action);
        return Map.of("pending", true, "jobId", handle.jobId());
    }
    private JsonNode poll(String action, String id, ToolCall call) throws IOException {
        if (!Objects.equals(jobOwners.get(id), scope(call) + "/" + action)) throw new Failure("找不到当前用户的任务，请重新操作");
        JobStatus status = host.jobs().status(id);
        if (status == null) throw new Failure("任务已失效，请重新操作");
        if (status.status().equals("done")) {
            if (status.resultJson() == null) throw new Failure("任务未返回结果，请重试");
            return JSON.readTree(status.resultJson());
        }
        if (Set.of("failed", "cancelled").contains(status.status())) throw new Failure("任务未完成或已取消，请重试");
        return ok(Map.of("pending", true, "jobId", id));
    }
    private Object status() {
        try { successful(List.of("--version"), Duration.ofSeconds(5)); }
        catch (Failure e) { return Map.of("cliAvailable", false, "loggedIn", false, "userName", "", "message", TmeetCli.MISSING); }
        String output = successful(List.of("auth", "status"), Duration.ofSeconds(10));
        boolean loggedIn = output.lines().anyMatch(line -> line.trim().equals("Logged in"));
        String name = output.lines().map(String::trim).filter(line -> line.startsWith("UserName:"))
            .map(line -> line.substring("UserName:".length()).trim()).findFirst().orElse("");
        return Map.of("cliAvailable", true, "loggedIn", loggedIn, "userName", name,
            "message", loggedIn ? "已连接本机腾讯会议账号（本机 CLI 账号由所有桌面用户共享）" : "请先授权本机腾讯会议账号");
    }
    private Object login(ToolCall call) {
        String url = cli.login(call.userId());
        if (!TmeetCli.validAuthorizeUrl(url)) throw new Failure("授权地址校验失败，请在终端运行 tmeet auth login");
        return Map.of("authorizeUrl", url);
    }
    private Object logout(ToolCall call, JsonNode args) {
        if (!args.path("confirmed").asBoolean(false)) throw new Failure("退出会清除本机共享的腾讯会议授权，请确认后重试");
        cli.cancelLogin(call.userId()); successful(List.of("auth", "logout"), Duration.ofSeconds(10));
        return Map.of("loggedIn", false);
    }
    private String successful(List<String> args, Duration timeout) {
        TmeetCli.Result result = cli.run(args, timeout);
        if (result.exitCode() != 0) throw new Failure("腾讯会议请求失败，请检查本机授权、CLI 版本及录制权限");
        return result.output();
    }
    private JsonNode query(List<String> args) {
        try {
            JsonNode response = JSON.readTree(successful(args, REQUEST_TIMEOUT));
            if (response == null || !response.path("data").isObject() || response.hasNonNull("error")
                    || response.path("code").asInt(0) != 0 || response.path("error_code").asInt(0) != 0)
                throw new Failure("腾讯会议响应异常，请检查授权与录制权限");
            return response.path("data");
        } catch (IOException e) { throw new Failure("无法解析腾讯会议响应，请检查 CLI 版本"); }
    }
    private Object config(ToolCall call, ObjectNode state, JsonNode args) throws IOException {
        ObjectNode config = (ObjectNode) state.path("config");
        if (args.has("lookbackDays")) {
            if (!args.get("lookbackDays").canConvertToInt() || args.get("lookbackDays").asInt() < 1 || args.get("lookbackDays").asInt() > 90)
                throw new Failure("同步范围应为 1 至 90 天");
            config.put("lookbackDays", args.get("lookbackDays").asInt());
        }
        if (args.has("excludeKeywords")) {
            JsonNode words = args.get("excludeKeywords");
            if (!words.isArray() || words.size() > 30) throw new Failure("排除词最多 30 个");
            ArrayNode normalized = JSON.createArrayNode();
            for (JsonNode word : words) {
                if (!word.isTextual() || word.asText().length() > 100) throw new Failure("每个排除词应为不超过 100 字的文本");
                if (!word.asText().isBlank()) normalized.add(word.asText().trim());
            }
            config.set("excludeKeywords", normalized);
        }
        if (args.has("lookbackDays") || args.has("excludeKeywords")) writeState(call, state);
        return config;
    }
    private Object sync(ToolCall call, ObjectNode state, JobContext ctx) throws Exception {
        ObjectNode previousMeetings = (ObjectNode) state.path("meetings");
        ObjectNode staged = state.deepCopy(); staged.set("meetings", JSON.createObjectNode());
        OffsetDateTime end = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        int days = state.path("config").path("lookbackDays").asInt(30);
        if (days < 1 || days > 90) throw new Failure("同步范围应为 1 至 90 天，请重新保存设置");
        String start = end.minusDays(days).toString();
        List<String> errors = new ArrayList<>();
        for (boolean recordings : List.of(false, true)) {
            String token = "";
            Set<String> seen = new HashSet<>();
            try {
                for (int page = 0; page < MAX_PAGES; page++) {
                    ctx.checkCancelled();
                    List<String> command = new ArrayList<>(List.of(recordings ? "record" : "meeting", recordings ? "list" : "list-ended",
                        "--start", start, "--end", end.toString(), "--page-size", "30", "--format", "json"));
                    if (!token.isEmpty()) command.addAll(List.of("--page-token", token));
                    JsonNode data = query(command);
                    JsonNode rows = data.path(recordings ? "record_meetings" : "meeting_info_list");
                    if (!rows.isArray()) throw new Failure("腾讯会议列表结构异常，请检查 CLI 版本");
                    for (JsonNode row : rows) mergeMeeting(staged, previousMeetings, row, recordings, errors);
                    token = data.path("next_page_token").asText("");
                    if (token.isBlank()) {
                        if (data.path("has_more").asBoolean(false)) throw new Failure("腾讯会议仍有后续数据，但未返回分页标识，请稍后重试");
                        break;
                    }
                    if (!seen.add(token) || page == MAX_PAGES - 1) throw new Failure("腾讯会议列表尚未全部同步，请缩小日期范围后重试");
                }
            } catch (Failure e) { errors.add((recordings ? "录制列表：" : "已结束会议：") + e.getMessage()); }
        }
        // Commit one complete snapshot. Failed pagination must not silently remove older cached meetings.
        int added = 0;
        if (errors.isEmpty()) {
            for (Iterator<String> keys = staged.path("meetings").fieldNames(); keys.hasNext();) if (!previousMeetings.has(keys.next())) added++;
            state.set("meetings", staged.path("meetings")); writeState(call, state);
        }
        return Map.of("count", state.path("meetings").size(), "added", added, "complete", errors.isEmpty(), "errors", errors);
    }
    private void mergeMeeting(ObjectNode state, ObjectNode previousMeetings, JsonNode row, boolean recordings, List<String> errors) {
        String meetingId = row.path("meeting_id").asText("");
        if (meetingId.isBlank()) throw new Failure("腾讯会议列表缺少必要标识，请更新 CLI");
        String subject = row.path("subject").asText("未命名会议");
        for (JsonNode word : state.path("config").path("excludeKeywords")) if (subject.contains(word.asText())) return;
        ObjectNode meetings = (ObjectNode) state.path("meetings");
        ObjectNode entry = null;
        for (JsonNode item : meetings) if (meetingId.equals(item.path("meetingId").asText())) { entry = (ObjectNode) item; break; }
        if (entry == null) {
            String key = UUID.randomUUID().toString();
            for (JsonNode old : previousMeetings) if (meetingId.equals(old.path("meetingId").asText())) { key = old.path("key").asText(); break; }
            entry = JSON.createObjectNode(); entry.put("key", key); entry.put("meetingId", meetingId);
            entry.set("recordFiles", JSON.createArrayNode()); meetings.set(entry.path("key").asText(), entry);
        }
        entry.put("subject", subject); entry.put("meetingCode", row.path("meeting_code").asText(""));
        if (!row.path("start_time").asText("").isBlank()) entry.put("startTime", row.path("start_time").asText());
        JsonNode files = row.path(recordings ? "record_files" : "records");
        if (recordings && recordingPending(row.path("state").asText(""))) {
            // record-list state belongs to the meeting row, not its record_files children.
            // Also withdraw IDs already supplied by list-ended in this same snapshot.
            ArrayNode allowed = (ArrayNode) entry.path("recordFiles");
            if (!files.isArray() || files.isEmpty()) allowed.removeAll();
            else for (JsonNode file : files) {
                String id = file.path("record_file_id").asText("");
                for (int i = allowed.size() - 1; i >= 0; i--) if (allowed.get(i).asText().equals(id)) allowed.remove(i);
            }
            return;
        }
        if (recordings && !files.isArray()) {
            // Some record-list rows omit file IDs. Official protocol requires the meeting-level fallback.
            try {
                JsonNode data = query(List.of("meeting", "get", "--meeting-id", meetingId, "--format", "json"));
                JsonNode info = data.path("meeting_info_list");
                JsonNode meeting = info.isArray() && !info.isEmpty() ? info.get(0) : data;
                files = meeting.path("records"); recordings = false;
                if (!files.isArray()) errors.add("部分录制缺少文件信息，请稍后重新同步");
            } catch (Failure e) { errors.add("部分录制详情读取失败：" + e.getMessage()); }
        }
        if (!files.isArray()) return;
        ArrayNode allowed = (ArrayNode) entry.path("recordFiles");
        for (JsonNode file : files) {
            String id = file.path("record_file_id").asText("");
            String stateName = file.path("state").asText("");
            if (!recordings && !file.path("permission_status").asText().equals("can_view")) continue;
            if (recordingPending(stateName)) continue;
            if (id.isBlank()) continue;
            boolean exists = false;
            for (JsonNode old : allowed) if (old.asText().equals(id)) exists = true;
            if (!exists) allowed.add(id);
            if (!entry.hasNonNull("startTime")) entry.put("startTime", file.path("record_start_time").asText(file.path("media_start_time").asText("")));
        }
    }
    private static boolean recordingPending(String state) {
        return state.contains("录制中") || state.contains("转码中");
    }
    private ObjectNode detail(ObjectNode state, String key, JobContext ctx) throws Exception {
        ObjectNode entry = entry(state, key);
        ObjectNode output = summary(entry);
        ArrayNode paragraphs = output.putArray("paragraphs"), todos = output.putArray("todos"), errors = output.putArray("errors");
        StringBuilder minutes = new StringBuilder();
        if (entry.path("recordFiles").isEmpty()) throw new Failure("该会议暂无有权限且已完成转码的录制");
        for (JsonNode file : entry.path("recordFiles")) {
            ctx.checkCancelled();
            try { transcript(entry.path("meetingId").asText(), file.asText(), paragraphs, ctx); }
            catch (Failure e) { errors.add("逐字稿：" + e.getMessage()); }
            try {
                JsonNode mm = query(List.of("record", "smart-minutes", "--record-file-id", file.asText(), "--format", "json")).path("meeting_minute");
                String text = mm.path("minute").asText("");
                if (!text.isBlank()) { if (!minutes.isEmpty()) minutes.append("\n\n"); minutes.append(text); }
                for (JsonNode todo : mm.path("todos")) {
                    ObjectNode item = todos.addObject(); item.put("content", todo.path("content").asText(""));
                    ArrayNode executors = item.putArray("executors");
                    for (JsonNode executor : todo.path("executor_list")) executors.add(executor.path("nick").asText(""));
                }
            } catch (Failure e) { errors.add("智能纪要：" + e.getMessage()); }
        }
        output.put("smartMinutes", minutes.toString()); output.put("complete", errors.isEmpty());
        return output;
    }
    private void transcript(String meetingId, String file, ArrayNode output, JobContext ctx) throws Exception {
        String pid = "";
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            ctx.checkCancelled();
            List<String> command = new ArrayList<>(List.of("record", "transcript-get", "--meeting-id", meetingId,
                "--record-file-id", file, "--limit", "1000", "--format", "json"));
            if (!pid.isEmpty()) command.addAll(List.of("--pid", pid));
            JsonNode data = query(command), rows = data.path("minutes").path("paragraphs");
            if (!rows.isArray()) throw new Failure("暂无逐字稿，或逐字稿响应结构不兼容");
            for (JsonNode row : rows) {
                StringBuilder text = new StringBuilder();
                for (JsonNode sentence : row.path("sentences")) for (JsonNode word : sentence.path("words")) text.append(word.path("text").asText(""));
                ObjectNode item = output.addObject(); item.put("speaker", row.path("speaker").path("user_name").asText("未知发言人"));
                item.put("text", text.toString()); item.put("startTime", row.path("start_time").asText(""));
            }
            if (!data.path("more").asBoolean(false)) return;
            if (rows.isEmpty() || page == MAX_PAGES - 1) throw new Failure("逐字稿尚未读取完整，请缩小录制范围或稍后重试");
            String last = rows.get(rows.size() - 1).path("pid").asText("");
            try { pid = new java.math.BigInteger(last).add(java.math.BigInteger.ONE).toString(); }
            catch (NumberFormatException e) { throw new Failure("逐字稿分页标识异常，请更新 CLI"); }
            if (!seen.add(pid)) throw new Failure("逐字稿分页重复，请稍后重试");
        }
    }
    private Object export(ToolCall call, ObjectNode state, String key, JobContext ctx) throws Exception {
        ObjectNode value = detail(state, key, ctx);
        if (!value.path("complete").asBoolean()) throw new Failure("逐字稿或纪要未完整读取，暂未归档；请重试");
        String subject = value.path("subject").asText("腾讯会议");
        StringBuilder md = new StringBuilder("# ").append(subject).append("\n\n")
            .append("- 会议号：").append(value.path("meetingCode").asText()).append("\n- 时间：").append(value.path("startTime").asText())
            .append("\n- 来源：腾讯会议\n\n## 智能纪要\n\n").append(value.path("smartMinutes").asText()).append("\n\n## 逐字稿\n\n");
        for (JsonNode p : value.path("paragraphs")) md.append("**").append(p.path("speaker").asText()).append("** ")
            .append(p.path("startTime").asText()).append("\n\n").append(p.path("text").asText()).append("\n\n");
        if (!value.path("todos").isEmpty()) {
            md.append("## 待办事项\n\n");
            for (JsonNode todo : value.path("todos")) {
                md.append("- ").append(todo.path("content").asText());
                List<String> names = new ArrayList<>();
                for (JsonNode name : todo.path("executors")) if (!name.asText().isBlank()) names.add(name.asText());
                if (!names.isEmpty()) md.append("（").append(String.join("、", names)).append("）");
                md.append("\n");
            }
        }
        ctx.checkCancelled();
        FileInfo folder = host.files().createFolderPath(call.projectId(), List.of("腾讯会议"));
        String name = subject.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 80) name = name.substring(0, 80);
        FileInfo result = host.files().write(call.projectId(), folder.id(), name + "-逐字稿.md",
            new ByteArrayInputStream(md.toString().getBytes(StandardCharsets.UTF_8)), ConflictPolicy.RENAME);
        return Map.of("fileId", result.id(), "fileName", result.name(), "path", result.path());
    }
    private static void validateKey(String key) {
        if (!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new Failure("会议标识无效，请重新同步后选择会议");
    }
    private ObjectNode entry(ObjectNode state, String key) {
        validateKey(key);
        JsonNode entry = state.path("meetings").path(key);
        if (!entry.isObject()) throw new Failure("当前用户或项目中找不到该会议，请先同步");
        return (ObjectNode) entry;
    }
    private ObjectNode summary(JsonNode entry) {
        ObjectNode result = JSON.createObjectNode();
        for (String field : List.of("key", "subject", "meetingCode", "startTime")) result.put(field, entry.path(field).asText(""));
        return result;
    }
    private ArrayNode summaries(ObjectNode state) {
        ArrayNode result = JSON.createArrayNode(); for (JsonNode entry : state.path("meetings")) result.add(summary(entry)); return result;
    }
    private Path statePath(ToolCall call) { return root.resolve("users").resolve(call.userId().toString()).resolve("projects").resolve(call.projectId().toString()).resolve("state.json"); }
    private ObjectNode readState(ToolCall call) throws IOException {
        Path file = statePath(call);
        if (java.nio.file.Files.exists(file)) {
            if (java.nio.file.Files.size(file) > 32 * 1024 * 1024 || java.nio.file.Files.isSymbolicLink(file)) throw new Failure("本地缓存异常，请联系支持");
            JsonNode state = JSON.readTree(file.toFile());
            if (state == null || !state.isObject() || !state.path("config").isObject() || !state.path("meetings").isObject()) throw new Failure("本地缓存格式异常，请联系支持");
            return (ObjectNode) state;
        }
        ObjectNode state = JSON.createObjectNode();
        ObjectNode config = state.putObject("config"); config.put("lookbackDays", 30); config.putArray("excludeKeywords");
        state.putObject("meetings"); return state;
    }
    private void writeState(ToolCall call, ObjectNode state) throws IOException {
        Path file = statePath(call);
        java.nio.file.Files.createDirectories(file.getParent());
        for (Path dir = file.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            if (java.nio.file.Files.isSymbolicLink(dir)) throw new Failure("本地缓存路径异常，请联系支持");
            permissions(dir, "rwx------");
        }
        Path temp = java.nio.file.Files.createTempFile(file.getParent(), ".state-", ".json");
        try {
            permissions(temp, "rw-------"); JSON.writeValue(temp.toFile(), state);
            try { java.nio.file.Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { java.nio.file.Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { java.nio.file.Files.deleteIfExists(temp); }
    }
    private static void permissions(Path path, String value) throws IOException {
        try { java.nio.file.Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(value)); }
        catch (UnsupportedOperationException ignored) { /* Windows uses the user's home ACL. */ }
    }
    private static ObjectNode ok(Object data) { ObjectNode node = JSON.createObjectNode(); node.put("success", true); node.set("data", JSON.valueToTree(data)); return node; }
    private static ObjectNode error(String message) { return JSON.createObjectNode().put("success", false).put("error", message); }
}
