package com.checkba.service.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 匿名安装标识（设计见 docs/ANALYTICS_TELEMETRY_DESIGN.md §5.1）。
 *
 * - install-id：按安装唯一的 UUID，随日聚合上报，用于活跃/留存统计；不含任何设备或用户信息。
 * - install-secret：仅本机持有、永不上传的随机盐；用于把 conversationId
 *   （形如 conv-${Date.now()}，含时间戳可预测）派生成不可反推的会话关联键。
 *
 * 与 entitlements.json/local.mv.db 同目录（security.license.dir，默认 ~/.aiworkdeck）。
 */
@Slf4j
@Service
public class InstallIdentityService {

    private final Path idFile;
    private final Path secretFile;

    private volatile String cachedId;
    private volatile byte[] cachedSecret;

    public InstallIdentityService(
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir) {
        this.idFile = Path.of(stateDir, "install-id");
        this.secretFile = Path.of(stateDir, "install-secret");
    }

    /** 匿名安装 ID（UUID 字符串），首次调用时生成并落盘。 */
    public synchronized String installId() {
        if (cachedId != null) return cachedId;
        try {
            Files.createDirectories(idFile.getParent());
            if (Files.exists(idFile)) {
                String v = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    cachedId = v;
                    return cachedId;
                }
            }
            String fresh = UUID.randomUUID().toString();
            Files.writeString(idFile, fresh, StandardCharsets.UTF_8);
            cachedId = fresh;
            return cachedId;
        } catch (IOException e) {
            // 磁盘异常时退化为进程内临时 ID，不影响业务
            log.warn("install-id 读写失败，使用临时 ID: {}", e.toString());
            cachedId = UUID.randomUUID().toString();
            return cachedId;
        }
    }

    /**
     * 会话关联键：SHA-256(installSecret || conversationId) 前 16 位十六进制。
     * 同一会话稳定、跨安装不可关联、无法反推原始 conversationId。
     */
    public String convKey(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(secret());
            md.update(conversationId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized byte[] secret() throws IOException {
        if (cachedSecret != null) return cachedSecret;
        Files.createDirectories(secretFile.getParent());
        if (Files.exists(secretFile)) {
            byte[] v = Files.readAllBytes(secretFile);
            if (v.length >= 16) {
                cachedSecret = v;
                return cachedSecret;
            }
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        Files.write(secretFile, fresh);
        try {
            Files.setPosixFilePermissions(secretFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows 无 POSIX 权限
        }
        cachedSecret = fresh;
        return cachedSecret;
    }
}
