// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.AddinGitRepoLink;
import com.checkba.repository.AddinGitRepoLinkRepository;
import com.checkba.service.addin.GitProviderClient;
import com.checkba.service.addin.GitTokenCipher;
import com.checkba.service.file.ProjectFileTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关联的 GitHub / Gitee 仓库里的文件（dev-board#720），ref 形如 {@code git:<linkId>:<path>}
 * （path 可含 {@code :}，只按第一个 {@code :} 切）。只读（D 决策）。
 *
 * <p>关联按「人 + 云端项目」查：令牌是某个人的，同项目的另一个成员看不见这一行，
 * 也就借不走这把令牌。别人的 linkId 抄过来也读不到，且与「没有这个文件」同一句话，
 * 不回显别人仓库的存在。
 *
 * <p>失败原因落在 {@link AddinGitRepoLink#getLastError()} 上（面板常驻显示）：
 * 令牌失效如果只在这一轮对话里说一句，用户下次看到的就只是 AI 反复「找不到文件」。
 *
 * <p>红线：令牌明文只在调用瞬间存在，不回显、不写日志；正文只在内存里过一遍。
 */
@Component
@Slf4j
public class GitProviderSource implements RefSource {

    static final String NOT_FOUND = "仓库里没有这个文件，或这条关联不属于你。请用 ref_list 重新查找。";
    static final String AUTH_FAILED = "git 仓库授权失效，请在设置里重新填写令牌。";
    static final String NO_SECRET = "服务器未配置 git 令牌密钥，读不了这个仓库，请联系管理员。";
    static final String UNREACHABLE = "暂时读不到这个 git 仓库，请稍后再试，或请用户手动上传这份文件。";

    /** 单个仓库最多列多少条：几个仓库一起列时，别让第一个仓库把 100 条的额度占满。 */
    static final int MAX_PER_REPO = 50;

    private final AddinGitRepoLinkRepository links;
    private final GitProviderClient client;
    private final GitTokenCipher cipher;
    private final ProjectFileTextExtractor extractor;

    public GitProviderSource(AddinGitRepoLinkRepository links, GitProviderClient client,
                             GitTokenCipher cipher, ProjectFileTextExtractor extractor) {
        this.links = links;
        this.client = client;
        this.cipher = cipher;
        this.extractor = extractor;
    }

    @Override
    public String scheme() {
        return "git";
    }

    // ==================== list ====================

    @Override
    public List<RefEntry> list(RefQuery q) {
        if (q.userId() == null || q.projectId() == null) {
            return List.of();
        }
        List<AddinGitRepoLink> rows = links.findByUserIdAndCloudProjectId(q.userId(), q.projectId());
        if (rows.isEmpty()) {
            // 这个项目没关联仓库：安静地不列，不在清单里添一行噪音
            return List.of();
        }
        String keyword = q.query() == null || q.query().isBlank()
                ? null : q.query().trim().toLowerCase(Locale.ROOT);
        List<RefEntry> out = new ArrayList<>();
        String firstFailure = null;
        int failed = 0;
        for (AddinGitRepoLink link : rows) {
            try {
                GitProviderClient.RepoRef ref = refOf(link);
                int taken = 0;
                for (String path : client.listPaths(ref, tokenOf(link))) {
                    if (taken >= MAX_PER_REPO) {
                        break;
                    }
                    String name = fileName(path);
                    if (keyword != null && !name.toLowerCase(Locale.ROOT).contains(keyword)) {
                        continue;
                    }
                    out.add(new RefEntry("git:" + link.getId() + ":" + path, "git", name,
                            repoLabel(link) + "/" + path, link.getProvider(), null, null));
                    taken++;
                }
                clearStaleError(link);
            } catch (RefSourceException e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = e.getMessage();
                }
            } catch (IOException e) {
                failed++;
                String message = explain(link, e);
                if (firstFailure == null) {
                    firstFailure = message;
                }
            }
        }
        // 一个仓库坏了不该把另一个仓库的清单也带走；全都坏了才整条报出来，
        // 让模型能如实转述，而不是说「没有这个文件」
        if (failed == rows.size() && firstFailure != null) {
            throw new RefSourceException(firstFailure);
        }
        return out;
    }

    // ==================== read ====================

    @Override
    public String read(RefQuery q, String body, String locator) {
        String raw = body == null ? "" : body.trim();
        int i = raw.indexOf(':');
        if (i <= 0) {
            throw new RefSourceException(NOT_FOUND);
        }
        long linkId;
        try {
            linkId = Long.parseLong(raw.substring(0, i));
        } catch (NumberFormatException e) {
            throw new RefSourceException(NOT_FOUND);
        }
        String path = raw.substring(i + 1);
        if (path.isBlank()) {
            throw new RefSourceException(NOT_FOUND);
        }
        AddinGitRepoLink link = q.userId() == null ? null
                : links.findByIdAndUserId(linkId, q.userId()).orElse(null);
        // 关联是按「人 + 云端项目」建的（唯一键里就有 cloud_project_id），读也得按这两维判：
        // 只判人的话，另一个项目的对话里抄来的 ref 仍然读得到，list 那边的项目边界就成了摆设
        if (link == null || q.projectId() == null
                || !q.projectId().equals(link.getCloudProjectId())) {
            throw new RefSourceException(NOT_FOUND);
        }
        byte[] bytes;
        try {
            bytes = client.readFile(refOf(link), tokenOf(link), path);
        } catch (IOException e) {
            throw new RefSourceException(explain(link, e));
        }
        String text;
        try {
            text = extractor.extractBytes(path, bytes);
        } catch (IOException e) {
            // 抽取器的 message 本来就是写给用户看的（「文件超过 50MB」之类）
            throw new RefSourceException(e.getMessage());
        }
        markRead(link);
        return RefSource.withLocatorNote(locator, text);
    }

    // ==================== 内部 ====================

    private GitProviderClient.RepoRef refOf(AddinGitRepoLink link) {
        return new GitProviderClient.RepoRef(link.getProvider(), link.getOwner(), link.getRepo(),
                link.getBranch());
    }

    /** 公开仓库可以没有令牌；有密文却没有密钥时说清是服务器没配，别让用户去换一把好端端的令牌。 */
    private String tokenOf(AddinGitRepoLink link) {
        String enc = link.getTokenEnc();
        if (enc == null || enc.isBlank()) {
            return null;
        }
        if (!cipher.enabled()) {
            throw new RefSourceException(NO_SECRET);
        }
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            throw new RefSourceException(NO_SECRET);
        }
    }

    /**
     * 出站失败 → 给用户看的一句话，顺便把原因记在关联行上。
     *
     * <p>「找不到文件」不记 lastError：那是模型抄错了路径，关联本身是好的，
     * 在面板上常驻一行红字只会误导用户去重填令牌。
     */
    private String explain(AddinGitRepoLink link, IOException e) {
        if (e instanceof GitProviderClient.GitAuthException) {
            markError(link, AUTH_FAILED);
            return AUTH_FAILED;
        }
        if (e instanceof FileNotFoundException) {
            return NOT_FOUND;
        }
        // 传输故障的细节（主机、端口）既帮不上律师的忙，也不该进模型的输入
        log.warn("git 参考读取失败: linkId={}, provider={}, error={}",
                link.getId(), link.getProvider(), e.getClass().getName());
        markError(link, UNREACHABLE);
        return UNREACHABLE;
    }

    /**
     * 列成功：只把上一次的失败原因抹掉。ref_list 一轮对话里可能调好几次，
     * 每次都写一趟库只为更新一个没人看的时间戳，不值。
     */
    private void clearStaleError(AddinGitRepoLink link) {
        if (link.getLastError() == null) {
            return;
        }
        link.setLastOkAt(LocalDateTime.now());
        link.setLastError(null);
        save(link);
    }

    /** 读成功：这是「这条关联确实能取到内容」的唯一证据，值得记一次时间戳。 */
    private void markRead(AddinGitRepoLink link) {
        link.setLastOkAt(LocalDateTime.now());
        link.setLastError(null);
        save(link);
    }

    private void markError(AddinGitRepoLink link, String message) {
        link.setLastError(message);
        save(link);
    }

    /** 记状态失败不该把这次读取也带崩——用户要的是文件内容，不是那一行时间戳。 */
    private void save(AddinGitRepoLink link) {
        try {
            links.save(link);
        } catch (RuntimeException e) {
            log.warn("git 关联状态更新失败: linkId={}, error={}", link.getId(), e.getClass().getName());
        }
    }

    private static String repoLabel(AddinGitRepoLink link) {
        return link.getOwner() + "/" + link.getRepo();
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
