package com.checkba.service.site;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code ~/.aiworkdeck/site.json} 的读写。
 *
 * <p><b>刻意不依赖 Spring、不依赖 Jackson</b>：这个文件要被
 * {@link SiteEnvironmentPostProcessor} 在容器启动之前读一次（那时没有任何 bean，
 * 也不该为了读一个字段把 ObjectMapper 拽进 Environment 阶段），
 * 再被 {@link SiteProfileService} 在运行期读写一次。两处共用这一份实现。
 *
 * <p>站点是**机器级**状态，与 license.json / account.json 同目录同规格。刻意不进数据库：
 * {@code local.identity.selectedUserId} 进数据库是因为它存的是指向同一个库里 user 表的外键，
 * 必须与数据同生共死；站点描述的是「这台机器面向哪个商业实体」，还原旧库不该把站点还原掉。
 *
 * <p>文件形态（字段少且固定，手写解析比引依赖更稳）：
 * <pre>{ "site": "cn", "chosenAt": "2026-08-08T00:00:00Z", "chosenBy": "user" }</pre>
 */
public final class SiteStateFile {

    public static final String FILE_NAME = "site.json";

    /** 站点 id 的合法形态。用它挡住手改文件塞进来的路径穿越与注入。 */
    private static final Pattern SITE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /**
     * @param site     站点 id；{@code null} = 文件不存在或不可解析
     * @param chosenAt ISO 8601
     * @param chosenBy {@code user}（用户显式选择）/ {@code default}
     */
    public record State(String site, String chosenAt, String chosenBy) {
        public static State empty() {
            return new State(null, null, null);
        }
    }

    private SiteStateFile() {
    }

    /** 读取，任何异常都按「未选择」处理——站点文件坏掉不该让应用起不来。 */
    public static State read(Path stateDir) {
        try {
            Path file = stateDir.resolve(FILE_NAME);
            if (!Files.exists(file)) return State.empty();
            String json = Files.readString(file);
            String site = extract(json, "site");
            if (site == null) return State.empty();
            String normalized = site.toLowerCase(Locale.ROOT);
            if (!SITE_ID.matcher(normalized).matches()) return State.empty();
            return new State(normalized, extract(json, "chosenAt"), extract(json, "chosenBy"));
        } catch (Exception e) {
            return State.empty();
        }
    }

    /** 写入。这里没有凭据，权限收敛沿用同目录规格（0600）只是保持一致，不是必需。 */
    public static void write(Path stateDir, State state) throws Exception {
        Files.createDirectories(stateDir);
        String json = "{\n"
                + "  \"site\": " + quote(state.site()) + ",\n"
                + "  \"chosenAt\": " + quote(state.chosenAt()) + ",\n"
                + "  \"chosenBy\": " + quote(state.chosenBy()) + "\n"
                + "}\n";
        Files.writeString(stateDir.resolve(FILE_NAME), json);
    }

    /**
     * 取顶层字符串字段。字段值全部是我们自己写的 ASCII 标识与时间戳，
     * 不需要处理转义——真遇到含引号的值直接判为不可解析，回落「未选择」。
     */
    private static String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        String value = json.substring(open + 1, close);
        return value.isBlank() ? null : value;
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
