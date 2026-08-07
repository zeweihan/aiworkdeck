package com.checkba.service.sms;

/**
 * 短信网关的出站 HTTP 缝：单测打桩不依赖网络（与 account 包的 AccountTransport 同一模式）。
 */
public interface SmsTransport {

    /** 对 url 发送 application/x-www-form-urlencoded POST，返回状态码与响应体。 */
    default Reply postForm(String url, String formBody) {
        return postForm(url, formBody, null);
    }

    /** 同上，附带 Authorization 头（Twilio 走 Basic；阿里云在请求体里签名，传 null）。 */
    Reply postForm(String url, String formBody, String authorization);

    record Reply(int status, String body) {
    }
}
