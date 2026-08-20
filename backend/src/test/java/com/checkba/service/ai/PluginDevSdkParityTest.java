package com.checkba.service.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 插件 SDK 的第五份副本守卫：plugin-dev 骨架分发的 classpath 资源
 * {@code plugin-dev/awd-plugin-sdk.js} 必须与源头 {@code sdk/plugin-sdk/awd-plugin-sdk.js}
 * 逐字节一致（既有的三份副本纪律见 plugin-system.md「SDK 有多份副本」地雷；
 * 打包态没有 sdk/ 目录，所以骨架只能带 classpath 副本）。
 * 仓库外跑（无 sdk/ 目录）时跳过而不是假绿。
 */
class PluginDevSdkParityTest {

    @Test
    void classpathSdkCopyMatchesRepoSource() throws Exception {
        Path repoSource = Path.of("..", "sdk", "plugin-sdk", "awd-plugin-sdk.js");
        Assumptions.assumeTrue(Files.isRegularFile(repoSource),
                "sdk/plugin-sdk/awd-plugin-sdk.js 不在（非完整仓库检出），跳过对拍");
        byte[] source = Files.readAllBytes(repoSource);
        byte[] resource = new ClassPathResource("plugin-dev/awd-plugin-sdk.js").getInputStream().readAllBytes();
        assertArrayEquals(source, resource,
                "plugin-dev 骨架分发的 SDK 副本与 sdk/plugin-sdk/awd-plugin-sdk.js 不一致，改 SDK 要同批同步全部副本");
    }
}
