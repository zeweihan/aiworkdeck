// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.capability;

import com.checkba.service.LangText;
import com.checkba.util.ProductIdentity;
import com.checkba.util.SsrfGuard;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 GitHub 拉一份源码到本机临时目录（能力包安装的第一段）。
 *
 * <p>这是全系统<b>第一条「从任意 URL 取代码到宿主机」的路径</b>，所以闸开得很窄：
 * <ul>
 *   <li>只收 {@code https://github.com/<owner>/<repo>[/tree/<ref>]}，别的形态一律拒；</li>
 *   <li>实际出站地址（codeload）过 {@link SsrfGuard}，每一跳重定向重新过一次
 *       （只跟随 github/codeload 域，且最多 3 跳）；</li>
 *   <li>限额与 {@code PluginDevService} 同口径：200 文件 / 单文件 5MB / 总量 20MB，
 *       压缩包本体也按 20MB 截断；</li>
 *   <li>解包逐条目拒 symlink / hardlink / 绝对路径 / {@code ..}（形制同
 *       {@code NativePackService.extract}）。</li>
 * </ul>
 *
 * <p><b>下载被抽成 {@link Downloader}</b>：单测注入本地 fixture tar.gz，永远不上网。
 */
@Service
@Slf4j
public class CapabilitySourceFetchService {

    static final int MAX_FILES = 200;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    /** 只认这一种形态。ref 允许带斜杠（分支名 feature/x），但不允许 .. 与查询串。 */
    private static final Pattern GITHUB_REPO = Pattern.compile(
            "^https://github\\.com/([A-Za-z0-9][A-Za-z0-9._-]{0,38})/([A-Za-z0-9][A-Za-z0-9._-]{0,99}?)(?:\\.git)?"
                    + "(?:/tree/([A-Za-z0-9][A-Za-z0-9._/-]{0,99}))?/?$");

    /** 出站下载器。生产是 HTTPS GET；单测注入本地 fixture。 */
    public interface Downloader {
        byte[] get(String url) throws IOException;
    }

    private Downloader downloader = CapabilitySourceFetchService::httpGet;

    /** 仅供测试注入本地 fixture（生产没有调用点）。 */
    public void setDownloader(Downloader downloader) {
        this.downloader = downloader;
    }

    /**
     * 拉取结果。{@code commit} 取自 tarball 顶层目录名的后缀（GitHub 打包时用的是
     * {@code <repo>-<sha 或 ref>}），拿不到就是空串——它只用于展示与审计，不参与判定。
     */
    public record Source(String owner, String repo, String ref, String commit,
                         Path dir, List<String> files, long totalBytes) {
    }

    /** 解析 URL -> 下载 tarball -> 解包到临时目录。失败一律 IllegalArgumentException（可读原因）。 */
    public Source fetch(String url) {
        Matcher m = GITHUB_REPO.matcher(url == null ? "" : url.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(LangText.of(
                    "只支持 GitHub 仓库链接（https://github.com/<owner>/<repo> 或 .../tree/<分支或标签>），当前: " + url,
                    "Only GitHub repository links are supported (https://github.com/<owner>/<repo>[/tree/<ref>]), got: " + url));
        }
        String owner = m.group(1);
        String repo = m.group(2);
        String ref = m.group(3) == null || m.group(3).isBlank() ? "HEAD" : m.group(3);
        if (ref.contains("..")) {
            throw new IllegalArgumentException(LangText.of("非法的分支/标签名: ", "Invalid ref: ") + ref);
        }

        String tarUrl = "https://codeload.github.com/" + owner + "/" + repo + "/tar.gz/" + ref;
        byte[] archive;
        try {
            archive = downloader.get(tarUrl);
        } catch (IOException e) {
            throw new IllegalArgumentException(LangText.of("下载失败: ", "Download failed: ") + e.getMessage(), e);
        }
        if (archive == null || archive.length == 0) {
            throw new IllegalArgumentException(LangText.of(
                    "仓库为空或不存在（也可能是私有仓库）: " + owner + "/" + repo,
                    "Repository is empty, missing, or private: " + owner + "/" + repo));
        }

        Path dir;
        try {
            dir = Files.createTempDirectory("awd-capability-");
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("创建临时目录失败: ", "Failed to create temp dir: ") + e.getMessage(), e);
        }
        Extracted extracted;
        try {
            extracted = extract(archive, dir);
        } catch (RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
        log.info("能力包源码已拉取: {}/{}@{} files={} bytes={}", owner, repo, ref,
                extracted.files.size(), extracted.total);
        return new Source(owner, repo, ref, extracted.commit, dir, extracted.files, extracted.total);
    }

    /** 删除拉取产生的临时目录（安装完成或计划过期时调）。 */
    public static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败无害，不值得中断调用方
                }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private record Extracted(List<String> files, long total, String commit) {
    }

    /** 解包 tar.gz，剥掉 GitHub 的顶层 {@code <repo>-<ref>/} 目录。 */
    private Extracted extract(byte[] archive, Path destDir) {
        List<String> files = new ArrayList<>();
        long total = 0;
        String topDir = null;
        try (TarArchiveInputStream tin = new TarArchiveInputStream(
                new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
            Path canonicalDest = destDir.toRealPath();
            TarArchiveEntry e;
            while ((e = tin.getNextEntry()) != null) {
                if (e.isSymbolicLink() || e.isLink()) {
                    throw new IllegalArgumentException(LangText.of(
                            "压缩包含链接条目，拒绝解压: ", "Archive contains a link entry; refused: ") + e.getName());
                }
                String name = e.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new IllegalArgumentException(LangText.of(
                            "压缩包含非法路径，拒绝解压: ", "Archive contains an unsafe path; refused: ") + name);
                }
                int slash = name.indexOf('/');
                if (topDir == null && slash > 0) {
                    topDir = name.substring(0, slash);
                }
                // 剥掉顶层目录；顶层目录本身与包外条目跳过
                String rel = slash < 0 ? "" : name.substring(slash + 1);
                if (rel.isBlank()) {
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(canonicalDest.resolve(rel).normalize());
                    continue;
                }
                if (!e.isFile()) {
                    continue;
                }
                if (files.size() >= MAX_FILES) {
                    throw new IllegalArgumentException(LangText.of(
                            "文件数超限（最多 " + MAX_FILES + " 个）", "Too many files (max " + MAX_FILES + ")"));
                }
                long size = Math.max(e.getSize(), 0);
                if (size > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException(LangText.of(
                            "单个文件超过 5MB 上限: ", "File exceeds the 5MB limit: ") + rel);
                }
                total += size;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException(LangText.of(
                            "总体积超过 20MB 上限", "Total size exceeds the 20MB limit"));
                }
                Path dest = canonicalDest.resolve(rel).normalize();
                if (!dest.startsWith(canonicalDest)) {
                    throw new IllegalArgumentException(LangText.of(
                            "压缩包路径越界，拒绝解压: ", "Archive path escapes the target dir; refused: ") + name);
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream out = Files.newOutputStream(dest)) {
                    tin.transferTo(out);
                }
                files.add(rel);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(LangText.of("解包失败: ", "Extraction failed: ") + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("压缩包里没有文件", "Archive contains no files"));
        }
        String commit = "";
        if (topDir != null) {
            int dash = topDir.lastIndexOf('-');
            commit = dash >= 0 ? topDir.substring(dash + 1) : topDir;
        }
        return new Extracted(files, total, commit);
    }

    /** HTTPS GET，手动跟随重定向并逐跳过 SsrfGuard；响应体按 20MB 截断。 */
    private static byte[] httpGet(String url) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String blocked = SsrfGuard.rejectIfBlocked(current);
            if (blocked != null) {
                throw new IOException(blocked);
            }
            if (!current.startsWith("https://")) {
                throw new IOException("refusing non-https redirect: " + current);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(current))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", ProductIdentity.userAgent("capability-installer"))
                    .GET().build();
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                String location = resp.headers().firstValue("location").orElse(null);
                resp.body().close();
                if (location == null) {
                    throw new IOException("redirect without location, status " + code);
                }
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (code != 200) {
                resp.body().close();
                throw new IOException("HTTP " + code + " for " + current);
            }
            try (InputStream in = resp.body()) {
                return readBounded(in, MAX_TOTAL_BYTES);
            }
        }
        throw new IOException("too many redirects for " + url);
    }

    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("download exceeds the " + (limit / 1024 / 1024) + "MB limit");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
