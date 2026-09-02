package com.checkba.service.ai.mcp;

import java.util.Map;

/**
 * MCP 传输协议提供方接口：协议细节（请求编码、响应解析）全部封装在实现内，
 * 上层（McpClientService / 各工具类）只面向「服务器名 + 工具名 + 参数」。
 *
 * 新增一种传输协议 = 新增一个实现类并注册为 Spring Bean，无需改动调用方。
 */
public interface McpProvider {

    /**
     * 「这台机器上没有这个 server 的凭证」的固定判据串（后面接 server 名）。
     *
     * <p>凭证为空时<b>一个字节都不发出去</b>：发一个空 Bearer 换回来的是上游的
     * {@code 401 900902 Missing Credentials}，用户看到的是一句像故障的报错，
     * 而真相是「这台机器本来就没配这项凭证」（打包态的桌面端恒如此）。
     * 上层据此把提示写成「未配置」而不是「本次不可用」——dev-board#395。
     */
    String NO_CREDENTIAL_PREFIX = "Error: MCP server has no credential: ";

    /** 传输协议标识，与 {@link McpProperties.ServerConfig#getTransport()} 匹配 */
    String transport();

    /**
     * 调用远端 MCP 工具。
     *
     * @param server   服务器配置
     * @param token    已解析的认证 token（系统设置覆盖已在上层完成）
     * @param toolName 远端工具名
     * @param args     工具入参
     * @return 给 LLM 消费的文本结果；失败时返回以 "Error" 开头的描述文本（不抛异常）
     */
    String callTool(McpProperties.ServerConfig server, String token, String toolName, Map<String, Object> args);
}
