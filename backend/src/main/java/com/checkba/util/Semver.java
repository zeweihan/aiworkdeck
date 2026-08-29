package com.checkba.util;

/**
 * 语义化版本的最小比较工具（插件规范 v2.7 P0：manifest.minHostVersion）。
 *
 * <p>仓里另有两份包私有的同逻辑拷贝（NativePackService / PluginMarketService），
 * 属既有代码不动；新代码一律用这里，两份旧拷贝的收敛是独立清理任务。
 */
public final class Semver {

    private Semver() {
    }

    /** 是否形如 {@code 主.次[.补丁]} 的版本号（允许尾随后缀，如 0.28.0-beta）。 */
    public static boolean isSemver(String v) {
        return v != null && v.matches("^\\d+\\.\\d+(\\.\\d+)?.*$");
    }

    /** 比较前三段数字；非数字段按 0 处理。返回负/零/正。 */
    public static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int na = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int nb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
