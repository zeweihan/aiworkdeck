// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.AccountBinding;
import com.checkba.repository.AccountBindingRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 官方案件库里的文件（dev-board#720），ref 形如 {@code case:<remoteProjectId>:<path>}
 * （path 可含 {@code :}，只按第一个 {@code :} 切）。只读（D 决策）。
 *
 * <p>案件库（case 实例）与插件云后端（addin 实例）是同一个 jar 的两个实例，库与用户表分离，
 * 同一个人在两边是两个本机 userId。跨实例身份键只有一个：{@link AccountBinding#getExternalAccountId()}
 * （官网账户 id，两边 awdk 登录都会写）。没有绑定 = 这个人没用官网账号登录过本实例，
 * 也就无从在案件库那侧对上号——来源直接缺席，一次请求都不发。
 *
 * <p>国际站与自建服务器不配 {@code ref.case.base-url} / {@code ref.internal.secret}，
 * {@link CaseRefClient#configured()} 为假，同样整块缺席（spec §7.1）。
 */
@Component
public class CaseLibrarySource implements RefSource {

    static final String UNREACHABLE = "案件库暂时无法访问，请稍后再试，或请用户手动上传这份文件。";
    static final String BAD_REF = "无法识别的引用，请先用 ref_list 获取 ref。";

    private final CaseRefClient client;
    private final AccountBindingRepository bindings;

    public CaseLibrarySource(CaseRefClient client, AccountBindingRepository bindings) {
        this.client = client;
        this.bindings = bindings;
    }

    @Override
    public String scheme() {
        return "case";
    }

    @Override
    public boolean available(RefQuery q) {
        // 先判配置：没配案件库就连"这个人是谁"都不必查
        return client.configured() && accountId(q).isPresent();
    }

    @Override
    public List<RefEntry> list(RefQuery q) {
        String accountId = accountId(q).orElse(null);
        if (accountId == null) {
            return List.of();
        }
        String keyword = q.query() == null || q.query().isBlank() ? null : q.query().trim();
        List<CaseRefClient.Entry> entries;
        try {
            entries = client.list(accountId, keyword);
        } catch (IOException e) {
            throw failure(e);
        }
        List<RefEntry> out = new ArrayList<>();
        for (CaseRefClient.Entry e : entries) {
            out.add(new RefEntry("case:" + e.remoteProjectId() + ":" + e.path(), "case", e.name(),
                    "案件库/" + e.projectName() + "/" + e.path(), null, null, null));
        }
        return out;
    }

    @Override
    public String read(RefQuery q, String body, String locator) {
        String accountId = accountId(q).orElseThrow(() -> new RefSourceException(
                "还没有用官网账号登录过，读不到案件库里的文件。"));
        String raw = body == null ? "" : body.trim();
        int i = raw.indexOf(':');
        if (i <= 0) {
            throw new RefSourceException(BAD_REF);
        }
        long remoteProjectId;
        try {
            remoteProjectId = Long.parseLong(raw.substring(0, i));
        } catch (NumberFormatException e) {
            throw new RefSourceException(BAD_REF);
        }
        String path = raw.substring(i + 1);
        if (path.isBlank()) {
            throw new RefSourceException(BAD_REF);
        }
        try {
            return RefSource.withLocatorNote(locator, client.read(accountId, remoteProjectId, path));
        } catch (IOException e) {
            throw failure(e);
        }
    }

    private Optional<String> accountId(RefQuery q) {
        if (q == null || q.userId() == null) {
            return Optional.empty();
        }
        return bindings.findByUserId(q.userId())
                .map(AccountBinding::getExternalAccountId)
                .filter(id -> id != null && !id.isBlank());
    }

    /**
     * 案件库那侧说得出原因的失败原样转述；传输故障只说「暂时无法访问」——
     * 网络细节（主机、端口、连接被拒）既帮不上律师的忙，也不该进模型的输入。
     */
    private static RefSourceException failure(IOException e) {
        return new RefSourceException(
                e instanceof CaseRefClient.CaseRefException ? e.getMessage() : UNREACHABLE);
    }
}
