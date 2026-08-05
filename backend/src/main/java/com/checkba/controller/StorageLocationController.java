package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.entitlement.FeatureCatalog;
import com.checkba.service.storage.StorageLocationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件存储位置（PR-C：{@code stage.unlimited} 的付费能力）。
 *
 * <p>闸分两级，因为「换到自选位置」和「知道自己数据在哪」是两件事：</p>
 * <ul>
 *   <li><b>单机模式</b>（三个端点都要）：搬的是本机磁盘目录，只在桌面单机版有意义。
 *       团队服务器上让任意成员从浏览器改服务端存储路径，等于把整台服务器的
 *       文件系统交出去（延续 local-folder-projects 的同款判断）。</li>
 *   <li><b>权益</b>（只有 POST 迁移要）：未拥有 {@code stage.unlimited} 不能选新位置。
 *       但 GET 与「恢复默认位置」<b>不设权益闸</b>：权益可能在自选位置生效之后失效
 *       （Key 被吊销、断开账户、离线超宽限），而 {@code applyOnStartup} 是无条件的，
 *       此时数据仍在自选路径上照常读写。若连查看路径都要权益，用户就再也看不到
 *       自己的文件在哪、也看不到「该目录当前不可访问」这句唯一的指路牌，
 *       更换不回默认位置——那是把人锁在一个既看不见也管不了的存储根上。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/storage/location")
public class StorageLocationController {

    private final StorageLocationService storageLocationService;
    private final EntitlementService entitlementService;
    private final boolean localMode;

    public StorageLocationController(StorageLocationService storageLocationService,
                                     EntitlementService entitlementService,
                                     @Value("${security.local-mode:false}") boolean localMode) {
        this.storageLocationService = storageLocationService;
        this.entitlementService = entitlementService;
        this.localMode = localMode;
    }

    /**
     * 当前位置。只读展示，不设权益闸（理由见类注释）。
     * 返回体里的 {@code entitled} 告诉前端能不能改位置——前端据此显示解锁引导而非藏起整块。
     */
    @GetMapping
    public Map<String, Object> current(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireLocalDesktop(sessionId);
        Map<String, Object> data = new HashMap<>(storageLocationService.current());
        data.put("entitled", entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED));
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    /**
     * 恢复默认位置。不搬也不删任何文件，只把指针换回默认目录，原目录内容原样留着。
     * 不设权益闸：这是退回免费版的默认状态，不发放任何付费能力，
     * 锁在付费墙后面只会让权益失效 + 磁盘拔掉的用户彻底出不来。
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireLocalDesktop(sessionId);
        Map<String, Object> data;
        try {
            data = storageLocationService.resetToDefault();
        } catch (com.checkba.storage.StorageException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "已恢复默认位置。原目录中的文件一个都没有删除，仍在原处。");
        result.put("data", data);
        return result;
    }

    @PostMapping
    public Map<String, Object> move(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody MoveRequest request) {
        requireLocalDesktop(sessionId);
        if (!entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED)) {
            throw new IllegalArgumentException("自选存储位置需要先解锁「文件缓存区无限版」");
        }
        Map<String, Object> data;
        try {
            data = storageLocationService.migrate(request == null ? null : request.getPath());
        } catch (com.checkba.storage.StorageException e) {
            // StorageException 默认落到通用处理器，被替换成「服务器内部错误」——那对用户毫无帮助：
            // 「请选择一个空目录」「目录不可写」正是他下一步该做什么。这里的每条文案都是
            // StorageLocationService 亲手写的、不含路径的用户语言，转成 IllegalArgumentException
            // 原样送出去是安全的（越界围栏等带路径的 StorageException 不经过这条路径）。
            throw new IllegalArgumentException(e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "已迁移。原目录保留为备份，确认无误后可自行删除。");
        result.put("data", data);
        return result;
    }

    private void requireLocalDesktop(String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            throw new UnauthorizedException("请先登录");
        }
        if (!localMode) {
            throw new IllegalArgumentException("该功能仅在本机单机版可用");
        }
    }

    public static class MoveRequest {
        private String path;

        public String getPath() { return path; }

        public void setPath(String path) { this.path = path; }
    }
}
