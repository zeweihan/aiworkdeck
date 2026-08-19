package com.checkba.service.pack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生资源包（native pack）配置（规范见 docs/NATIVE_PACK_DISTRIBUTION.md §3.2）。
 *
 * 配置前缀：ai.packs
 */
@Component
@ConfigurationProperties(prefix = "ai.packs")
public class PackProperties {

    /**
     * 资源包落盘目录（相对服务端工作目录，惯例同 ai.plugins.dir）。
     * 打包桌面版的后端 cwd 是用户数据目录，所以它落在 ~/.aiworkdeck/packs/。
     */
    private String dir = "packs";

    /**
     * 下载源，按序降级（镜像必须排在 GitHub 之前：境内直连 GitHub 实测 12 KB/s，
     * 几十 MB 的包不能指望它当主源）。每个 base 拼 {@code /<id>/manifest.json} 与
     * {@code /<id>/<version>/<archive>}。
     */
    private List<String> baseUrls = new ArrayList<>(List.of(
            "https://www.aiworkdeck.com/plugin-packs",
            "https://workdeck.ai/plugin-packs"));

    /** 总开关。置 false 时安装、自动补下载、封禁同步全部旁路（离线部署形态用）。 */
    private boolean enabled = true;

    /**
     * 平台封禁表地址（形制同插件封禁表，见规范 §8.4）。
     * 端点 404 / 不可达时静默跳过——官网未部署时不该刷错误日志。
     */
    private String revokedUrl = "https://www.aiworkdeck.com/api/registry/packs/revoked";

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public List<String> getBaseUrls() { return baseUrls; }
    public void setBaseUrls(List<String> baseUrls) { this.baseUrls = baseUrls; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRevokedUrl() { return revokedUrl; }
    public void setRevokedUrl(String revokedUrl) { this.revokedUrl = revokedUrl; }
}
