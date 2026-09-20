// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 「这个外部服务此刻能不能用」的单一判据（dev-board#750），供工具可见性使用。
 *
 * <p>存在的理由：平台代采档下外部服务全部经 {@link PlatformGatewayClient} 出去，没连账户时
 * 每一次调用都必然回一句「尚未连接 AI WorkDeck 账户」。而工具规格是照常下发的，于是模型
 * 会认认真真地去调——实测一条纯法律问答跑了 4 个 LLM 往返，其中 law_search 与 search_web
 * 两轮完全是浪费，每轮 3~5 秒。模型看不见这些工具就不会试。
 *
 * <p><b>只判「平台档 + 没连账户」这一种</b>：那是唯一一个我们在工具分发前就能确定
 * 「打了也是白打」的状态。BYOK / LOCAL 档的可用性取决于各自的凭证（yml、system_setting、
 * .env 三处兜底，判据分散在每个 service 内部），在这里猜必然出错，而猜错的方向是
 * <b>把能用的工具藏掉</b>——那比失败一次严重得多。所以那两档一律当可用。
 *
 * <p>注意非 local-mode（云后端）下 {@link ExternalProviderResolver#resolve} 恒不返回 PLATFORM，
 * 所以这道闸在云上天然是空操作；它只对桌面端生效，而桌面端正是「用户还没连账户」的常态。
 */
@Service
@RequiredArgsConstructor
public class ExternalServiceAvailability {

    private final ExternalProviderResolver externalProviderResolver;
    private final PlatformGatewayClient platformGatewayClient;

    /**
     * @param service {@link ExternalServiceProvider} 里的服务名常量（search / pkulaw / qichacha …）
     * @return false 只在「这个服务当前走平台网关，而本机没连账户」时出现；其余一律 true
     */
    public boolean usable(String service) {
        try {
            if (externalProviderResolver.resolve(service) != ExternalServiceProvider.PLATFORM) {
                return true;
            }
            return platformGatewayClient.connected();
        } catch (RuntimeException e) {
            // 判不出来就当可用：藏掉一个能用的工具，表现是「这个能力整个不存在」
            return true;
        }
    }
}
