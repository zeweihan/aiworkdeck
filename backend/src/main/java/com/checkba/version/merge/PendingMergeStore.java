// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import com.checkba.version.ProjectRepoService;
import com.checkba.version.VersionException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「这几份文件已经逐处合好、字节已经写回工作区了」的待决记录
 * （spec 2026-09-14 §4.4），落在 {@code <gitdir>/awd-merge-pending.json}。
 *
 * <p><b>为什么放 gitdir 而不是工作区</b>：裁决收尾走的是
 * {@link ProjectRepoService#commitMergeResolution} 的 {@code git add .}，
 * 放工作区的话这个内部文件会被原样收进律师的版本历史里——他不认识它，
 * 而且它是本次裁决的中间态，一次性的。gitdir 不在工作树里，{@code git add .} 收不到。
 *
 * <p><b>为什么必须落盘而不是放内存</b>：裁决窗口是数据安全窗口——律师在合并比对稿里
 * 逐处裁完一份文件、还没点「确认选择」时把桌面端关了，重开之后
 * {@code /status} 要能说出「这份已经合好了」，否则他会被要求把同一份文件再裁一遍，
 * 而工作区里躺着的已经是合并结果、不是冲突态，第二遍裁出来的东西是错的。
 *
 * <p>生命周期与合并窗口同寿：裁决提交成功后 {@code clear}，
 * 中止合并（{@code abortMerge}）时也 {@code clear}——两条路都由调用方负责，
 * 本类只管读写这一个文件。
 */
@Service
public class PendingMergeStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PendingMergeStore.class);

    /** 文件名里不带项目号：它本来就躺在这个项目自己的 gitdir 里。 */
    static final String FILE_NAME = "awd-merge-pending.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectRepoService repoService;

    public PendingMergeStore(ProjectRepoService repoService) {
        this.repoService = repoService;
    }

    /**
     * 磁盘形制。{@code path} 不在这里——它是外层 map 的键，避免同一条信息存两份对不上。
     * {@code writtenAt} 只为排障（合并窗口里哪一份先合好的），任何判定都不看它。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(String mode, List<Decision> decisions, int mainCount, int otherCount, String writtenAt) {
    }

    /** 记下（或覆盖）一份文件的合并结果。同一份文件重裁一遍就是覆盖，最后一次为准。 */
    public void put(long projectId, MergeRecord r) {
        if (r == null || r.path() == null || r.path().isBlank()) return;
        Map<String, Row> rows = read(projectId);
        rows.put(r.path(), new Row(r.mode(),
                r.decisions() == null ? List.of() : r.decisions(),
                r.mainCount(), r.otherCount(), Instant.now().toString()));
        write(projectId, rows);
    }

    public Optional<MergeRecord> get(long projectId, String path) {
        if (path == null) return Optional.empty();
        Row row = read(projectId).get(path);
        return row == null ? Optional.empty() : Optional.of(toRecord(path, row));
    }

    /** 全部记录，按路径排序——收尾写尾注时顺序要稳定（同 {@code mergesTrailerValue} 的理由）。 */
    public List<MergeRecord> all(long projectId) {
        Map<String, Row> rows = read(projectId);
        List<MergeRecord> out = new ArrayList<>(rows.size());
        for (String path : new java.util.TreeSet<>(rows.keySet())) {
            out.add(toRecord(path, rows.get(path)));
        }
        return out;
    }

    /** 合并窗口结束（裁决提交成功 / 中止合并）：整份文件删掉。 */
    public void clear(long projectId) {
        try {
            Files.deleteIfExists(file(projectId));
        } catch (IOException e) {
            // 删不掉只会让下一次合并窗口读到一份陈旧记录，而那一次的 resolve-file 会
            // 逐份覆盖它；为此把已经落地的裁决提交打回去，代价远大于留一个脏文件。
            log.warn("清理待决合并记录失败（不阻断）: project={}", projectId, e);
        }
    }

    private static MergeRecord toRecord(String path, Row row) {
        return new MergeRecord(path, row.mode(),
                row.decisions() == null ? List.of() : row.decisions(),
                row.mainCount(), row.otherCount());
    }

    private Path file(long projectId) {
        return repoService.gitDir(projectId).resolve(FILE_NAME);
    }

    private Map<String, Row> read(long projectId) {
        Path f = file(projectId);
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(Files.readAllBytes(f), new TypeReference<LinkedHashMap<String, Row>>() {});
        } catch (Exception e) {
            // 读不回来（手工改坏、写到一半断电）就当没有记录：后果是律师被要求重裁一遍，
            // 比拿一份解不回来的记录去收尾安全。
            log.warn("待决合并记录读不回来，按空处理: project={}", projectId, e);
            return new LinkedHashMap<>();
        }
    }

    private void write(long projectId, Map<String, Row> rows) {
        Path f = file(projectId);
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows));
        } catch (Exception e) {
            // 这条必须抛：写不进去就等于「合好的文件没人记得」，收尾时会被当成没合过，
            // 律师会拿到一个说不通的状态。宁可当场报错让他重试。
            throw new VersionException("记录合并结果失败: project=" + projectId, e);
        }
    }
}
