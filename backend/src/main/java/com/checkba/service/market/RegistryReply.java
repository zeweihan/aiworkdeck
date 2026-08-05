package com.checkba.service.market;

import java.nio.charset.StandardCharsets;

/**
 * 官网注册表的一次 HTTP 响应（状态码 + 原始字节）。
 *
 * 为什么不直接返回 String：付费闸门要求调用方能区分 402（未购买）与其它非 200，
 * 而 402 的响应体是 JSON、插件文件下载的响应体是二进制——两条链路共用一种载体最省事。
 *
 * @param status HTTP 状态码；0 表示请求根本没发出去（网络不可达）
 * @param data   响应体原始字节，可能为 null
 */
public record RegistryReply(int status, byte[] data) {

    public String body() {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }
}
