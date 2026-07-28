package com.checkba.version;

/** 版本记录相关异常。调用方一律捕获后降级，不得阻断主流程。 */
public class VersionException extends RuntimeException {
    public VersionException(String message, Throwable cause) { super(message, cause); }
    public VersionException(String message) { super(message); }
}
