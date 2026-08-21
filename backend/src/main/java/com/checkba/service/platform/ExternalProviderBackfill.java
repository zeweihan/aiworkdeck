package com.checkba.service.platform;

import com.checkba.service.SystemSettingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 存量安装的档位回填。<b>与档位框架必须同批合入</b>——框架一上线，
 * 存量用户就已经被静默切走了。
 *
 * <h3>不回填会发生什么</h3>
 * {@code SystemSettingService.get(key, default)} 只在 {@code system_setting} 行不存在时
 * 才回落默认值。存量库里根本没有 {@code external.<service>.provider} 这一行，
 * 于是升级后一律取新默认值 platform——而用户填过的 {@code external.qichacha.key} /
 * {@code external.aliyunOcr.*} / {@code external.pkulaw.token} 一个都没丢，
 * 就在库里躺着却不再被用。两类用户同时被搞坏：
 *
 * <ul>
 *   <li>自带阿里云 OCR / Tushare 订阅的律所：同一次调用从「花已经付过的订阅」变成
 *       「扣 Credits」，<b>为同一项服务付两遍钱</b>，且没有任何提示。</li>
 *   <li>从未连过账户的存量用户：OCR 与搜索从「昨天好好的」变成「余额不足」，
 *       他的结论是新版本弄坏了功能。</li>
 * </ul>
 *
 * <h3>做法</h3>
 * 照 {@code DataInitializer} 写 {@code system.wizard.completed} 的形态：
 * <b>只在该行不存在时</b>写一次。已有非空 BYOK 凭证 → 显式写 {@code byok}（写库，不靠默认值）；
 * 完全空配置的服务才写 {@code platform}。
 *
 * <p>全新安装因此天然落到全 platform（新库里一个凭证都没有），零配置目标不受影响。
 */
@Service
@Slf4j
public class ExternalProviderBackfill {

    private final SystemSettingService systemSettingService;
    private final ExternalProviderResolver resolver;

    /**
     * 各 BYOK 凭证键的 yml/env 默认值。
     *
     * <p>必须一起看，不能只查 DB：团队服务器常用环境变量注入
     * （{@code QICHACHA_KEY}、{@code PKULAW_TOKEN}…），那些值不在 {@code system_setting} 里，
     * 只查库会把一台配好的团队服务器判成「空配置」并切到 platform，
     * 而团队服务器上平台档恒不可用（D5），结果是功能直接消失。
     */
    private final Map<String, String> injectedDefaults = new HashMap<>();

    public ExternalProviderBackfill(
            SystemSettingService systemSettingService,
            ExternalProviderResolver resolver,
            @Value("${bocha.api.key:}") String bochaKey,
            @Value("${external.aliyun-ocr.access-key-id:}") String ocrAk,
            @Value("${external.aliyun-ocr.access-key-secret:}") String ocrSk,
            @Value("${meeting.asr.access-key-id:}") String asrAk,
            @Value("${meeting.asr.app-key:}") String asrAppKey,
            @Value("${meeting.oss.bucket:}") String ossBucket,
            @Value("${external.qichacha.key:}") String qichachaKey,
            @Value("${external.qichacha.secret:}") String qichachaSecret,
            @Value("${external.tushare.token:}") String tushareToken,
            @Value("${external.pkulaw.token:}") String pkulawToken) {
        this.systemSettingService = systemSettingService;
        this.resolver = resolver;
        injectedDefaults.put("external.bocha.apiKey", bochaKey);
        injectedDefaults.put("external.aliyunOcr.accessKeyId", ocrAk);
        injectedDefaults.put("external.aliyunOcr.accessKeySecret", ocrSk);
        injectedDefaults.put("meeting.asr.access-key-id", asrAk);
        injectedDefaults.put("meeting.asr.app-key", asrAppKey);
        injectedDefaults.put("meeting.oss.bucket", ossBucket);
        injectedDefaults.put("external.qichacha.key", qichachaKey);
        injectedDefaults.put("external.qichacha.secret", qichachaSecret);
        injectedDefaults.put("external.tushare.token", tushareToken);
        injectedDefaults.put("external.pkulaw.token", pkulawToken);
    }

    /**
     * 刻意用 {@code @PostConstruct} 而不是 {@code ApplicationReadyEvent}：内嵌 Tomcat
     * 在 {@code refresh()} 收尾（{@code finishRefresh -> startWebServer}）就已经开始收
     * 连接，而 {@code ApplicationReadyEvent} 要等 {@code refresh()} 返回、
     * {@code CommandLineRunner} 跑完才发出——只挂后者，升级后第一批落在这个窗口里的
     * 请求会撞见还没回填的默认档位（见类注释）。{@code @PostConstruct} 在本 bean
     * 的依赖（{@code SystemSettingService}/{@code ExternalProviderResolver}）已经
     * 就绪之后、且必然早于 {@code finishRefresh()} 执行，能把这个窗口关掉。
     */
    @PostConstruct
    void onStartup() {
        try {
            int written = backfill();
            if (written > 0) {
                log.info("外部服务档位回填完成，写入 {} 项", written);
            }
        } catch (Exception e) {
            // 回填失败不能拖垮启动：最坏情况是用户在设置页手动切一次档。
            log.warn("外部服务档位回填失败（不影响启动）: {}", e.toString());
        }
    }

    /** @return 本次写入的行数 */
    public int backfill() {
        int written = 0;
        for (ExternalServiceProvider.Descriptor d : ExternalServiceProvider.ALL) {
            if (resolver.hasExplicitSetting(d.service())) continue;

            ExternalServiceProvider mode = decide(d);
            systemSettingService.set(ExternalProviderResolver.providerKey(d.service()), mode.settingValue());
            written++;
            if (mode == ExternalServiceProvider.BYOK) {
                log.info("服务 {} 已有自备凭证，档位回填为 byok（不切到平台代采）", d.service());
            }
        }
        return written;
    }

    /**
     * 判定一个没写过档位的服务该落哪一档：已有非空 BYOK 凭证 → byok
     * （<b>宁可保守</b>，切错方向会花用户的钱）；都没有 → platform。
     *
     * <p>曾经还有一步「先看档位自身的 yml/env 默认值，且<b>只有 LOCAL 算显式偏好</b>」，
     * 那一步只为 TTS 存在（打包态注入 {@code EXTERNAL_TTS_PROVIDER=local}）。
     * 语音合成移除云端档之后不再有服务需要它，连同那个注入一起删掉了。
     *
     * <p><b>将来若有服务再引入 env 级档位默认值，这一步要连同它的两条教训一起加回来</b>：
     * ① 不加这一步，打包态注入的 local 会被凭证推断盖成 platform，本地引擎当场失效；
     * ② 加了也只能认 LOCAL——`${EXTERNAL_TTS_PROVIDER:elevenlabs}` 里那个 elevenlabs 是
     * 历史默认值而非任何人做过的选择，把它当偏好会让每一台全新安装都落到 byok，
     * 而用户根本没有那把 Key，零配置目标当场落空。
     */
    private ExternalServiceProvider decide(ExternalServiceProvider.Descriptor d) {
        return hasByokCredentials(d) ? ExternalServiceProvider.BYOK : ExternalServiceProvider.PLATFORM;
    }

    /** 任一关键凭证非空即算「用户已经自备了 Key」。 */
    private boolean hasByokCredentials(ExternalServiceProvider.Descriptor d) {
        for (String key : d.byokCredentialKeys()) {
            String fromDb = systemSettingService.get(key, null);
            if (fromDb != null && !fromDb.isBlank()) return true;
            String injected = injectedDefaults.get(key);
            if (injected != null && !injected.isBlank()) return true;
        }
        return false;
    }
}
