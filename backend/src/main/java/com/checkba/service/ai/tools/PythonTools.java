package com.checkba.service.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Python Tools for the Agent (Dockerized).
 * 
 * Supports calling back to Java tools via file-based IPC:
 * - Python writes tool requests to /workspace/_tool_requests.json
 * - Java executes tools and writes results to /workspace/_tool_results.json
 * - Python reads results and continues execution
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PythonTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PythonTools.class);

    @Value("${external.tushare.token:}")
    private String tushareToken;

    @Value("${external.qichacha.key:}")
    private String qichachaKey;

    @Value("${external.qichacha.secret:}")
    private String qichachaSecret;

    private final LegalTools legalTools;
    private final WebTools webTools;
    private final com.checkba.service.SystemSettingService systemSettingService;

    private static final String DOCKER_IMAGE = "python:3.9-slim";

    // Python helper code that provides the default_api object
    private static final String PYTHON_API_BRIDGE = """
# === Auto-injected API Bridge ===
import json
import os

class _ToolAPI:
    \"\"\"Bridge to call Java backend tools from Python.\"\"\"
    
    def __init__(self):
        self._request_file = "/workspace/_tool_request.json"
        self._result_file = "/workspace/_tool_result.json"
    
    def _call_tool(self, tool_name, **kwargs):
        \"\"\"Internal method to call a backend tool.\"\"\"
        request = {
            "tool": tool_name,
            "args": kwargs
        }
        # Write request
        with open(self._request_file, 'w', encoding='utf-8') as f:
            json.dump(request, f, ensure_ascii=False)
        
        # Signal ready (create marker file)
        with open("/workspace/_request_ready", 'w') as f:
            f.write("1")
        
        # Wait for result (poll for result file)
        import time
        for _ in range(300):  # 30 second timeout
            if os.path.exists("/workspace/_result_ready"):
                break
            time.sleep(0.1)
        
        # Read result
        if os.path.exists(self._result_file):
            with open(self._result_file, 'r', encoding='utf-8') as f:
                result = json.load(f)
            # Cleanup
            if os.path.exists("/workspace/_request_ready"):
                os.remove("/workspace/_request_ready")
            if os.path.exists("/workspace/_result_ready"):
                os.remove("/workspace/_result_ready")
            if os.path.exists(self._request_file):
                os.remove(self._request_file)
            if os.path.exists(self._result_file):
                os.remove(self._result_file)
            
            if result.get("error"):
                raise Exception(result["error"])
            return result.get("content", "")
        else:
            raise Exception("Tool call timed out - no result received")
    
    def read_document(self, fileId):
        \"\"\"Read a document from the project by its file ID.\"\"\"
        return {"content": self._call_tool("read_document", fileId=str(fileId))}
    
    def search_web(self, query):
        \"\"\"Search the web using Baidu.\"\"\"
        return {"content": self._call_tool("search_web", query=query)}
    
    def browse_url(self, url):
        \"\"\"Browse a URL and extract its content.\"\"\"
        return {"content": self._call_tool("browse_url", url=url)}

# Create the global API object
default_api = _ToolAPI()

# === End API Bridge ===

""";

    @ToolMeta(displayName = "执行Python代码", category = "python")
    @Tool("Run Python script. Use this for data analysis, Tushare stock data, or Qichacha API. You can call default_api.read_document(fileId='...') to read project files. Returns stdout/stderr.")
    public String run_python(String code) {
        if (code == null || code.isBlank()) {
            return "Error: code is required.";
        }
        log.info("Tool: run_python called. Code length={}", code.length());

        java.util.Map<String, String> credentials = resolveExternalCredentials();
        // Debug: Log API key status (masked for security)
        log.info("API Key Status - TUSHARE_TOKEN: {}, QICHACHA_KEY: {}, QICHACHA_SECRET: {}",
            maskValue(credentials.get("TUSHARE_TOKEN")),
            maskValue(credentials.get("QICHACHA_KEY")),
            maskValue(credentials.get("QICHACHA_SECRET")));

        Path tempDir = null;
        Process process = null;
        try {
            // 1. Prepare Workspace on Host
            tempDir = Files.createTempDirectory("agent_python_ctx_");
            Path scriptPath = tempDir.resolve("script.py");
            
            // Auto-append pip install for common libs if imported but not likely in slim
            StringBuilder finalCode = new StringBuilder();
            
            // Inject API Bridge
            finalCode.append(PYTHON_API_BRIDGE);
            
            if (code.contains("tushare") || code.contains("pandas") || code.contains("requests")) {
                 finalCode.append("# Auto-install dependency check (slow)\n");
                 finalCode.append("import subprocess, sys\n");
                 finalCode.append("def install(package):\n");
                 finalCode.append("    subprocess.check_call([sys.executable, \"-m\", \"pip\", \"install\", \"-q\", package])\n");
                 
                 if (code.contains("tushare")) finalCode.append("try: import tushare\nexcept: install('tushare')\n");
                 if (code.contains("pandas")) finalCode.append("try: import pandas\nexcept: install('pandas')\n");
                 if (code.contains("requests")) finalCode.append("try: import requests\nexcept: install('requests')\n");
                 if (code.contains("matplotlib")) finalCode.append("try: import matplotlib\nexcept: install('matplotlib')\n");
                 finalCode.append("\n");
            }
            finalCode.append(code);

            Files.writeString(scriptPath, finalCode.toString(), StandardOpenOption.CREATE);

            // 2. Build Docker Command
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("run");
            command.add("--rm");
            // Volume Mount
            command.add("-v");
            command.add(tempDir.toAbsolutePath().toString() + ":/workspace");
            // Working Dir
            command.add("-w");
            command.add("/workspace");
            // Env Vars
            credentials.forEach((name, value) -> {
                command.add("-e");
                command.add(name + "=" + value);
            });

            // Image & Command
            command.add(DOCKER_IMAGE);
            command.add("python");
            command.add("-u");  // Unbuffered output
            command.add("script.py");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            log.info("Executing Docker command: {}", String.join(" ", command));
            
            process = pb.start();

            // 并发排空 stdout/stderr：子进程输出超过 OS 管道缓冲区(~64KB)时会阻塞在 write，
            // 若主线程等进程退出后才读，进程将永不退出 → 假超时。必须边跑边读。
            final StringBuilder stdoutBuf = new StringBuilder();
            final StringBuilder stderrBuf = new StringBuilder();
            Thread outPump = pumpStream(process.getInputStream(), stdoutBuf);
            Thread errPump = pumpStream(process.getErrorStream(), stderrBuf);

            // 3. Monitor for tool call requests (IPC loop)
            Path requestReadyPath = tempDir.resolve("_request_ready");
            Path requestPath = tempDir.resolve("_tool_request.json");
            Path resultPath = tempDir.resolve("_tool_result.json");
            Path resultReadyPath = tempDir.resolve("_result_ready");
            
            long startTime = System.currentTimeMillis();
            long timeout = 120_000; // 2 minutes total
            
            while (process.isAlive()) {
                // Check timeout
                if (System.currentTimeMillis() - startTime > timeout) {
                    process.destroyForcibly();
                    return "Error: Python execution timed out (120s limit).";
                }
                
                // Check for tool call request
                if (Files.exists(requestReadyPath)) {
                    log.info("Detected tool call request from Python");
                    
                    try {
                        // Read request
                        String requestJson = Files.readString(requestPath);
                        cn.hutool.json.JSONObject request = cn.hutool.json.JSONUtil.parseObj(requestJson);
                        
                        String toolName = request.getStr("tool");
                        cn.hutool.json.JSONObject args = request.getJSONObject("args");
                        
                        log.info("Python requesting tool: {} with args: {}", toolName, args);
                        
                        // Execute tool
                        String result = executeToolForPython(toolName, args);
                        
                        // Write result
                        cn.hutool.json.JSONObject resultObj = new cn.hutool.json.JSONObject();
                        if (result.startsWith("Error")) {
                            resultObj.set("error", result);
                        } else {
                            resultObj.set("content", result);
                        }
                        Files.writeString(resultPath, resultObj.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        
                        // Signal result ready
                        Files.writeString(resultReadyPath, "1", StandardOpenOption.CREATE);
                        
                        log.info("Tool result written for Python, length: {}", result.length());
                        
                    } catch (Exception e) {
                        log.error("Error processing Python tool request", e);
                        cn.hutool.json.JSONObject errorResult = new cn.hutool.json.JSONObject();
                        errorResult.set("error", "Tool execution failed: " + e.getMessage());
                        Files.writeString(resultPath, errorResult.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.writeString(resultReadyPath, "1", StandardOpenOption.CREATE);
                    }
                }
                
                // Sleep briefly to avoid busy-waiting
                Thread.sleep(100);
            }
            
            // 4. Capture Output（等排空线程收齐后再读缓冲）
            outPump.join(5000);
            errPump.join(5000);
            String stdout;
            String stderr;
            synchronized (stdoutBuf) { stdout = stdoutBuf.toString().trim(); }
            synchronized (stderrBuf) { stderr = stderrBuf.toString().trim(); }
            
            // 5. Return
            if (process.exitValue() != 0) {
                return "Python Error (Exit Code " + process.exitValue() + "):\n" + stderr + "\n\nStdout:\n" + stdout;
            }
            
            return "Python Output:\n" + stdout;

        } catch (Exception e) {
            log.error("Docker Python Error", e);
            return "System Error executing Python in Docker: " + e.getMessage();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            // Cleanup temp dir
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.delete(path); } catch (Exception ignored) {}
                        });
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * 注入 Python 子进程的外部服务凭证（环境变量名 -> 值）。
     *
     * <p><b>库优先、yml 兜底</b>，与同一批凭证的 Java 侧调用方（{@code TushareService} /
     * {@code QichachaService}）同源。早先这里只读 {@code @Value}，而用户在
     * 「系统管理 → 外部服务」填的凭证写进的是 {@code system_setting}——脚本因此永远拿到空值，
     * 且不会报「未配置」，只是查不到数据，AI 据此回答「没有查到该公司的信息」。
     *
     * <p>取值放在每次调用时而不是启动时：设置页改完即时生效，不用重启后端。
     */
    java.util.Map<String, String> resolveExternalCredentials() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("TUSHARE_TOKEN", orEmpty(systemSettingService.get("external.tushare.token", tushareToken)));
        env.put("QICHACHA_KEY", orEmpty(systemSettingService.get("external.qichacha.key", qichachaKey)));
        env.put("QICHACHA_SECRET", orEmpty(systemSettingService.get("external.qichacha.secret", qichachaSecret)));
        return env;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    /**
     * 后台守护线程持续读取进程输出流到缓冲，防止管道写阻塞导致子进程挂死。
     */
    private Thread pumpStream(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) { sink.append(line).append('\n'); }
                }
            } catch (java.io.IOException ignored) {
                // 进程结束/流关闭时正常退出
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Execute a tool requested by Python code.
     */
    private String executeToolForPython(String toolName, cn.hutool.json.JSONObject args) {
        try {
            switch (toolName) {
                case "read_document":
                    String fileId = args.getStr("fileId");
                    return legalTools.read_document(fileId);
                    
                case "search_web":
                    String query = args.getStr("query");
                    return webTools.search_web(query);
                    
                case "browse_url":
                    String url = args.getStr("url");
                    return webTools.browse_url(url);
                    
                default:
                    return "Error: Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            log.error("Error executing tool {} for Python", toolName, e);
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * Mask sensitive values for logging - show only first 4 and last 4 chars.
     */
    private String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return "[EMPTY]";
        }
        if (value.length() <= 8) {
            return "[SET:" + value.length() + "chars]";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
