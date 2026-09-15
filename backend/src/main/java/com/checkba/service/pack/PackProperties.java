// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
     * 自动追新：启动后延迟一次 + 每 24h，对已装且启用的 pack 比对 registry 版本，
     * 有新版就走一遍完整安装事务换上（见 {@link PackUpdater}）。
     *
     * <p>置 false 只关自动升级，手动 {@code POST /api/packs/{id}/upgrade} 与安装不受影响。
     * 关掉它的代价写在 docs/NATIVE_PACK_DISTRIBUTION.md §5.1：应用更新不等于 pack 更新，
     * 新引擎能力会一直缺席。
     */
    private boolean autoUpgrade = true;

    /**
     * 平台封禁表地址（形制同插件封禁表，见规范 §8.4）。
     * 端点 404 / 不可达时静默跳过——官网未部署时不该刷错误日志。
     */
    private String revokedUrl = "https://www.aiworkdeck.com/api/registry/packs/revoked";

    /**
     * 单个压缩包的条目数上限。5000 是 litviz/drawio 时代的值——一个带 torch 的 Python venv
     * 轻松上万文件，四个运行时 pack 会当场被拦下。抬到 150000 的安全前提是 manifest 有
     * Ed25519 签名：能走到解压这一步的字节只可能来自我们自己的构建（规范 §2/§10）。
     */
    private int maxArchiveEntries = 150_000;

    /** 单个压缩包解压后的总体积上限（字节）。默认 2.5 GB：mineru 的 lib 解压后 1.2 GB 量级。 */
    private long maxUnpackedBytes = 2_684_354_560L;

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public List<String> getBaseUrls() { return baseUrls; }
    public void setBaseUrls(List<String> baseUrls) { this.baseUrls = baseUrls; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAutoUpgrade() { return autoUpgrade; }
    public void setAutoUpgrade(boolean autoUpgrade) { this.autoUpgrade = autoUpgrade; }
    public String getRevokedUrl() { return revokedUrl; }
    public void setRevokedUrl(String revokedUrl) { this.revokedUrl = revokedUrl; }
    public int getMaxArchiveEntries() { return maxArchiveEntries; }
    public void setMaxArchiveEntries(int maxArchiveEntries) { this.maxArchiveEntries = maxArchiveEntries; }
    public long getMaxUnpackedBytes() { return maxUnpackedBytes; }
    public void setMaxUnpackedBytes(long maxUnpackedBytes) { this.maxUnpackedBytes = maxUnpackedBytes; }
}
