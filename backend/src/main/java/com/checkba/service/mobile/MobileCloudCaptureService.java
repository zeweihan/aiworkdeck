// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.mobile;
import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.service.ProjectFileService;
import com.checkba.service.LangText;
import com.checkba.storage.StorageServiceFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

/** The existing mobile upload protocol can target a persistent cloud project. */
@Service
public class MobileCloudCaptureService {
    private final MobileCloudReceiptRepository receipts;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final ProjectFileService files;
    private final StorageServiceFactory storage;
    private final CloudStorageQuotaService quota;
    private final ProjectCatalogService catalog;
    @org.springframework.beans.factory.annotation.Autowired
    private MobileMediaInboxRepository relayItems;

    public List<Map<String,Object>> status(Long userId, List<String> ids) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (String id : ids) receipts.findByUserIdAndClientMediaId(userId, id).ifPresent(receipt ->
                result.add(statusResponse(receipt)));
        return result;
    }
    public MobileCloudCaptureService(MobileCloudReceiptRepository receipts, ProjectRepository projects,
            UserRepository users, ProjectFileService files, StorageServiceFactory storage,
            CloudStorageQuotaService quota, ProjectCatalogService catalog) {
        this.receipts=receipts; this.projects=projects; this.users=users; this.files=files;
        this.storage=storage; this.quota=quota; this.catalog=catalog;
    }
    @Transactional
    public Map<String,Object> store(Long userId, String projectKey, String clientId, String name,
            String mediaType, LocalDateTime capturedAt, long size, InputStream content) {
        if (ProjectCatalogService.validUid(clientId)==null) throw new IllegalArgumentException("Invalid clientMediaId");
        Long projectId;
        try { projectId=Long.valueOf(projectKey); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid project id"); }
        Project project=projects.findById(projectId).filter(p->userId.equals(p.getUserId()))
                .orElseThrow(()->new IllegalArgumentException(LangText.of("项目不存在", "Project not found")));
        users.lockStorageOwner(userId).orElseThrow(()->new IllegalArgumentException("User not found"));
        if (relayItems.findByUserIdAndClientMediaId(userId,clientId).isPresent()) throw new IllegalArgumentException(
                LangText.of("这份采集已发送至设备", "This capture was already sent to a device"));
        Optional<MobileCloudReceipt> prior=receipts.findByUserIdAndClientMediaId(userId,clientId);
        if (prior.isPresent()) {
            if (!projectId.equals(prior.get().getProjectId())) throw new IllegalArgumentException(
                    LangText.of("这份采集已归档至另一项目", "This capture is already assigned to another project"));
            return response(prior.get());
        }
        if (!Set.of("audio","image","video","document").contains(mediaType)) throw new IllegalArgumentException("Invalid media type");
        if (size<=0 || size>CloudStorageQuotaService.RELAY_BYTES) throw new IllegalArgumentException(
                LangText.of("单文件上限为 200MB", "The file limit is 200MB"));
        quota.requirePersistentCapacity(userId,size);
        String clean=Objects.toString(name,"capture").replace('\\','/');
        clean=clean.substring(clean.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "_");
        if (clean.isBlank() || clean.equals(".") || clean.equals("..")) clean="capture";
        int dot=clean.lastIndexOf('.');
        String ext=dot>0 ? clean.substring(dot+1).toLowerCase(Locale.ROOT) : mediaType;
        String stem=dot>0 ? clean.substring(0,dot) : clean;
        if(stem.length()>100) stem=stem.substring(0,100);
        if(ext.length()>16) ext=mediaType;
        String landed=stem+"-"+clientId+"."+ext;
        String rootName="audio".equals(mediaType) ? "现场录音" : "document".equals(mediaType) ? "插件文档" : "现场影像";
        String day=(capturedAt==null?LocalDateTime.now():capturedAt).toLocalDate().toString();
        String path="projects/"+projectId+"/"+rootName+"/"+day+"/"+landed;
        String temp="projects/"+projectId+"/.capture-"+clientId+".tmp";
        var store=storage.getStorageService();
        boolean moved=false;
        try {
            SizedInput input=new SizedInput(content,size);
            store.save(temp,input);
            if(input.count!=size) throw new IOException("Capture length does not match declared size");
            store.move(temp,path); moved=true;
            // Filesystem writes are not rolled back by JPA. Remove this new blob on a failed commit too.
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) try { quota.removeRolledBackFile(projectId, path); }
                                catch (Exception error) {
                                    org.slf4j.LoggerFactory.getLogger(MobileCloudCaptureService.class)
                                            .error("Cloud capture rollback cleanup failed: {}", path, error);
                                }
                            }
                        });
            }
            ProjectFile folder=folder(projectId,null,rootName,userId);
            ProjectFile date=folder(projectId,folder.getId(),day,userId);
            ProjectFile file=files.createFile(projectId,date.getId(),landed,ext,size,path,null,userId);
            MobileCloudReceipt receipt=new MobileCloudReceipt();
            receipt.setUserId(userId); receipt.setClientMediaId(clientId); receipt.setProjectId(projectId);
            receipt.setProjectUid(catalog.cloudProjectUid(userId,projectId));
            receipt.setFileId(file.getId()); receipt.setFileUid(catalog.ensureFileUid(file));
            receipt.setCreatedAt(LocalDateTime.now()); receipts.saveAndFlush(receipt);
            return response(receipt);
        } catch (IOException|RuntimeException e) {
            try { store.delete(moved?path:temp); } catch (Exception ignored) {}
            throw new IllegalStateException(LangText.of("云端归档失败，请重试", "Cloud filing failed; please retry"),e);
        }
    }
    private ProjectFile folder(Long projectId,Long parent,String name,Long userId) {
        return files.getFilesByParent(projectId,parent).stream().filter(f->Boolean.TRUE.equals(f.getIsFolder())&&name.equals(f.getName()))
                .findFirst().orElseGet(()->files.createFolder(projectId,parent,name,userId));
    }
    private Map<String,Object> response(MobileCloudReceipt receipt) {
        return Map.of("code",0,"id",receipt.getId(),"clientMediaId",receipt.getClientMediaId(),
                "delivered",true,"projectUid",receipt.getProjectUid(),"fileId",receipt.getFileId(),"fileUid",receipt.getFileUid());
    }
    private Map<String,Object> statusResponse(MobileCloudReceipt receipt) {
        Map<String,Object> result=new LinkedHashMap<>(response(receipt));
        result.remove("code"); result.remove("id"); result.put("waitingSeconds",0L); return result;
    }
    private static class SizedInput extends FilterInputStream {
        final long expected; long count;
        SizedInput(InputStream input,long expected){super(input);this.expected=expected;}
        @Override public int read() throws IOException { int value=in.read();if(value>=0)check(1);return value; }
        @Override public int read(byte[] b,int off,int len)throws IOException {int n=in.read(b,off,len);if(n>0)check(n);return n;}
        void check(int n)throws IOException {count+=n;if(count>expected)throw new IOException("Capture exceeds declared size");}
    }
}
