// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    /**
     * 团队统计的项目短码：HMAC-SHA256(install-secret, "project:" + projectId) 前 16 位十六进制。
     *
     * <p>与 {@link #convKey} 同源同盐，但**用途完全不同**，刻意不合并成一个方法：
     * convKey 服务匿名 telemetry（与账户无关），本方法服务带鉴权的团队统计通道。
     * 两条通道的端点、表、开关全部分离（见 licensing-billing 领域文档「团队通道」一节）。
     *
     * <p>短码不可反推项目 id，也不跨安装可关联（盐只在本机、永不上传）。代价是同一个案件
     * 在两位律师的机器上会得到两个不同的短码——本机项目**没有任何跨机器稳定标识**
     * （版本记录模块没有远端仓 id 的概念，全仓 grep 零命中），所以首期只能按本机 id 哈希。
     * 将来若引入远端仓标识，改成 HMAC(teamId, remoteId) 即可让同一案件聚成一行。
     */
    public String projectKey(Long projectId) {
        if (projectId == null) return null;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret(), "HmacSHA256"));
            byte[] digest = mac.doFinal(("project:" + projectId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
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
