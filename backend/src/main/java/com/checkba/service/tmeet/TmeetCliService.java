// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet;

import com.checkba.service.tmeet.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯会议官方 CLI (tmeet) 执行封装服务。
 * 负责与本地 tmeet 命令行交互，执行鉴权、会议列表、逐字稿拉取及智能纪要提取。
 */
@Service
@Slf4j
public class TmeetCliService {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final Pattern URL_PATTERN = Pattern.compile("https://\\S+");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("UserName:\\s*(.+)");
    private static final Pattern OPENID_PATTERN = Pattern.compile("OpenId:\\s*(.+)");
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("AccessToken:\\s*(.+)");
    private static final Pattern REFRESH_TOKEN_PATTERN = Pattern.compile("RefreshToken:\\s*(.+)");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String configuredCliPath;

    private final AtomicReference<Process> activeLoginProcess = new AtomicReference<>(null);
    private final AtomicReference<String> currentAuthorizeUrl = new AtomicReference<>(null);

    public TmeetCliService(@Value("${tmeet.cli.path:}") String configuredCliPath) {
        this.configuredCliPath = configuredCliPath;
    }

    /**
     * 解析 tmeet 可执行文件的绝对路径。
     */
    public String resolveCliPath() {
        if (configuredCliPath != null && !configuredCliPath.isBlank()) {
            File f = new File(configuredCliPath);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }

        String envPath = System.getenv("TMEET_PATH");
        if (envPath != null && !envPath.isBlank()) {
            File f = new File(envPath);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }

        String userHome = System.getProperty("user.home");
        Path localBin = Path.of(userHome, ".local", "bin", "tmeet");
        if (Files.exists(localBin) && Files.isExecutable(localBin)) {
            return localBin.toAbsolutePath().toString();
        }

        Path commonMacPath = Path.of("/usr/local/bin/tmeet");
        if (Files.exists(commonMacPath) && Files.isExecutable(commonMacPath)) {
            return commonMacPath.toAbsolutePath().toString();
        }

        Path optHomebrew = Path.of("/opt/homebrew/bin/tmeet");
        if (Files.exists(optHomebrew) && Files.isExecutable(optHomebrew)) {
            return optHomebrew.toAbsolutePath().toString();
        }

        return "tmeet";
    }

    public boolean isCliAvailable() {
        try {
            String path = resolveCliPath();
            Process process = new ProcessBuilder(path, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            log.warn("检测 tmeet CLI 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查询当前登录状态及凭证信息。
     */
    public TmeetAuthStatus getAuthStatus() {
        String cliPath = resolveCliPath();
        boolean available = isCliAvailable();
        if (!available) {
            return TmeetAuthStatus.builder()
                    .cliAvailable(false)
                    .cliPath(cliPath)
                    .loggedIn(false)
                    .message("未找到或无法执行 tmeet CLI，请确保已安装 @tencentcloud/tmeet")
                    .build();
        }

        try {
            ExecutionResult res = executeCommand(List.of("auth", "status"), 10);
            String output = res.stdout;
            boolean loggedIn = output.contains("Logged in");

            TmeetAuthStatus.TmeetAuthStatusBuilder builder = TmeetAuthStatus.builder()
                    .cliAvailable(true)
                    .cliPath(cliPath)
                    .loggedIn(loggedIn);

            if (loggedIn) {
                Matcher mUser = USERNAME_PATTERN.matcher(output);
                if (mUser.find()) builder.userName(mUser.group(1).trim());

                Matcher mOpenId = OPENID_PATTERN.matcher(output);
                if (mOpenId.find()) builder.openId(mOpenId.group(1).trim());

                Matcher mAccess = ACCESS_TOKEN_PATTERN.matcher(output);
                if (mAccess.find()) builder.accessTokenExpiry(mAccess.group(1).trim());

                Matcher mRefresh = REFRESH_TOKEN_PATTERN.matcher(output);
                if (mRefresh.find()) builder.refreshTokenExpiry(mRefresh.group(1).trim());

                builder.message("已登录");
            } else {
                builder.message("未登录腾讯会议账号");
            }

            return builder.build();
        } catch (Exception e) {
            log.error("执行 tmeet auth status 失败: {}", e.getMessage());
            return TmeetAuthStatus.builder()
                    .cliAvailable(true)
                    .cliPath(cliPath)
                    .loggedIn(false)
                    .message("查询登录状态异常: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 发起扫码登录流程：启动 tmeet auth login --no-browser 并提取授权 URL。
     */
    public synchronized Map<String, Object> startLogin() {
        TmeetAuthStatus status = getAuthStatus();
        if (status.isLoggedIn()) {
            return Map.of("loggedIn", true, "message", "用户已经登录", "status", status);
        }

        // 如果已经有正在等待授权的进程，且 URL 仍有效，直接返回
        Process existing = activeLoginProcess.get();
        if (existing != null && existing.isAlive() && currentAuthorizeUrl.get() != null) {
            return Map.of(
                    "loggedIn", false,
                    "authorizeUrl", currentAuthorizeUrl.get(),
                    "message", "请使用微信或腾讯会议扫码授权"
            );
        }

        try {
            if (existing != null) {
                existing.destroyForcibly();
            }

            String cliPath = resolveCliPath();
            ProcessBuilder pb = new ProcessBuilder(cliPath, "auth", "login", "--no-browser");
            pb.environment().put("TMEET_AGENT", "AI WorkDeck");
            pb.environment().put("TMEET_MODEL", "WorkDeck AI");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            activeLoginProcess.set(process);

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String authUrl = null;
            long start = System.currentTimeMillis();
            StringBuilder logBuffer = new StringBuilder();

            // 在 10 秒内读取出输出里的 authorize url
            while (System.currentTimeMillis() - start < 10000) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    logBuffer.append(line).append("\n");
                    if (line.contains("Error: user has been login")) {
                        process.destroyForcibly();
                        return Map.of("loggedIn", true, "message", "用户已登录");
                    }
                    Matcher matcher = URL_PATTERN.matcher(line);
                    if (matcher.find()) {
                        authUrl = matcher.group();
                        break;
                    }
                } else {
                    Thread.sleep(200);
                }
            }

            if (authUrl == null) {
                process.destroyForcibly();
                log.warn("无法从 tmeet auth login 提取授权地址，输出: {}", logBuffer);
                throw new IllegalStateException("未能获取腾讯会议授权地址: " + logBuffer);
            }

            currentAuthorizeUrl.set(authUrl);

            // 启动异步线程保持进程并等待用户完成登录
            CompletableFuture.runAsync(() -> {
                try {
                    boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                    if (finished) {
                        log.info("tmeet auth login 进程结束，返回值: {}", process.exitValue());
                    } else {
                        log.warn("tmeet auth login 超时未完成，终止进程");
                        process.destroyForcibly();
                    }
                } catch (Exception e) {
                    log.error("等待 tmeet auth login 进程异常", e);
                } finally {
                    activeLoginProcess.compareAndSet(process, null);
                    currentAuthorizeUrl.set(null);
                }
            });

            return Map.of(
                    "loggedIn", false,
                    "authorizeUrl", authUrl,
                    "message", "请使用微信或腾讯会议扫码授权"
            );
        } catch (Exception e) {
            log.error("启动腾讯会议登录失败", e);
            throw new RuntimeException("启动腾讯会议登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 登出腾讯会议账号。
     */
    public boolean logout() {
        Process p = activeLoginProcess.getAndSet(null);
        if (p != null) {
            p.destroyForcibly();
        }
        currentAuthorizeUrl.set(null);
        try {
            ExecutionResult res = executeCommand(List.of("auth", "logout"), 10);
            return res.exitCode == 0;
        } catch (Exception e) {
            log.error("执行 tmeet auth logout 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取指定时间窗口内已结束的会议列表。
     */
    public List<TmeetMeetingItem> listEndedMeetings(LocalDateTime since, LocalDateTime until, int pageSize, String pageToken) {
        List<String> args = new ArrayList<>(List.of("meeting", "list-ended", "--format", "json"));
        if (since != null) {
            args.add("--start");
            args.add(since.atZone(DEFAULT_ZONE).format(ISO_FORMATTER));
        }
        if (until != null) {
            args.add("--end");
            args.add(until.atZone(DEFAULT_ZONE).format(ISO_FORMATTER));
        }
        args.add("--page-size");
        args.add(String.valueOf(pageSize > 0 ? pageSize : 20));

        if (pageToken != null && !pageToken.isBlank()) {
            args.add("--page-token");
            args.add(pageToken);
        }

        try {
            ExecutionResult res = executeCommand(args, 60);
            return parseMeetingListResponse(res.stdout);
        } catch (Exception e) {
            log.error("拉取已结束会议列表失败", e);
            throw new RuntimeException("拉取腾讯会议列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取指定时间窗口内本人的云录制列表。
     */
    public List<TmeetMeetingItem> listRecordings(LocalDateTime since, LocalDateTime until, int pageSize, String pageToken) {
        List<String> args = new ArrayList<>(List.of("record", "list", "--format", "json"));
        if (since != null) {
            args.add("--start");
            args.add(since.atZone(DEFAULT_ZONE).format(ISO_FORMATTER));
        }
        if (until != null) {
            args.add("--end");
            args.add(until.atZone(DEFAULT_ZONE).format(ISO_FORMATTER));
        }
        args.add("--page-size");
        args.add(String.valueOf(pageSize > 0 ? pageSize : 30));

        if (pageToken != null && !pageToken.isBlank()) {
            args.add("--page-token");
            args.add(pageToken);
        }

        try {
            ExecutionResult res = executeCommand(args, 60);
            return parseRecordListResponse(res.stdout);
        } catch (Exception e) {
            log.error("拉取云录制列表失败", e);
            throw new RuntimeException("拉取腾讯会议录制列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分页拉取单场会议录制文件的全部逐字稿段落。
     */
    public List<TmeetParagraph> getTranscript(String meetingId, String recordFileId) {
        List<TmeetParagraph> result = new ArrayList<>();
        String nextPid = null;

        for (int page = 0; page < 50; page++) {
            List<String> args = new ArrayList<>(List.of(
                    "record", "transcript-get",
                    "--meeting-id", meetingId,
                    "--record-file-id", recordFileId,
                    "--limit", "1000",
                    "--format", "json"
            ));
            if (nextPid != null) {
                args.add("--pid");
                args.add(nextPid);
            }

            ExecutionResult res = executeCommand(args, 60);
            try {
                JsonNode root = objectMapper.readTree(res.stdout);
                JsonNode data = root.path("data");
                JsonNode minutes = data.path("minutes");
                JsonNode paragraphs = minutes.path("paragraphs");

                if (paragraphs.isArray()) {
                    for (JsonNode p : paragraphs) {
                        String pid = p.path("pid").asText("");
                        String start = p.path("start_time").asText("");
                        String end = p.path("end_time").asText("");
                        JsonNode speaker = p.path("speaker");
                        String spName = speaker.path("user_name").asText("未知发言人");
                        String spAvatar = speaker.path("avatar_url").asText("");
                        String spUserId = speaker.path("user_id").asText("");

                        StringBuilder textBuilder = new StringBuilder();
                        JsonNode sentences = p.path("sentences");
                        if (sentences.isArray()) {
                            for (JsonNode s : sentences) {
                                JsonNode words = s.path("words");
                                if (words.isArray()) {
                                    for (JsonNode w : words) {
                                        textBuilder.append(w.path("text").asText(""));
                                    }
                                }
                            }
                        }

                        String text = textBuilder.toString().trim();
                        if (!text.isEmpty()) {
                            result.add(TmeetParagraph.builder()
                                    .pid(pid)
                                    .startTime(start)
                                    .endTime(end)
                                    .speakerName(spName)
                                    .speakerAvatar(spAvatar)
                                    .speakerUserId(spUserId)
                                    .text(text)
                                    .build());
                        }
                    }
                }

                boolean hasMore = data.path("more").asBoolean(false);
                if (!hasMore || paragraphs.isEmpty()) {
                    break;
                }
                JsonNode lastP = paragraphs.get(paragraphs.size() - 1);
                long lastPidVal = Long.parseLong(lastP.path("pid").asText("0"));
                nextPid = String.valueOf(lastPidVal + 1);
            } catch (Exception e) {
                log.error("解析逐字稿 JSON 失败: {}", res.stdout, e);
                throw new RuntimeException("解析逐字稿失败: " + e.getMessage(), e);
            }
        }

        return result;
    }

    /**
     * 获取单场录制的智能纪要（包含纪要正文和待办事项）。
     */
    public TmeetSmartMinutes getSmartMinutes(String recordFileId) {
        try {
            ExecutionResult res = executeCommand(List.of(
                    "record", "smart-minutes",
                    "--record-file-id", recordFileId,
                    "--format", "json"
            ), 60);

            JsonNode root = objectMapper.readTree(res.stdout);
            JsonNode data = root.path("data");
            JsonNode mm = data.path("meeting_minute");
            String minuteText = mm.path("minute").asText("").trim();

            List<TmeetSmartMinutes.TmeetTodo> todos = new ArrayList<>();
            JsonNode todosArr = mm.path("todos");
            if (todosArr.isArray()) {
                for (JsonNode item : todosArr) {
                    String content = item.path("content").asText("").trim();
                    List<String> executors = new ArrayList<>();
                    JsonNode exList = item.path("executor_list");
                    if (exList.isArray()) {
                        for (JsonNode ex : exList) {
                            String nick = ex.path("nick").asText("").trim();
                            if (!nick.isEmpty()) executors.add(nick);
                        }
                    }
                    todos.add(TmeetSmartMinutes.TmeetTodo.builder()
                            .content(content)
                            .executors(executors)
                            .build());
                }
            }

            return TmeetSmartMinutes.builder()
                    .minute(minuteText)
                    .rawJson(data.toString())
                    .todos(todos)
                    .build();
        } catch (Exception e) {
            log.warn("获取腾讯会议智能纪要失败 (fid={}): {}", recordFileId, e.getMessage());
            return TmeetSmartMinutes.builder().minute("").todos(Collections.emptyList()).build();
        }
    }

    // ==================== 响应解析与命令行执行底层 ====================

    private List<TmeetMeetingItem> parseMeetingListResponse(String json) {
        List<TmeetMeetingItem> list = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode meetingList = root.path("data").path("meeting_info_list");
            if (meetingList.isArray()) {
                for (JsonNode m : meetingList) {
                    String meetingId = m.path("meeting_id").asText("");
                    String meetingCode = m.path("meeting_code").asText("");
                    String subject = m.path("subject").asText("未命名会议");
                    String meetingType = m.path("meeting_type").asText("普通会议");
                    String subMeetingId = m.path("sub_meeting_id").asText("");
                    LocalDateTime startTime = parseDateTime(m.path("start_time").asText());
                    LocalDateTime endTime = parseDateTime(m.path("end_time").asText());

                    List<TmeetMeetingItem.TmeetRecordFile> records = new ArrayList<>();
                    JsonNode recArray = m.path("records");
                    if (recArray.isArray()) {
                        for (JsonNode r : recArray) {
                            records.add(TmeetMeetingItem.TmeetRecordFile.builder()
                                    .recordFileId(r.path("record_file_id").asText(""))
                                    .subject(r.path("subject").asText(subject))
                                    .duration(r.path("duration").asText(""))
                                    .type(r.path("type").asText("云录制"))
                                    .permissionStatus(r.path("permission_status").asText("can_view"))
                                    .url(r.path("url").asText(""))
                                    .mediaStartTime(parseDateTime(r.path("media_start_time").asText()))
                                    .build());
                        }
                    }

                    list.add(TmeetMeetingItem.builder()
                            .meetingId(meetingId)
                            .meetingCode(meetingCode)
                            .subject(subject)
                            .meetingType(meetingType)
                            .subMeetingId(subMeetingId)
                            .startTime(startTime)
                            .endTime(endTime)
                            .records(records)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("反序列化 meeting_info_list 失败: {}", json, e);
        }
        return list;
    }

    private List<TmeetMeetingItem> parseRecordListResponse(String json) {
        List<TmeetMeetingItem> list = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode recMeetings = root.path("data").path("record_meetings");
            if (recMeetings.isArray()) {
                for (JsonNode m : recMeetings) {
                    String meetingId = m.path("meeting_id").asText("");
                    String meetingCode = m.path("meeting_code").asText("");
                    String rawSubject = m.path("subject").asText("未命名会议");
                    String subject = rawSubject.replaceFirst("^转写_", "");

                    List<TmeetMeetingItem.TmeetRecordFile> records = new ArrayList<>();
                    JsonNode recFiles = m.path("record_files");
                    LocalDateTime mStartTime = null;
                    if (recFiles.isArray()) {
                        for (JsonNode f : recFiles) {
                            LocalDateTime fStart = parseDateTime(f.path("record_start_time").asText());
                            if (mStartTime == null) mStartTime = fStart;
                            records.add(TmeetMeetingItem.TmeetRecordFile.builder()
                                    .recordFileId(f.path("record_file_id").asText(""))
                                    .subject(subject)
                                    .duration("")
                                    .type("云录制")
                                    .permissionStatus("can_view")
                                    .url("")
                                    .mediaStartTime(fStart)
                                    .build());
                        }
                    }

                    list.add(TmeetMeetingItem.builder()
                            .meetingId(meetingId)
                            .meetingCode(meetingCode)
                            .subject(subject)
                            .meetingType("录制会议")
                            .startTime(mStartTime)
                            .records(records)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("反序列化 record_meetings 失败: {}", json, e);
        }
        return list;
    }

    private LocalDateTime parseDateTime(String isoStr) {
        if (isoStr == null || isoStr.isBlank()) return null;
        try {
            return LocalDateTime.parse(isoStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(isoStr).atZoneSameInstant(DEFAULT_ZONE).toLocalDateTime();
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private ExecutionResult executeCommand(List<String> commandArgs, int timeoutSeconds) {
        String cliPath = resolveCliPath();
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(cliPath);
        fullCommand.addAll(commandArgs);

        try {
            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            pb.environment().put("TMEET_AGENT", "AI WorkDeck");
            pb.environment().put("TMEET_MODEL", "WorkDeck AI");
            Process p = pb.start();

            String stdout;
            String stderr;
            try (var outReader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 var errReader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {

                Future<String> outFuture = CompletableFuture.supplyAsync(() -> readAll(outReader));
                Future<String> errFuture = CompletableFuture.supplyAsync(() -> readAll(errReader));

                boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    throw new TimeoutException("执行 tmeet 超时 (" + timeoutSeconds + "s)");
                }
                stdout = outFuture.get(2, TimeUnit.SECONDS);
                stderr = errFuture.get(2, TimeUnit.SECONDS);
            }

            int exitCode = p.exitValue();
            if (exitCode != 0) {
                log.warn("tmeet {} 退出码非零 ({}): stdout={}, stderr={}",
                        String.join(" ", commandArgs), exitCode, stdout, stderr);
            }
            return new ExecutionResult(exitCode, stdout, stderr);
        } catch (Exception e) {
            log.error("调用 tmeet 命令失败: {}", String.join(" ", fullCommand), e);
            throw new RuntimeException("执行 tmeet CLI 失败: " + e.getMessage(), e);
        }
    }

    private String readAll(BufferedReader reader) {
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException ignored) {
        }
        return sb.toString().trim();
    }

    public record ExecutionResult(int exitCode, String stdout, String stderr) {}
}
