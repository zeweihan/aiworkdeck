// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    /** multipart 里的那一个文件部件（头像上传只有一个，没必要做成通用多部件）。 */
    record Multipart(String fieldName, String filename, String contentType, byte[] content) {}

    /**
     * multipart/form-data 出站（当前只有头像上传一处）。
     *
     * <p>默认实现直接抛：绝大多数打桩 transport 只关心 JSON 那条路，给个默认值
     * （比如返回 200）会让「桩根本没接这条路」在测试里表现成一次成功的上传。
     */
    default Reply sendMultipart(String method, String url, String bearerKey, Multipart part) {
        throw new UnsupportedOperationException("本 transport 不支持 multipart 出站");
    }
}
