package com.checkba.service;

/**
 * 静态语言文案桥：非 Spring 托管的产文案点（enum、new 出来的 handler、静态方法）
 * 经它按应用语言二选一取用户可见文案，Spring bean 也可直接用它避免构造器扩散。
 *
 * <p>指针由 AppLanguageService 的 @PostConstruct 登记——只登记 Spring 容器里的那个实例，
 * 单测里 new 出来的不登记，避免跨测试类污染静态状态。未登记（启动早期 / 纯单测）或取值
 * 抛异常一律回退中文，与存量行为逐字节一致（v0.15.0 static 指针顺序的教训：静态指针必须
 * null 安全回退，不搞全局监听器）。
 */
public final class LangText {

    private static volatile AppLanguageService languageService;

    private LangText() {
    }

    /** 由 AppLanguageService @PostConstruct 调用；测试可显式登记后在收尾 reset()。 */
    public static void register(AppLanguageService service) {
        languageService = service;
    }

    /** 测试收尾用：清掉登记，回到「未初始化=中文」的默认态。 */
    public static void reset() {
        languageService = null;
    }

    public static boolean isEnglish() {
        AppLanguageService svc = languageService;
        if (svc == null) return false;
        try {
            return svc.isEnglish();
        } catch (Exception e) {
            // 语言取不到时宁可回退中文，也不能让一条进度文案把整轮对话带崩
            return false;
        }
    }

    /** 按应用语言二选一：zh-CN（含未初始化/异常回退）取 zh，en-US 取 en。 */
    public static String of(String zh, String en) {
        return isEnglish() ? en : zh;
    }
}
