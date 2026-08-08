package com.checkba.service.feedback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈的服务端上下文：版本、运行环境、后端日志尾巴。
 *
 * <p>为什么在服务端采而不是让客户端传：版本号与日志是**判断这条反馈可不可信**的依据，
 * 客户端传什么都行的字段没有取证价值。客户端只负责它自己才知道的部分
 * （当前页面、窗口尺寸、最近的前端报错），那部分单独放在 client 段里。
 */
@Slf4j
@Service
public class FeedbackContextCollector {

    /** 单个日志文件最多取尾部这么多字节——反馈是给人和模型看的，整份日志既没用也涨库。 */
    private static final int LOG_TAIL_BYTES = 16 * 1024;

    private final String appVersion;
    private final List<String> logFiles;

    public FeedbackContextCollector(
            @Value("${telemetry.app-version:dev}") String appVersion,
            @Value("${feedback.log-files:}") String logFilesCsv) {
        this.appVersion = appVersion;
        this.logFiles = resolveLogFiles(logFilesCsv);
    }

    private static List<String> resolveLogFiles(String csv) {
        if (csv != null && !csv.isBlank()) {
            return List.of(csv.split(","));
        }
        // 默认两处：打包态桌面壳把 java 的输出写到 ~/.aiworkdeck/logs/backend.log；
        // dev 态 restart-backend.sh 写在 backend/app.log（相对工作目录）。
        String home = System.getProperty("user.home", "");
        return List.of(
                Paths.get(home, ".aiworkdeck", "logs", "backend.log").toString(),
                "app.log");
    }

    public String appVersion() {
        return appVersion;
    }

    public String platform() {
        return System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "?") + ")";
    }

    /** 运行环境快照（不含任何密钥/路径以外的敏感信息）。 */
    public Map<String, Object> runtimeInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appVersion", appVersion);
        m.put("platform", platform());
        m.put("java", System.getProperty("java.version", "?"));
        Runtime rt = Runtime.getRuntime();
        m.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        m.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        return m;
    }

    /** 后端日志尾巴：取第一个存在且非空的候选文件。取不到就返回空串，不编造。 */
    public String backendLogTail() {
        for (String candidate : logFiles) {
            String trimmed = candidate == null ? "" : candidate.trim();
            if (trimmed.isEmpty()) continue;
            Path p = Paths.get(trimmed);
            try {
                if (!Files.isRegularFile(p) || Files.size(p) == 0) continue;
                return tail(p);
            } catch (IOException e) {
                log.debug("读取日志尾巴失败: {} {}", p, e.toString());
            }
        }
        return "";
    }

    private static String tail(Path p) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long len = raf.length();
            long from = Math.max(0, len - LOG_TAIL_BYTES);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            // 截断处很可能落在半行中间，丢掉第一行残句
            int nl = s.indexOf('\n');
            return (from > 0 && nl >= 0) ? s.substring(nl + 1) : s;
        }
    }
}
