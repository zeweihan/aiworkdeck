package com.checkba.service;

import com.checkba.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;

/**
 * 首启向导是否已初始化——**这个判据的唯一定义处**。
 *
 * <p>原先是 {@code WizardController} 的私有方法。抽出来是因为它同时是一条安全前置条件：
 * 向导 POST 在「全新安装」窗口内是匿名可达的（见 WizardController.initialize 的注释），
 * 而向导里的辅助端点（当前是本地 Ollama 探测）必须享有**完全相同**的匿名窗口，
 * 否则「向导里每一条下一步都必须能在向导里做完」这条规则会被打破：
 * server 模式下一台全新未初始化的机器，管理员还没有会话可用，选本地档就永远提交不了。
 *
 * <p>各写一份判据是这类问题的常见死法——两处判据一旦漂移，
 * 要么某个端点在初始化后仍然匿名可达，要么向导里某一步在窗口期反而被拦。
 */
@Service
public class WizardStateService {

    /** 全新安装时由 {@code DataInitializer} 先落一份 "false"，见那里的注释。 */
    public static final String KEY_WIZARD_COMPLETED = "system.wizard.completed";

    /**
     * 「有没有人真的配过 AI」这一行——向导提交与管理后台保存 AI 配置都必写它，
     * 用来区分标记 "false" 的两个来源（全新安装钉的 vs 管理员 reset 打开的）。
     * 与 {@code AdminConfigController.KEY_AI_ACTIVE_PROVIDER} 同一个键。
     */
    private static final String KEY_AI_ACTIVE_PROVIDER = "ai.activeProvider";

    private final SystemSettingService systemSettingService;
    private final SystemSettingRepository systemSettingRepository;

    public WizardStateService(SystemSettingService systemSettingService,
                              SystemSettingRepository systemSettingRepository) {
        this.systemSettingService = systemSettingService;
        this.systemSettingRepository = systemSettingRepository;
    }

    /**
     * completed 标记显式存在时以它为准（reset 后 = "false"，向导重新开放）；
     * 标记不存在的存量部署退回「保存过任何配置即已初始化」兜底，防匿名滥用。
     */
    public boolean isInitialized() {
        String completed = systemSettingService.get(KEY_WIZARD_COMPLETED, null);
        if (completed != null) {
            return Boolean.parseBoolean(completed);
        }
        return systemSettingRepository.count() > 0;
    }

    /**
     * 是否处于「全新安装的匿名向导窗口」内。
     *
     * <p>与 {@link #isInitialized()} 不是简单取反——管理员 reset 打开的窗口
     * （标记 = "false"）里 {@code isInitialized()} 也是 false，但那个窗口
     * **必须带管理员会话**，不属于匿名窗口。
     *
     * <p>判据不能写成「标记从未写过」：全新安装（非单机模式）启动时
     * {@code DataInitializer} 就把标记钉成了 "false"，标记从此再也不是 null，
     * 于是每一台全新机器上向导里的本地 Ollama / 本地 ASR 探测都会 401
     * ——正好是本类注释说要防的那种死法。真正的分界是**有没有人配过 AI**：
     * 向导提交与管理后台保存都会写 {@code ai.activeProvider}，
     * 所以 reset 打开的窗口里这一行必然在，全新安装则必然没有。
     */
    public boolean inAnonymousSetupWindow() {
        String completed = systemSettingService.get(KEY_WIZARD_COMPLETED, null);
        if (completed == null) {
            // 存量部署：标记从未写过，只有空库才认全新安装（沿用原兜底，防匿名滥用）
            return systemSettingRepository.count() == 0;
        }
        if (Boolean.parseBoolean(completed)) {
            return false;
        }
        return systemSettingService.get(KEY_AI_ACTIVE_PROVIDER, null) == null;
    }
}
