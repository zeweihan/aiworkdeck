package com.checkba.service.storage;

import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户自选的本机文件存储位置（Spec §5「付费解锁后需用户自行指定本地存储路径」）。
 *
 * <h3>它管的到底是什么</h3>
 * 文件缓存区里的文件就是项目文件（「加入缓存区」= 移进项目内的 {@code __staging_area__} 目录），
 * 物理上都躺在全局存储根下，没有独立的缓存区目录可搬。因此本服务搬的是
 * <b>全局存储根</b>——即项目文件与缓存区文件在本机的落盘位置。
 * 数据库里存的是逻辑路径，物理位置全部经 {@link ProjectStorageResolver} 解析，
 * 所以迁移只需搬目录 + 换指针，无需改任何一行数据。
 *
 * <h3>迁移策略：复制 → 校验 → 换指针 → 原目录留作备份</h3>
 * <b>绝不先删后搬。</b>顺序是：
 * <ol>
 *   <li>校验目标目录（绝对路径、可写、与源不互相嵌套、为空或不存在）；</li>
 *   <li>整棵树复制过去，边复制边记数；</li>
 *   <li>校验目标的文件数与总字节数与源一致，不一致即失败；</li>
 *   <li>写落盘配置 + 切换解析器指针；</li>
 *   <li><b>原目录原封不动保留</b>，由用户确认无误后自行删除。</li>
 * </ol>
 * 任一步失败都会清掉本次在目标目录里复制出来的内容（那些全是本次新建的副本，
 * 删它们不碰任何原始数据），并保持配置指向原路径——失败后的状态与没点过这个按钮完全一样。
 *
 * <h3>为什么配置落文件而不落数据库</h3>
 * 存储根必须在任何文件操作发生之前就确定，而数据库要等 JPA 起来才能读，
 * 存在启动期先后顺序的窟窿。落在 {@code ~/.aiworkdeck/storage-location.json}
 * （与 license/entitlements 同一个状态目录）则可在解析器构造完成后立刻应用。
 */
@Service
@Slf4j
public class StorageLocationService {

    private final ProjectStorageResolver resolver;
    private final Path stateFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorageLocationService(
            ProjectStorageResolver resolver,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir) {
        this.resolver = resolver;
        this.stateFile = Path.of(stateDir, "storage-location.json");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class State {
        public String root;
        public String movedAt;
    }

    /**
     * 启动时应用已保存的自选位置。
     * 目录不存在（外置硬盘没插、被改名）时**不静默回退**到默认位置——那会让用户面对一个空白的
     * 应用并以为数据没了，而真相是数据好端端躺在别处。这里保持指向该路径并告警，
     * 文件操作会明确失败，设置页也仍显示这个路径，用户知道该去插硬盘。
     */
    @PostConstruct
    void applyOnStartup() {
        State state = loadState();
        if (state.root == null || state.root.isBlank()) return;
        Path saved = Path.of(state.root).toAbsolutePath().normalize();
        if (!Files.isDirectory(saved)) {
            log.warn("自选存储位置当前不可用（目录不存在）: {}。已保持指向该位置，请检查磁盘是否连接。", saved);
        }
        resolver.relocate(saved);
        log.info("存储位置已应用自选路径: {}", saved);
    }

    /** 设置页展示用的当前状态。 */
    public Map<String, Object> current() {
        Path root = resolver.globalRoot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", root.toString());
        result.put("defaultPath", resolver.configuredRoot().toString());
        result.put("custom", !root.equals(resolver.configuredRoot()));
        result.put("available", Files.isDirectory(root));
        State state = loadState();
        result.put("movedAt", state.movedAt);
        return result;
    }

    /**
     * 迁移到新位置。同步执行（数据量可能不小，前端要给等待态）。
     *
     * @return 迁移结果：搬了多少文件、多少字节、原路径（保留为备份）
     * @throws StorageException 校验或迁移失败；此时存储位置维持原样，用户数据一个字节没动
     */
    public synchronized Map<String, Object> migrate(String targetPath) {
        Path source = resolver.globalRoot();
        // 源不可访问时不能往下走：copyTree 见源目录不存在会把它建出来，
        // 于是「0 个文件迁移成功」，指针切到新的空目录，用户的数据留在拔掉的那块盘上而应用一片空白。
        if (!Files.isDirectory(source)) {
            throw new StorageException("当前存储目录不可访问（磁盘未连接或已被移动），无法迁移。"
                    + "请先接回磁盘再迁移；若不打算继续使用该磁盘，可先恢复默认位置。");
        }
        Path target = validateTarget(targetPath, source);

        boolean createdTargetDir = !Files.exists(target);
        Tally sourceTally;
        try {
            Files.createDirectories(target);
            assertWritable(target);
            sourceTally = tally(source);
            copyTree(source, target);
            Tally targetTally = tally(target);
            // 源侧复查：只比「复制前的源」和「复制后的目标」是不够的。复制一棵大树要几分钟，
            // 期间自动保存/AI 改文档/浏览器下载都会往源里落盘；落在已经被 copyTree 走过的目录里
            // 的那些文件不会出现在目标里，而两侧数字仍然相等——校验通过、指针切走，
            // 那个文件在文件树里看得见却打不开。宁可让用户重来一次。
            Tally sourceAfter = tally(source);
            if (sourceAfter.files != sourceTally.files || sourceAfter.bytes != sourceTally.bytes) {
                throw new StorageException("迁移期间原目录发生了变化（可能有文档在自动保存，或有下载/AI 改动在进行），"
                        + "为确保一个文件都不落下，已放弃本次迁移并保持原位置。请关闭正在编辑的文档后重试。");
            }
            if (targetTally.files != sourceTally.files || targetTally.bytes != sourceTally.bytes) {
                throw new StorageException("迁移校验未通过（文件数或大小不一致），已放弃本次迁移");
            }
        } catch (StorageException e) {
            rollback(target, createdTargetDir);
            throw e;
        } catch (Exception e) {
            rollback(target, createdTargetDir);
            log.error("存储位置迁移失败，已回滚，仍使用原位置: {}", source, e);
            throw new StorageException("迁移失败，已保持原存储位置：" + e.getMessage());
        }

        // 复制与校验都过了才落配置、才换指针。这两步之间即使进程被杀，
        // 下次启动会按配置文件走新位置，数据已经在那里了。
        State state = new State();
        state.root = target.toString();
        state.movedAt = Instant.now().toString();
        try {
            saveState(state);
        } catch (StorageException e) {
            // 此时 relocate 还没执行，存储根**没有**切换，目标里躺着一份完整副本。
            // 留着它下次迁移会撞上「请选择一个空目录」，用户得先手工删目录才能重试——
            // 所以这里连同副本一起清掉，回到「没点过这个按钮」的状态。源目录全程只读。
            rollback(target, createdTargetDir);
            throw e;
        }
        resolver.relocate(target);
        log.info("存储位置已迁移: {} -> {}（{} 个文件），原目录保留为备份", source, target, sourceTally.files);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", target.toString());
        result.put("previousPath", source.toString());
        result.put("movedFiles", sourceTally.files);
        result.put("movedBytes", sourceTally.bytes);
        return result;
    }

    /**
     * 恢复到默认位置。<b>只换指针，不搬也不删任何文件</b>——自选目录里的数据原样留在那里。
     *
     * <p>为什么它不要求权益（与 {@link #migrate} 不同）：权益可能在自选位置生效之后失效
     * （Key 被吊销、断开账户、离线超宽限）。此时自选目录若又不可访问（外置盘拔了、被改名），
     * 用户就被永久困在一个既进不去也换不掉的存储根上，所有文件操作无解释地失败。
     * 「退回免费版的默认位置」不发放任何付费能力，把它锁在付费墙后面只会制造死局。</p>
     *
     * @return 恢复结果：默认位置与被留下的原位置（前端要把原位置显示给用户，文件还在那儿）
     */
    public synchronized Map<String, Object> resetToDefault() {
        Path previous = resolver.globalRoot();
        Path defaultRoot = resolver.configuredRoot();
        if (previous.equals(defaultRoot)) {
            throw new StorageException("当前已经是默认位置");
        }
        saveState(new State()); // root=null：下次启动不再应用自选路径
        resolver.relocate(defaultRoot);
        log.info("存储位置已恢复默认: {} -> {}（原目录内容一律保留）", previous, defaultRoot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", defaultRoot.toString());
        result.put("previousPath", previous.toString());
        return result;
    }

    // ==================== 内部 ====================

    private Path validateTarget(String targetPath, Path source) {
        if (targetPath == null || targetPath.isBlank()) {
            throw new StorageException("请选择一个目录");
        }
        Path target = Path.of(targetPath).toAbsolutePath().normalize();
        // 嵌套判断必须在**解析软链之后**做：normalize() 是纯词法的，
        // 一个指向源目录内部的软链（/tmp/link -> /data/sub）在词法上与 /data 毫无关系，
        // 三条围栏全部判 false，随后 copyTree 顺着链把源目录复制进它自己，
        // 直到路径长度触顶失败，并在用户的数据根里留下几百个垃圾目录。
        Path realSource = realPathOf(source);
        Path realTarget = realPathOf(target);
        if (realTarget.equals(realSource)) {
            throw new StorageException("新位置与当前位置相同");
        }
        // 互相嵌套会让「复制整棵树」变成无限自我复制，或把源埋进目标里
        if (realTarget.startsWith(realSource)) {
            throw new StorageException("新位置不能在当前存储目录内部");
        }
        if (realSource.startsWith(realTarget)) {
            throw new StorageException("新位置不能是当前存储目录的上级目录");
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new StorageException("所选路径不是目录");
        }
        // 只接受空目录：避免与目标里已有的同名文件发生覆盖或合并语义，
        // 那是丢数据最容易发生的地方
        if (Files.isDirectory(target)) {
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new StorageException("请选择一个空目录（或新建一个），以免与已有文件混在一起");
                }
            } catch (IOException e) {
                throw new StorageException("无法读取所选目录：" + e.getMessage());
            }
        }
        return target;
    }

    /**
     * 解析软链后的真实路径。路径尚不存在时（用户在选择器里新建的目录），
     * 取最近的已存在祖先的真实路径，再把剩余的段拼回去。
     * 解析不了（权限等）就退回词法路径——那只是回到没有本方法时的判定强度，不会更松。
     */
    private static Path realPathOf(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) return path;
        try {
            Path real = existing.toRealPath();
            return existing.equals(path) ? real : real.resolve(existing.relativize(path));
        } catch (IOException e) {
            return path;
        }
    }

    private void assertWritable(Path dir) {
        Path probe = dir.resolve(".awd-write-probe");
        try {
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new StorageException("所选目录不可写，请换一个位置或检查权限");
        }
    }

    /** 复制整棵树。目标已确保为空，不存在覆盖既有文件的可能。 */
    private void copyTree(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            Files.createDirectories(source);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    record Tally(long files, long bytes) {}

    /** package-private 是留给测试的接缝：覆写它即可制造「校验不通过」以验证回滚。 */
    Tally tally(Path root) throws IOException {
        if (!Files.exists(root)) return new Tally(0, 0);
        long[] acc = new long[2];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                acc[0]++;
                acc[1] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return new Tally(acc[0], acc[1]);
    }

    /**
     * 回滚：只删本次复制到目标目录里的内容。目标事前已校验为空目录（或不存在），
     * 所以这里删掉的每一个字节都是本次刚生成的副本，用户的原始数据在源目录纹丝不动。
     */
    private void rollback(Path target, boolean createdTargetDir) {
        try {
            if (!Files.exists(target)) return;
            try (var walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder())
                        .filter(p -> createdTargetDir || !p.equals(target))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // 残留副本不影响正确性，源数据未动；下次迁移会因目录非空而被拦下
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("迁移回滚清理不完整（不影响原数据）: {}", e.getMessage());
        }
    }

    State loadState() {
        try {
            if (!Files.exists(stateFile)) return new State();
            State s = objectMapper.readValue(Files.readAllBytes(stateFile), State.class);
            return s == null ? new State() : s;
        } catch (Exception e) {
            log.warn("storage-location.json 读取失败，按默认位置处理: {}", e.getMessage());
            return new State();
        }
    }

    void saveState(State state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.write(stateFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
        } catch (Exception e) {
            // 抛出去（而不是吞掉）：配置没记住就等于这次操作没发生，调用方据此回滚并保持原位置。
            // 注意文案不能说「已迁移」——此时 relocate 还没执行，存储根一动没动。
            throw new StorageException("存储位置配置写入失败，已保持原位置：" + e.getMessage());
        }
    }
}
