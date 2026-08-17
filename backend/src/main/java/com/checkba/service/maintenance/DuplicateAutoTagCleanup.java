package com.checkba.service.maintenance;

import com.checkba.model.entity.FileTag;
import com.checkba.model.entity.Tag;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 存量库里重复自动标签的一次性修复。
 *
 * <h3>坏数据是怎么来的</h3>
 * {@code POST /api/files/{id}/upload} 同时是编辑器自动保存的落点，挂在那条分支上的
 * {@code AutoTaggingService.autoTagFile} 因此每存一次盘就跑一次：每轮 LLM 返回 5 个
 * 措辞不同的新词，而 {@code TagService.getOrCreateSystemTag} 只按精确字符串去重，
 * 标签于是无上限累积。维护者本机实测：**一个文件积到 338 个标签**，
 * 搜索面板的标签筛选区被撑成一面墙，同时每次自动保存都白烧一次辅助模型的钱。
 * 生成侧的闸已经加在 {@code AutoTaggingService}（已有系统标签就跳过），
 * 但那只挡住未来，存量的墙还立在每一台已升级的机器上。
 *
 * <h3>为什么不是「全清 AI 标签重来」</h3>
 * 维护者在自己机器上选了全清，那是他对自己数据的处置。**下发给所有用户是另一回事**：
 * 有人的自动标签只打过一遍（新装机器、或很少编辑的文件），那批标签是好的、
 * 而且可能已经被拿来筛过文件。把它们一起删掉是拿别人的数据替他做主。
 *
 * <p>所以这里只修**能证明是 bug 产物**的那部分：
 * <ol>
 *   <li>按文件保留最早的 {@value #KEEP_PER_FILE} 条自动标签关联——那正好是第一次打标签的
 *       结果（{@code AutoTaggingService} 每轮 {@code .limit(5)}），后面的都是重复批次；</li>
 *   <li>某个文件的自动标签**不超过 5 个就一行不动**——健康安装因此零变化，
 *       跑完日志里连一条 INFO 都不会有；</li>
 *   <li>再删掉因此变成「零文件引用」的自动标签——它们只会继续占着筛选区；</li>
 *   <li>手工标签（{@code isSystem=false}）与它们的关联**一律不碰**。</li>
 * </ol>
 *
 * <p>只跑一次（{@value #DONE_KEY} 落库），失败不拖垮启动——最坏情况是标签墙多留一版，
 * 而不是用户打不开软件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicateAutoTagCleanup {

    /** 保留数 = AutoTaggingService 每轮的 {@code .limit(5)}，即「第一次打标签」的产出。 */
    static final int KEEP_PER_FILE = 5;

    /** 一次性标志。照 DataInitializer 写 {@code system.wizard.completed} 的形态。 */
    static final String DONE_KEY = "maintenance.autoTagDedup.done";

    private final TagRepository tagRepository;
    private final FileTagRepository fileTagRepository;
    private final SystemSettingService systemSettingService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (systemSettingService.get(DONE_KEY, null) != null) {
            return;
        }
        try {
            Result r = cleanup();
            if (r.removedLinks() > 0 || r.removedTags() > 0) {
                log.info("重复自动标签清理完成：{} 个文件，删除 {} 条关联、{} 个标签",
                        r.affectedFiles(), r.removedLinks(), r.removedTags());
            }
            systemSettingService.set(DONE_KEY, LocalDateTime.now().toString());
        } catch (Exception e) {
            // 不写完成标志：下次启动再试一次。清理本身是幂等的，重跑无害。
            log.warn("重复自动标签清理失败（不影响启动）: {}", e.toString());
        }
    }

    /** @return 本次实际删掉了什么。可重复调用：跑完一次之后再跑就是全零。 */
    @Transactional
    public Result cleanup() {
        List<Tag> autoTags = tagRepository.findByIsSystemTrue();
        if (autoTags.isEmpty()) {
            return new Result(0, 0, 0);
        }
        Set<Long> autoTagIds = autoTags.stream().map(Tag::getId).collect(Collectors.toSet());

        List<FileTag> autoLinks = fileTagRepository.findByTagIdIn(new ArrayList<>(autoTagIds));
        Map<Long, List<FileTag>> byFile = autoLinks.stream()
                .collect(Collectors.groupingBy(FileTag::getFileId));

        List<FileTag> doomed = new ArrayList<>();
        int affectedFiles = 0;
        for (List<FileTag> links : byFile.values()) {
            if (links.size() <= KEEP_PER_FILE) {
                continue; // 没被 bug 弄坏，不动
            }
            affectedFiles++;
            // createdAt 可能为 null（很老的行）：null 排在最后，宁可留下有时间戳的最早那批
            links.sort(Comparator.comparing(FileTag::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            doomed.addAll(links.subList(KEEP_PER_FILE, links.size()));
        }
        if (doomed.isEmpty()) {
            return new Result(0, 0, 0);
        }
        fileTagRepository.deleteAll(doomed);

        // 删完关联后，哪些自动标签一个文件都不挂了 —— 它们只会继续占着筛选区。
        // 判据要重新查一遍：同一个标签可能同时挂在别的文件上，不能拿本次删掉的集合去推。
        Set<Long> survivingTagIds = fileTagRepository.findByTagIdIn(new ArrayList<>(autoTagIds))
                .stream().map(FileTag::getTagId).collect(Collectors.toSet());
        Set<Long> orphanTagIds = new HashSet<>(autoTagIds);
        orphanTagIds.removeAll(survivingTagIds);
        if (!orphanTagIds.isEmpty()) {
            tagRepository.deleteAllById(orphanTagIds);
        }
        return new Result(affectedFiles, doomed.size(), orphanTagIds.size());
    }

    /** @param affectedFiles 有多少个文件被判定为「被 bug 弄坏」 */
    public record Result(int affectedFiles, int removedLinks, int removedTags) {}
}
