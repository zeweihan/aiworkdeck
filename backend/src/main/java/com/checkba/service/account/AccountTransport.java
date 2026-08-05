package com.checkba.service.account;

/**
 * 出站 HTTP 缝（seam）：把「怎么发请求」从 {@link AccountService} 里剥出来，
 * 单测可以打桩返回任意状态码/报文，不必真的起一个 HTTP 服务或依赖网络。
 *
 * 生产实现 {@link HttpAccountTransport} 用 JDK HttpClient，5 秒超时。
 */
public interface AccountTransport {

    /** 出站响应。status &lt; 0 表示请求根本没发出去（连接失败/超时）。 */
    record Reply(int status, String body) {
        public static final int NETWORK_FAILURE = -1;

        public boolean networkFailure() {
            return status < 0;
        }
    }

    /**
     * @param method     GET / POST
     * @param url        绝对地址
     * @param bearerKey  awdk_ 账户 Key，null 表示不带 Authorization
     * @param jsonBody   请求体，null 表示无体
     */
    Reply send(String method, String url, String bearerKey, String jsonBody);
}
