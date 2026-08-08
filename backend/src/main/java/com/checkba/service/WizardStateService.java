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
     * 是否处于「全新安装的匿名向导窗口」内：标记从未写过且库里没有任何配置。
     *
     * <p>与 {@link #isInitialized()} 不是简单取反——管理员 reset 打开的窗口
     * （标记 = "false"）里 {@code isInitialized()} 也是 false，但那个窗口
     * **必须带管理员会话**，不属于匿名窗口。
     */
    public boolean inAnonymousSetupWindow() {
        return systemSettingService.get(KEY_WIZARD_COMPLETED, null) == null
                && systemSettingRepository.count() == 0;
    }
}
