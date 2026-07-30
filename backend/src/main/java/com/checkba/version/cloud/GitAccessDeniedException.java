package com.checkba.version.cloud;

public class GitAccessDeniedException extends RuntimeException {
    private final int statusCode;
    public GitAccessDeniedException(int statusCode) {
        super("git access denied: " + statusCode);
        this.statusCode = statusCode;
    }
    public int statusCode() { return statusCode; }
}
