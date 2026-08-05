package com.checkba.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 单机免登模式的回环绑定强校验（2026-08 安全审计不变式：免登必须钉死回环）。
 *
 * security.local-mode=true 时所有请求都会被解析为「本机用户」，等于零鉴权。
 * 这只有在后端仅监听回环地址、外部机器根本连不上时才是安全的。
 * 因此这里在启动期硬性校验：local-mode 开启但 server.address 不是
 * 127.0.0.1/localhost（含未设置，此时 Spring 默认监听 0.0.0.0）时，直接拒绝启动。
 */
@Component
public class LocalModeLoopbackGuard {

    public LocalModeLoopbackGuard(
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${server.address:}") String serverAddress) {
        validate(localMode, serverAddress);
    }

    static void validate(boolean localMode, String serverAddress) {
        if (!localMode) return;
        String addr = serverAddress == null ? "" : serverAddress.trim();
        if ("127.0.0.1".equals(addr) || "localhost".equalsIgnoreCase(addr)) return;
        throw new IllegalStateException(
                "security.local-mode=true 时 server.address 必须为 127.0.0.1 或 localhost（当前："
                        + (addr.isEmpty() ? "未设置，将监听 0.0.0.0" : addr)
                        + "）。免登模式必须钉死回环，拒绝启动。");
    }
}
