// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.DdItem;
import com.checkba.model.entity.DdRequest;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DdItemRepository;
import com.checkba.repository.DdRequestRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.CloudSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 把本机已有的尽调清单一次性推到案件库（dev-board#1050）。
 *
 * <p>案卷放进案件库之后清单以案件库为准：本机 {@code /api/dd/*} 整体代理过去
 * （{@code DdCloudProxyFilter}），本机库里原有的清单从此不再被读到。为了不让律师以前建的
 * 清单「消失」，在第一次代理读清单列表时把**尚未迁移**的本机清单推上去：清单名 → 清单项
 * （层级、标题、说明）→ 已上传的文件（走案件库既有的上传口）→ 审核状态。推完在本机那一行
 * 记下案件库一侧的 id（{@link DdRequest#getCloudRequestId()}），本机行不删。
 *
 * <p>判据是「本机有未迁移的清单」而不是「案件库上还没有清单」：同事先在案件库上建了清单时，
 * 后者会让这位律师本机的清单永远留在本机、谁也看不到。
 *
 * <p>不迁移的：清单项留言（作者会变成律师本人，冒名比缺失更糟）；迁移后文件的上传人
 * 记成律师在案件库上的账号。任何一步失败都整条清单回滚（尽力删掉案件库上建了一半的那条），
 * 下次读列表时重试；失败绝不挡住这次读列表本身。
 */
@Service
public class DdCloudMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DdCloudMigrationService.class);

    private final CloudSyncService cloudSyncService;
    private final DdRequestRepository requestRepository;
    private final DdItemRepository itemRepository;
    private final ProjectFileRepository fileRepository;
    private final StorageServiceFactory storageServiceFactory;

    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DdCloudMigrationService(CloudSyncService cloudSyncService,
                                   DdRequestRepository requestRepository,
                                   DdItemRepository itemRepository,
                                   ProjectFileRepository fileRepository,
                                   StorageServiceFactory storageServiceFactory) {
        this.cloudSyncService = cloudSyncService;
        this.requestRepository = requestRepository;
        this.itemRepository = itemRepository;
        this.fileRepository = fileRepository;
        this.storageServiceFactory = storageServiceFactory;
    }

    /** 推送本机尚未迁移的清单；返回这次推上去的条数。异常只记日志。 */
    public int migratePending(long projectId) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, k -> new ReentrantLock());
        lock.lock();
        try {
            List<DdRequest> pending = new ArrayList<>(requestRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                    .stream().filter(r -> r.getCloudRequestId() == null).toList());
            java.util.Collections.reverse(pending); // 先建的先推，案件库上的先后顺序与本机一致
            int done = 0;
            for (DdRequest r : pending) {
                try {
                    migrateOne(projectId, r);
                    done++;
                } catch (Exception e) {
                    log.warn("本机尽调清单迁移到案件库失败，下次读列表时重试: project={} request={}",
                            projectId, r.getId(), e);
                    break;
                }
            }
            if (done > 0) log.info("本机尽调清单已迁移到案件库: project={} count={}", projectId, done);
            return done;
        } finally {
            lock.unlock();
        }
    }

    private void migrateOne(long projectId, DdRequest request) throws Exception {
        JSONObject created = call(projectId, "POST", "/projects/" + projectId,
                JSONUtil.toJsonStr(Map.of("name", request.getName())));
        long remoteRequestId = created.getLong("id");
        try {
            List<DdItem> items = itemRepository.findByDdRequestIdOrderBySortOrderAsc(request.getId());
            Map<Long, Long> idMap = new HashMap<>();
            // 父项先建：按 sortOrder 反复扫，每轮建出父项已映射（或无父项）的那些
            List<DdItem> left = new ArrayList<>(items);
            while (!left.isEmpty()) {
                List<DdItem> ready = left.stream()
                        .filter(i -> i.getParentId() == null || idMap.containsKey(i.getParentId())
                                || items.stream().noneMatch(o -> o.getId().equals(i.getParentId())))
                        .toList();
                if (ready.isEmpty()) break; // 残缺的环：剩下的不迁（DdService 的环检测本不该放出这种数据）
                for (DdItem item : ready) {
                    Long parent = item.getParentId() == null ? null : idMap.get(item.getParentId());
                    Map<String, Object> addBody = new HashMap<>();
                    addBody.put("parentId", parent);
                    JSONObject added = call(projectId, "POST", "/requests/" + remoteRequestId + "/item",
                            JSONUtil.toJsonStr(addBody));
                    long remoteItemId = added.getLong("id");
                    idMap.put(item.getId(), remoteItemId);

                    Map<String, Object> info = new HashMap<>();
                    info.put("title", item.getTitle());
                    info.put("description", item.getDescription() == null ? "" : item.getDescription());
                    call(projectId, "PUT", "/items/" + remoteItemId + "/info", JSONUtil.toJsonStr(info));

                    boolean uploaded = item.getUploadedFileId() != null && uploadFile(projectId, item, remoteItemId);
                    // 审核结论只能落在已有附件的条目上（案件库的状态机，dev-board#1057）：附件没迁上去的
                    // 条目停在 PENDING；UPLOADED 由上传本身设好；驳回要带理由——本机从来没有驳回
                    // 界面、也不迁留言，只能写一句说明，免得整条清单迁移被 400 卡住、每次读列表都重试。
                    String status = item.getStatus();
                    if (uploaded && ("APPROVED".equals(status) || "REJECTED".equals(status))) {
                        Map<String, Object> body = new HashMap<>();
                        body.put("status", status);
                        if ("REJECTED".equals(status)) {
                            body.put("reason", "迁入案件库前已驳回（原理由未记录）");
                        }
                        call(projectId, "PUT", "/items/" + remoteItemId + "/status", JSONUtil.toJsonStr(body));
                    }
                }
                left.removeAll(ready);
            }
            request.setCloudRequestId(remoteRequestId);
            requestRepository.save(request);
        } catch (Exception e) {
            try {
                cloudSyncService.proxyDd(projectId, "DELETE", "/requests/" + remoteRequestId, null, null, null);
            } catch (Exception cleanup) {
                log.warn("回滚案件库上建了一半的清单失败: project={} remoteRequest={}", projectId, remoteRequestId, cleanup);
            }
            throw e;
        }
    }

    /** 附件走案件库既有的上传口；本机文件找不到就跳过（清单项仍迁，停在待上传）。 */
    private boolean uploadFile(long projectId, DdItem item, long remoteItemId) throws Exception {
        ProjectFile file = fileRepository.findById(item.getUploadedFileId()).orElse(null);
        if (file == null || file.getFilePath() == null) return false;
        byte[] bytes;
        try (InputStream in = storageServiceFactory.getStorageService().load(file.getFilePath()).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            log.warn("本机尽调附件读不出来，跳过: project={} item={}", projectId, item.getId(), e);
            return false;
        }
        String boundary = "----awd" + UUID.randomUUID().toString().replace("-", "");
        String name = file.getName() == null ? "upload" : file.getName().replace("\"", "_").replace("\r", "").replace("\n", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        CloudSyncService.RawResponse r = cloudSyncService.proxyDd(projectId, "POST",
                "/items/" + remoteItemId + "/upload", null, body.toByteArray(),
                "multipart/form-data; boundary=" + boundary);
        parse(r);
        return true;
    }

    private JSONObject call(long projectId, String method, String suffix, String json) {
        return parse(cloudSyncService.proxyDd(projectId, method, suffix, null,
                json == null ? null : json.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    /** DD 端点成功时回裸对象；失败是全站统一的 {code, message}（或 403）。 */
    private static JSONObject parse(CloudSyncService.RawResponse r) {
        String text = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
        if (r.status() != 200) {
            throw new IllegalStateException("案件库回 HTTP " + r.status());
        }
        JSONObject o = JSONUtil.parseObj(text);
        if (o.containsKey("code") && o.getInt("code", 0) != 0) {
            throw new IllegalStateException("案件库拒绝: " + o.getStr("message"));
        }
        return o;
    }
}
