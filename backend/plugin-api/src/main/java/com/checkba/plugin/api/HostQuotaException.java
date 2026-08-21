package com.checkba.plugin.api;

/** 每插件每分钟宿主调用次数超限。 */
public class HostQuotaException extends RuntimeException {
    public HostQuotaException(String m) { super(m); }
}
