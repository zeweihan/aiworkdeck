package com.checkba.plugin.api;

/** 标签；type 为 NORMAL / PARTY / ISSUE（null 视同 NORMAL）。 */
public record TagInfo(long id, String name, String type, String color) {}
