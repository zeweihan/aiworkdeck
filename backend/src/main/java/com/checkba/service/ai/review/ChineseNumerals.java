package com.checkba.service.ai.review;

/**
 * 条款编号里的数字解析：中文数字（一、十二、二十一、一百零三）、阿拉伯数字、全角数字。
 * 解析失败返回 -1，调用方按「不是编号」处理。只服务编号场景，上限千位。
 */
final class ChineseNumerals {

    private ChineseNumerals() {
    }

    static int parse(String s) {
        if (s == null || s.isBlank()) return -1;
        String t = s.trim();
        StringBuilder ascii = new StringBuilder();
        boolean allDigits = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                ascii.append(c);
            } else if (c >= '１' && c <= '９') {
                ascii.append((char) ('0' + (c - '０')));
            } else if (c == '０') {
                ascii.append('0');
            } else {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                return Integer.parseInt(ascii.toString());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int result = 0;
        int current = 0;
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int digit = digitOf(c);
            if (digit >= 0) {
                current = digit;
                any = true;
                continue;
            }
            switch (c) {
                case '十' -> {
                    result += (current == 0 ? 1 : current) * 10;
                    current = 0;
                    any = true;
                }
                case '百' -> {
                    result += (current == 0 ? 1 : current) * 100;
                    current = 0;
                    any = true;
                }
                case '千' -> {
                    result += (current == 0 ? 1 : current) * 1000;
                    current = 0;
                    any = true;
                }
                default -> {
                    return -1;
                }
            }
        }
        return any ? result + current : -1;
    }

    private static int digitOf(char c) {
        return switch (c) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两', '兩' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }
}
