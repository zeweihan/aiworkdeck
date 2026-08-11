package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目档案（客户 / 事项类型 / 立项时间 / 下一步 / 对方）。
 *
 * 对外契约：读接口恒返回 5 条、顺序固定、label 由服务端给——中文文案单一来源在这里，
 * 前端不许自己补齐缺项、不许自己排序、不许再写一份 label 表。
 *
 * source 库里只有 'ai' 与 'user' 两种取值；响应里可能出现的 'default' 是 openedAt
 * 无行时由 Project.createdAt 派生的，永不落库。
 */
@Service
public class ProjectProfileService {

    /** 固定五个字段，顺序即响应顺序 */
    public static final List<String> FIELD_KEYS =
            List.of("client", "matterType", "openedAt", "nextStep", "counterparty");

    private static final Map<String, String> LABELS = Map.of(
            "client", "客户",
            "matterType", "事项类型",
            "openedAt", "立项时间",
            "nextStep", "下一步",
            "counterparty", "对方");

    static final String SOURCE_USER = "user";
    static final String SOURCE_AI = "ai";
    static final String SOURCE_DEFAULT = "default";
    static final String KEY_OPENED_AT = "openedAt";

    private final ProjectProfileFieldRepository repository;
    private final ProjectRepository projectRepository;

    /**
     * 本 bean 的懒加载自身代理，只为让 saveUserField/applyAiSuggestion 的重试真正经过
     * Spring 的事务代理开出一个新事务——同类方法互相调用（this.xxxTx(...)）不经代理，
     * @Transactional 会被静默绕过。构造器没法自己注入自己，只能用字段注入；@Lazy 打破
     * 自引用的构造期死环，代理在真正被调用时才解析到已建好的 bean。
     *
     * 包可见（不加 private）是特意的：ProjectProfileServiceTest 用手工 new 构造这个服务
     * （不过 Spring 容器），需要在同包的 @BeforeEach 里手动把这个字段接到 service 自己。
     */
    @Autowired
    @Lazy
    ProjectProfileService self;

    public ProjectProfileService(ProjectProfileFieldRepository repository,
                                 ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    /** 概览页档案头一次渲染完：五个字段全量返回，未填的也返回、值为 null。 */
    public List<Map<String, Object>> getProfile(Long projectId) {
        Map<String, ProjectProfileField> rows = new HashMap<>();
        for (ProjectProfileField row : repository.findByProjectId(projectId)) {
            rows.put(row.getFieldKey(), row);
        }
        Project project = projectRepository.findById(projectId).orElse(null);

        List<Map<String, Object>> fields = new ArrayList<>(FIELD_KEYS.size());
        for (String fieldKey : FIELD_KEYS) {
            fields.add(render(fieldKey, rows.get(fieldKey), project));
        }
        return fields;
    }

    /**
     * 手填单字段（A 期唯一的写入通道）。upsert 语义：
     * 写入即把该字段锁成 source='user'，Plan 2 的 AI 抽取永不覆盖它。
     *
     * value 为 null 或 trim 后为空串 → 删除该行（回到未填态；openedAt 因此回落建档时间）。
     *
     * <p><b>不带 @Transactional——并发撞车的重试必须落在新事务里。</b>旧写法是单个
     * @Transactional 方法内部 try/catch DataIntegrityViolationException 后用同一个
     * EntityManager 补救：IDENTITY 生成策略下 repository.save() 对新实体是立即 INSERT，
     * 撞 (projectId, fieldKey) 唯一约束时 Hibernate 会在异常冒出来之前就把当前事务标记为
     * rollback-only。catch 之后继续用同一个 EntityManager 补救能正常跑完、方法能正常返回，
     * 但事务在方法出口提交时会因为 rollback-only 标记抛 UnexpectedRollbackException——
     * 结果和完全不写兜底一模一样（被 GlobalExceptionHandler 兜成 200+{code:1,"服务器内部
     * 错误"}），而且这次补救的写入随事务回滚一起被丢弃（实测结论，见并发测试）。
     *
     * <p>于是把重试拆成两次独立事务：本方法本身不开事务，两次尝试都通过 {@link #self}
     * 调用 {@link #saveUserFieldTx}，各自在自己的新事务里跑。重试那次进新事务后
     * find 能读到对方已提交的那一行，按更新语义写、保留它的 uid。
     */
    public Map<String, Object> saveUserField(Long projectId, String fieldKey, String value) {
        try {
            return self.saveUserFieldTx(projectId, fieldKey, value);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            return self.saveUserFieldTx(projectId, fieldKey, value);
        }
    }

    /**
     * {@link #saveUserField} 的事务体。不要直接调用（包括同类内部调用）——必须经
     * {@link #self} 代理才能拿到独立的新事务，直接调用会绕过 Spring 的事务拦截。
     *
     * <p>propagation 显式钉死 REQUIRES_NEW，不用默认的 REQUIRED：REQUIRED 只在调用方
     * 当前没有事务时才会新开事务，这个前提今天成立（唯一入口是非事务的控制器），但
     * 只要调用方本身处于某个外层事务里，REQUIRED 就会让两次重试都 join 进同一个外层
     * 事务——重试语义形同虚设，而且撞约束打上的 rollback-only 标记会随 join 一起污染
     * 外层事务，把外层事务里其它无关的写入也拖着一起回滚。REQUIRES_NEW 不看调用方有没有
     * 事务，每次都强制挂起外层、开一个全新的物理事务，这个保证与调用方是否处于事务中无关。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> saveUserFieldTx(Long projectId, String fieldKey, String value) {
        requireKnownKey(fieldKey);
        Project project = projectRepository.findById(projectId).orElse(null);

        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            repository.findByProjectIdAndFieldKey(projectId, fieldKey).ifPresent(repository::delete);
            return render(fieldKey, null, project);
        }

        ProjectProfileField row = repository.findByProjectIdAndFieldKey(projectId, fieldKey)
                .orElseGet(() -> newRow(projectId, fieldKey));
        row.setFieldValue(trimmed);
        row.setSource(SOURCE_USER);
        // 改成手填就把 AI 那次判断的痕迹清掉——留着会让 UI 把手填值标成「模型猜的」
        row.setConfidence(null);
        row.setEvidence(null);
        return render(fieldKey, repository.save(row), project);
    }

    /**
     * AI 抽取写入。**A 期没有调用方**（抽取链路属 Plan 2），本方法存在的意义是先把
     * 档案表的核心不变式立住并测掉：
     *
     *   source='user' 的字段锁定，AI 永不覆盖。
     *
     * AI 有新判断时挂到同一行的 pending* 四列（唯一约束是 (projectId, fieldKey)，
     * 建议不能另起一行），律师采纳后才转正。抽取结果为空时什么都不写——模型这轮没抽出来，
     * 不代表要清空律师已有的值。
     *
     * 不要给这个方法开 HTTP 端点：A 期没有任何触发 AI 抽取的入口，开了就是死端点。
     *
     * <p>不带 @Transactional，理由与 {@link #saveUserField} 完全一致：重试必须落在新事务里，
     * 否则撞约束时的补救会在方法出口随 rollback-only 的事务一起被回滚成
     * UnexpectedRollbackException。A 期没有调用方，这里先把并发形状和不变式一起立住——
     * Plan 2 接上 AI 抽取链路后，AI 异步抽取与律师手填并发写同一行会让这条 race 从理论变常态。
     */
    public Map<String, Object> applyAiSuggestion(Long projectId, String fieldKey, String value,
                                                 Double confidence, String evidence) {
        try {
            return self.applyAiSuggestionTx(projectId, fieldKey, value, confidence, evidence);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            return self.applyAiSuggestionTx(projectId, fieldKey, value, confidence, evidence);
        }
    }

    /**
     * {@link #applyAiSuggestion} 的事务体。不要直接调用（包括同类内部调用）——必须经
     * {@link #self} 代理才能拿到独立的新事务，直接调用会绕过 Spring 的事务拦截。
     *
     * <p>propagation 显式钉死 REQUIRES_NEW，不用默认的 REQUIRED，理由与
     * {@link #saveUserFieldTx} 完全一致，但这里的风险不是假设性的：Plan 2 接上 AI 抽取
     * 链路后，调用方大概率会包一层 @Transactional（例如批量抽取跑在一个事务里）。届时
     * REQUIRED 会让本方法的两次重试都 join 进那个外层事务而不是各自独立——重试语义静默
     * 失效，回到本类头部注释描述的 UnexpectedRollbackException 老问题；更糟的是撞约束
     * 打上的 rollback-only 标记会随 join 一起污染外层事务，把外层事务里其它无关的写入
     * （例如同一批次里别的字段）也拖着一起回滚。REQUIRES_NEW 不看调用方有没有事务，每次
     * 都强制挂起外层、开一个全新的物理事务，提前把这个坑堵死。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> applyAiSuggestionTx(Long projectId, String fieldKey, String value,
                                                 Double confidence, String evidence) {
        requireKnownKey(fieldKey);
        Project project = projectRepository.findById(projectId).orElse(null);
        Optional<ProjectProfileField> found = repository.findByProjectIdAndFieldKey(projectId, fieldKey);

        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return render(fieldKey, found.orElse(null), project);
        }

        ProjectProfileField row = found.orElseGet(() -> newRow(projectId, fieldKey));
        if (SOURCE_USER.equals(row.getSource())) {
            row.setPendingValue(trimmed);
            row.setPendingConfidence(confidence);
            row.setPendingEvidence(evidence);
            row.setPendingAt(LocalDateTime.now());
        } else {
            row.setFieldValue(trimmed);
            row.setSource(SOURCE_AI);
            row.setConfidence(confidence);
            row.setEvidence(evidence);
        }
        return render(fieldKey, repository.save(row), project);
    }

    /** 新行必须自带 uid：跨机器身份只认它，既有行的 uid 任何时候都不许换。 */
    private ProjectProfileField newRow(Long projectId, String fieldKey) {
        ProjectProfileField row = new ProjectProfileField();
        row.setProjectId(projectId);
        row.setFieldKey(fieldKey);
        row.setUid(UUID.randomUUID().toString());
        return row;
    }

    private void requireKnownKey(String fieldKey) {
        if (!FIELD_KEYS.contains(fieldKey)) {
            throw new IllegalArgumentException("未知的档案字段");
        }
    }

    /**
     * 组装单个字段的响应元素。
     *
     * 用 LinkedHashMap 不用 Map.of——Map.of 不接受 null value，而未填的字段五个值全是 null。
     */
    Map<String, Object> render(String fieldKey, ProjectProfileField row, Project project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fieldKey", fieldKey);
        out.put("label", LABELS.get(fieldKey));

        if (row == null) {
            // openedAt 无行时回落建档时间。'default' 只出现在响应里，库里永远不会有这个值。
            if (KEY_OPENED_AT.equals(fieldKey) && project != null && project.getCreatedAt() != null) {
                out.put("fieldValue", project.getCreatedAt().toLocalDate().toString());
                out.put("source", SOURCE_DEFAULT);
            } else {
                out.put("fieldValue", null);
                out.put("source", null);
            }
            out.put("confidence", null);
            out.put("evidence", null);
            out.put("updatedAt", null);
            return out;
        }

        out.put("fieldValue", row.getFieldValue());
        out.put("source", row.getSource());
        out.put("confidence", row.getConfidence());
        out.put("evidence", row.getEvidence());
        out.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return out;
    }
}
