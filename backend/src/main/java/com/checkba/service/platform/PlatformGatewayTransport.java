package com.checkba.service.platform;

/**
 * 网关的出站 HTTP 缝（seam）。与 {@code AccountTransport} 同一形态：把「怎么发请求」
 * 剥出来，单测可以打桩返回任意状态码/报文，不必真的起 HTTP 服务或依赖网络。
 *
 * <p><b>刻意不复用 AccountTransport</b>：那个实现把超时写死成 5 秒
 * （{@code HttpAccountTransport.TIMEOUT}），而它服务的都是几 KB 的账户 JSON 往返。
 * 网关这边 OCR、TTS、听悟建任务超过 5 秒是常态，沿用等于给每一次正常调用
 * 都加一个「客户端超时但服务端已扣费」的窗口——那正是幂等键要防的事，
 * 没必要先自己制造它。
 */
public interface PlatformGatewayTransport {

    /** 出站响应。status &lt; 0 表示请求根本没发出去（连接失败/超时）。 */
    record Reply(int status, String body) {
        public static final int NETWORK_FAILURE = -1;

        public boolean networkFailure() {
            return status < 0;
        }
    }

    /**
     * @param method         GET / POST
     * @param url            绝对地址
     * @param bearerKey      awdk_ 账户 Key，null 表示不带 Authorization
     * @param idempotencyKey 幂等键，null 表示不带（只有不扣费的端点才允许 null）
     * @param jsonBody       请求体，null 表示无体
     * @param timeoutSeconds 本次调用的响应超时
     */
    Reply send(String method, String url, String bearerKey, String idempotencyKey,
               String jsonBody, int timeoutSeconds);
}
