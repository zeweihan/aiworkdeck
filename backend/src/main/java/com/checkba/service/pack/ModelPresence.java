// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.pack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 「模型下没下」的只读判定。落盘由 Electron 的 model-manager.js 负责
 * （{@code ~/.aiworkdeck/models/<name>/.aiworkdeck-complete}，写标记是下载成功的最后一步），
 * 后端只需要读一眼——打包态后端 cwd 就是 ~/.aiworkdeck，与 ai.packs.dir 同一套相对路径惯例。
 */
@Component
public class ModelPresence {

    private static final String MARKER = ".aiworkdeck-complete";

    private final Path root;

    public ModelPresence(@Value("${ai.models.dir:models}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** modelId 形如 {@code mineru-models} → 目录 {@code <root>/mineru}。 */
    public boolean installed(String modelId) {
        if (modelId == null) return false;
        String name = modelId.endsWith("-models")
                ? modelId.substring(0, modelId.length() - "-models".length())
                : modelId;
        if (!name.matches("^[a-z0-9-]{1,32}$")) return false; // 兼防路径穿越
        return Files.isRegularFile(root.resolve(name).resolve(MARKER));
    }
}
