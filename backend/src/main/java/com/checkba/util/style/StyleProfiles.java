package com.checkba.util.style;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 画像的解析与 HOUSE 单源。
 *
 * <p>{@code style-profiles/house-default.json} 是律所标准格式（HOUSE）的唯一出处：
 * 后端 {@code DocxStyleHelper}、编辑器 worker、Office 插件三处写端都从这一份派生，
 * 对拍测试（{@code HouseProfileParityTest} 等）替代过去「三处逐字一致」的人工约定。
 * 文件位置与文件名不要动——构建脚本按这个路径复制并做 sha256 对拍。
 */
public final class StyleProfiles {

    public static final String HOUSE_DEFAULT_RESOURCE = "style-profiles/house-default.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile StyleProfile houseDefault;

    private StyleProfiles() {
    }

    /** classpath 上的 HOUSE 画像，缓存一次；返回的是共享实例，调用方不要改它（要改先 merge）。 */
    public static StyleProfile houseDefault() {
        StyleProfile cached = houseDefault;
        if (cached == null) {
            synchronized (StyleProfiles.class) {
                cached = houseDefault;
                if (cached == null) {
                    houseDefault = cached = loadHouseDefault();
                }
            }
        }
        return cached;
    }

    private static StyleProfile loadHouseDefault() {
        try (InputStream in = StyleProfiles.class.getClassLoader().getResourceAsStream(HOUSE_DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("classpath 缺少 " + HOUSE_DEFAULT_RESOURCE);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + HOUSE_DEFAULT_RESOURCE + " 失败", e);
        }
    }

    /** 解析画像 JSON；非对象或解析失败抛 IllegalArgumentException。 */
    public static StyleProfile parse(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("画像 JSON 为空");
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject()) throw new IllegalArgumentException("画像 JSON 必须是对象");
            return new StyleProfile((ObjectNode) n);
        } catch (IOException e) {
            throw new IllegalArgumentException("画像 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static StyleProfile of(ObjectNode node) {
        return new StyleProfile(node);
    }

    public static String toJson(StyleProfile profile) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(profile.root());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
