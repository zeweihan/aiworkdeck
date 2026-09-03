package com.checkba.service.mobile;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 启动期对账：把手机中转落盘时写错的 {@code project_file.file_type} 改回扩展名（dev-board#417）。
 *
 * <h3>要治的是什么</h3>
 * {@code MobileRelayClientService.landAndAck} 曾把中转件的 {@code mediaType}
 * （{@code image}/{@code video}/{@code audio}）当成 fileType 落库，而 fileType 在全仓
 * 只有一个语义——**文件扩展名**（见 {@link ProjectFile} 的字段注释）。后果不是报错，
 * 是文件在文件树里躺得好好的、字节也完好，点开却弹「无法打开文件：暂不支持打开此类型文件…
 * 文件类型：image」——前端 {@code isFileTypeSupported} 的白名单里是 jpg/png/mp4，
 * 没有 image/video/audio。同一个错还让 {@code isAudioFile} 恒 false，
 * 资源管理器右键的「转写」（dev-board#228）在手机传来的录音上永远不出现。
 *
 * <p>写入侧已经改成落扩展名，但**存量行救不回来**：影像早已 ACK、中转区 blob 早已删除，
 * 取件轮询再也不会碰到它们。本仓没有 SQL 迁移框架（ddl-auto: update），
 * 所以照 {@link OrphanPhoneSessionReconciler} 的成例做成启动期对账。
 *
 * <h3>为什么按 fileType 取行是安全的</h3>
 * image/video/audio 三个词不可能是任何真实文件的扩展名，命中的必然是这个 bug 的产物。
 * 改动也只发生在「名字里确实有扩展名」时——名字没有扩展名的行保持原样，
 * 那正是 {@code ContextAssemblerService.isVisionCandidate} 唯一还认 fileType=image 的一档。
 *
 * <p>只在 {@code security.local-mode=true}（单机桌面版）跑：中转客户端本来就只在桌面端活动，
 * 脏行只可能在桌面端本地库里；云后端与团队服务器上跑等于白扫一遍全表。
 *
 * <p>幂等：改过之后再也匹配不到，之后每次启动都是 0 条。
 */
@Service
@Slf4j
public class MediaFileTypeReconciler implements CommandLineRunner {

    /** 曾被误当 fileType 落库的 mediaType 取值（MobileRelayStoreService 的白名单前三个）。 */
    private static final List<String> BAD_FILE_TYPES = List.of("image", "video", "audio");

    private final boolean localMode;
    private final ProjectFileRepository projectFileRepository;

    public MediaFileTypeReconciler(@Value("${security.local-mode:false}") boolean localMode,
                                   ProjectFileRepository projectFileRepository) {
        this.localMode = localMode;
        this.projectFileRepository = projectFileRepository;
    }

    @Override
    public void run(String... args) {
        reconcile();
    }

    /** @return 被改写的行数（供测试与日志用） */
    public int reconcile() {
        if (!localMode) return 0;
        List<ProjectFile> rows;
        try {
            rows = projectFileRepository.findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(BAD_FILE_TYPES);
        } catch (RuntimeException e) {
            // 对账是顺手活，读不到文件表不该拦住启动
            log.warn("影像 fileType 对账跳过：读取文件表失败 {}", e.toString());
            return 0;
        }
        int fixed = 0;
        for (ProjectFile f : rows) {
            String ext = MobileRelayClientService.fileTypeOf(f.getName(), null);
            if (ext == null || ext.equals(f.getFileType())) continue;
            try {
                String before = f.getFileType();
                f.setFileType(ext);
                projectFileRepository.save(f);
                fixed++;
                log.info("影像 fileType 对账：文件 {}（{}）{} → {}，此前在文件树里点开会被判「不支持的类型」",
                        f.getId(), f.getName(), before, ext);
            } catch (RuntimeException e) {
                log.warn("影像 fileType 对账：文件 {} 改写失败 {}", f.getId(), e.toString());
            }
        }
        if (fixed > 0) {
            log.info("影像 fileType 对账完成：修正 {} 行", fixed);
        }
        return fixed;
    }
}
