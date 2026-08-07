package com.checkba.service.totp;

/**
 * 测试工具：替其他包的测试算出某个密钥在某个时刻的合法 TOTP 码。
 *
 * <p>放在 totp 包下的 test 源码里，是为了不把 {@code TotpService.code} 这类
 * 「凭密钥造码」的能力提升为生产 API——生产代码只需要校验，不需要生成。
 */
public final class TotpTestCodes {

    private TotpTestCodes() {
    }

    /** 当前时间片的合法码。 */
    public static String now(String secret) {
        return at(secret, System.currentTimeMillis() / 1000);
    }

    /** 指定 Unix 秒所在时间片的合法码。 */
    public static String at(String secret, long epochSeconds) {
        return TotpService.code(TotpService.base32Decode(secret),
                epochSeconds / TotpService.STEP.toSeconds(), TotpService.DIGITS);
    }

    /** 相对当前时间片偏移 n 个步长的合法码（n 可为负）。 */
    public static String stepsFromNow(String secret, int steps) {
        return at(secret, System.currentTimeMillis() / 1000 + steps * TotpService.STEP.toSeconds());
    }
}
