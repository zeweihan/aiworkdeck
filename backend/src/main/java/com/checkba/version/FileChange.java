package com.checkba.version;

public record FileChange(String path, Type type) {
    public enum Type { ADD, MODIFY, DELETE, RENAME }
}
