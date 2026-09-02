package com.checkba.service.legal;

import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.service.ai.mcp.McpResponseParser;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.GatewayException;
import com.checkba.service.platform.PlatformGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 北大法宝检索的<b>双档分发唯一出口</b>（平台代采走网关 / 自备 Key 直连 MCP）。
 *
 * <h3>为什么必须收成一处</h3>
 * 法宝 token 是订阅主体的，<b>我们不会把它下发到每台用户机器</b>——打包态的桌面端
 * {@code PKULAW_TOKEN} 恒为空。谁绕过这里直接打 {@link McpClientService}，
 * 谁就在用户机器上发一个 {@code Authorization: Bearer }（空 token）出去，
 * 换回一句 {@code 401 900902 Missing Credentials}。
 * AI 对话那条（{@code LegalTools}）本来就走网关，依据窗格漏了——dev-board#395 的病灶。
 *
 * <p>平台档下官网持 token 打同一个 MCP 端点，把 JSON-RPC 响应正文原样带回来。
 * <b>解析仍用 {@link McpResponseParser}</b>：两档共用一个解析器，法宝改格式只会改一处。
 *
 * <p>网关失败以 {@link GatewayException} 原样抛出，<b>不在这里压成一句文案</b>——
 * 调用方（对话工具 / 依据窗格）对同一种失败要摆的话术不同，
 * kind 一旦丢掉就再也分不出「余额不足」和「服务未开放」。
 */
@Service
@RequiredArgsConstructor
public class PkulawChannel {

    /** 法宝那几个 MCP server 都配了 60 秒超时，网关这条要留出同样的余量。 */
    public static final int TIMEOUT_SECONDS = 60;

    /** 网关只代采法宝自家的 server；名字对不上的（自建 / 别家案例 MCP）用的是别人的凭证，照旧直连。 */
    private static final String PKULAW_SERVER_PREFIX = "pkulaw";

    private final McpClientService mcpClientService;
    private final ExternalProviderResolver externalProviderResolver;
    private final PlatformGatewayClient platformGatewayClient;

    /**
     * 打一次法宝工具。
     *
     * @param server mcp.servers 里的 server 名；只有法宝自家的 server 才会走网关
     * @return 与 {@link McpClientService#callTool} 同一约定：失败返回 "Error" 前缀的文本
     * @throws GatewayException 平台档下网关的分类失败（余额不足 / 未开放 / 不可达），带 kind
     */
    public String callTool(String server, String tool, Map<String, Object> args) {
        if (!viaPlatform(server)) {
            return mcpClientService.callTool(server, tool, args);
        }
        // 网关的 op 就是法宝的工具名；MCP 端点写死在服务端，客户端点不了别处
        PlatformGatewayClient.Result result =
                platformGatewayClient.call("pkulaw", tool, Map.of("args", args), TIMEOUT_SECONDS);
        return McpResponseParser.parse(result.data().path("raw").asText(""));
    }

    /** 这个 server 这次走不走平台网关。 */
    public boolean viaPlatform(String server) {
        return server != null && server.startsWith(PKULAW_SERVER_PREFIX)
                && externalProviderResolver.resolve(ExternalServiceProvider.PKULAW)
                        == ExternalServiceProvider.PLATFORM;
    }
}
