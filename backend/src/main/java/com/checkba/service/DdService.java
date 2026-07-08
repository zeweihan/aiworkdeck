package com.checkba.service;

import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DdService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DdService.class);

    private final DdRequestRepository ddRequestRepository;
    private final DdItemRepository ddItemRepository;
    private final DdCommentRepository ddCommentRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;

    /**
     * 获取项目的尽调请求列表
     */
    public List<DdRequest> getRequests(Long projectId) {
        return ddRequestRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * 获取请求详情（包含项）
     */
    public DdRequest getRequest(Long requestId) {
        return ddRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("请求不存在"));
    }

    /**
     * 获取请求下的所有项
     */
    public List<DdItem> getItems(Long requestId) {
        return ddItemRepository.findByDdRequestIdOrderBySortOrderAsc(requestId);
    }

    /**
     * 创建尽调请求（支持文本解析）
     */
    @Transactional
    public DdRequest createRequest(Long projectId, String name, String textContent, Long userId) {
        DdRequest request = new DdRequest();
        request.setProjectId(projectId);
        request.setName(name);
        request.setCreatedBy(userId);
        request.setStatus("DRAFT");
        request = ddRequestRepository.save(request);

        if (StringUtils.hasText(textContent)) {
            parseAndCreateItems(request.getId(), textContent);
        }

        return request;
    }

    /**
     * 解析文本并创建项
     */
    private void parseAndCreateItems(Long requestId, String textContent) {
        String[] lines = textContent.split("\n");
        int order = 0;
        // Count existing items to append correctly
        List<DdItem> existing = ddItemRepository.findByDdRequestIdOrderBySortOrderAsc(requestId);
        if (!existing.isEmpty()) {
            order = existing.get(existing.size() - 1).getSortOrder();
        }

        List<DdItem> items = new ArrayList<>();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // 简单解析：去除开头的数字序号（如 "1. " 或 "1、"）
            String title = line.replaceAll("^[0-9]+[\\.\\、\\s]*", "").trim();
            if (title.isEmpty()) continue;

            DdItem item = new DdItem();
            item.setDdRequestId(requestId);
            item.setTitle(title);
            item.setSortOrder(++order);
            item.setStatus("PENDING");
            items.add(item);
        }
        
        ddItemRepository.saveAll(items);
    }

    /**
     * 批量添加项（支持文本解析）
     */
    @Transactional
    public List<DdItem> addItems(Long requestId, String textContent) {
        // Verify request exists
        getRequest(requestId);
        
        if (StringUtils.hasText(textContent)) {
            parseAndCreateItems(requestId, textContent);
        }
        return getItems(requestId);
    }

    /**
     * 更新请求状态
     */
    @Transactional
    public DdRequest updateStatus(Long requestId, String status) {
        DdRequest request = getRequest(requestId);
        request.setStatus(status);
        return ddRequestRepository.save(request);
    }

    /**
     * 更新请求信息
     */
    @Transactional
    public DdRequest updateRequest(Long requestId, String name) {
        DdRequest request = getRequest(requestId);
        if (StringUtils.hasText(name)) {
            request.setName(name);
        }
        return ddRequestRepository.save(request);
    }

    /**
     * 客户上传文件
     */
    @Transactional
    public DdItem uploadFile(Long itemId, MultipartFile file, Long userId) throws IOException {
        DdItem item = ddItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("清单项不存在"));
        DdRequest request = getRequest(item.getDdRequestId());
        Long projectId = request.getProjectId();

        // 1. Root: "客户提供的文件"
        ProjectFile clientFolder = ensureFolder(projectId, null, "客户提供的文件", userId);

        // 2. Request Folder: "{RequestName}"
        ProjectFile requestFolder = ensureFolder(projectId, clientFolder.getId(), request.getName(), userId);

        // 3. Mirror Hierarchy: Traverse up to find path
        List<DdItem> hierarchy = new ArrayList<>();
        DdItem current = item;
        while (current != null) {
            hierarchy.add(0, current); // Add to front
            if (current.getParentId() != null) {
                current = ddItemRepository.findById(current.getParentId()).orElse(null);
            } else {
                current = null;
            }
        }
        
        // 4. Create folders along the path (EXCEPT the item itself, the file goes INTO the item's implied folder? 
        // User said: "DDList中的一级标题，对应abc文件夹中的一级子文件夹... 上传位置，即上传到上传所在项的父目录"
        // Wait, "上传所在项的父目录"?
        // Example: Root -> A -> B. Uploading to B.
        // If B is a "folder" in DD list (has children), do we put file inside B? 
        // Usually file attached to "End node".
        // Let's interpret: "Upload path corresponds to the Item's path".
        // If item is A/B/C, file should be at client_uploads/Request/A/B/C_filename? 
        // OR client_uploads/Request/A/B/filename (if C is the file itself?) -> But C is an Item.
        // Re-reading: "一级标题，对应abc文件夹中的一级子文件夹" -> Items ARE folders.
        // "上传位置，即上传到上传所在项的父目录" -> Uploading to Item C (which is child of B), file goes into Folder B.
        // Correct. Item C allows uploading A FILE. Item C IS the file placeholder?
        // If Item C is "Business License", we upload "license.pdf".
        // Path should be .../B/license.pdf? Or .../B/Business License/license.pdf?
        // "上传到上传所在项的父目录" -> If I am at "Basic Info (Folder) -> License (Item)", file goes to "Basic Info".
        // So we create folders for all PARENTS of the current item.
        
        ProjectFile targetFolder = requestFolder;
        // Iterate precursors (all except last one)
        // Hierarchy contains [Parent, Child, ItemItSelf]
        // We want folders for Parent, Child.
        if (hierarchy.size() > 1) {
            for (int i = 0; i < hierarchy.size() - 1; i++) {
                DdItem ancestor = hierarchy.get(i);
                targetFolder = ensureFolder(projectId, targetFolder.getId(), ancestor.getTitle(), userId);
            }
        }
        
        // 5. Save file
        String originalFilename = file.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(originalFilename);
        
        // Naming: Use original filename? Or Item title?
        // Usually keep original filename is safer, but maybe prefix with Item title if requested.
        // User didn't specify renaming file, just "Sync placement".
        // Let's use original filename.
        // 清洗客户端上传的原始文件名：只取文件名段、剥离路径分隔符与特殊字符（保留中文），
        // 防止 "../" 或绝对路径注入存储 key。客户端上传是外部可控入口；存储层另有围栏兜底。
        String rawName = (originalFilename == null) ? "" : originalFilename.replace("\\", "/");
        int slashIdx = rawName.lastIndexOf('/');
        if (slashIdx >= 0) rawName = rawName.substring(slashIdx + 1);
        rawName = rawName.replaceAll("[^A-Za-z0-9._\\u4e00-\\u9fa5-]", "_");
        if (!StringUtils.hasText(rawName) || ".".equals(rawName) || "..".equals(rawName)) {
            rawName = "upload";
        }
        String fileName = System.currentTimeMillis() + "_" + rawName;

        String storagePath = "projects/" + projectId + "/client_uploads/" + fileName;
        
        // Physical Save
        storageServiceFactory.getStorageService().save(storagePath, file.getInputStream());

        // 6. Create ProjectFile record in the target folder
        ProjectFile projectFile = new ProjectFile();
        projectFile.setProjectId(projectId);
        projectFile.setParentId(targetFolder.getId());
        projectFile.setIsFolder(false);
        projectFile.setName(originalFilename); // Display name
        projectFile.setFileType(extension);
        projectFile.setFileSize(file.getSize());
        projectFile.setFilePath(storagePath);
        projectFile.setWpsFileId(generateWpsFileId(projectId));
        projectFile.setSortOrder(0);
        projectFile.setUserId(userId);
        projectFile.setCreatedAt(LocalDateTime.now());
        projectFile.setUpdatedAt(LocalDateTime.now());
        projectFile = projectFileRepository.save(projectFile);

        // 7. Update DdItem
        item.setUploadedFileId(projectFile.getId());
        item.setUploadedAt(LocalDateTime.now());
        item.setUploadedBy(userId);
        item.setStatus("UPLOADED");
        
        return ddItemRepository.save(item);
    }

    private ProjectFile ensureFolder(Long projectId, Long parentId, String name, Long userId) {
        Optional<ProjectFile> folderOpt = projectFileRepository.findByProjectIdAndParentIdAndName(projectId, parentId, name);
        if (folderOpt.isPresent()) {
            return folderOpt.get();
        } else {
            return projectFileService.createFolder(projectId, parentId, name, userId);
        }
    }

    /**
     * 添加评论
     */
    @Transactional
    public DdComment addComment(Long itemId, Long userId, String content) {
        DdComment comment = new DdComment();
        comment.setDdItemId(itemId);
        comment.setUserId(userId);
        comment.setContent(content);
        return ddCommentRepository.save(comment);
    }

    /**
     * 获取评论
     */
    public List<DdComment> getComments(Long itemId) {
        return ddCommentRepository.findByDdItemIdOrderByCreatedAtAsc(itemId);
    }

    /**
     * 更新项状态（律师审核）
     */
    @Transactional
    public DdItem updateItemStatus(Long itemId, String status) {
        DdItem item = ddItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("清单项不存在"));
        item.setStatus(status);
        return ddItemRepository.save(item);
    }
    
    /**
     * 更新项信息（如描述）
     */
    @Transactional
    public DdItem updateItemInfo(Long itemId, String title, String description) {
        DdItem item = ddItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("清单项不存在"));
        if (title != null) item.setTitle(title);
        if (description != null) item.setDescription(description);
        return ddItemRepository.save(item);
    }

    /**
     * 创建单个项（支持层级）
     */
    @Transactional
    public DdItem addItem(Long requestId, Long parentId) {
        DdItem item = new DdItem();
        item.setDdRequestId(requestId);
        item.setTitle("新事项"); // Default title
        item.setStatus("PENDING");
        item.setParentId(parentId);
        
        // Calculate level and sortOrder
        if (parentId != null) {
            DdItem parent = ddItemRepository.findById(parentId).orElseThrow(() -> new IllegalArgumentException("父项不存在"));
            item.setLevel(parent.getLevel() + 1);
        } else {
            item.setLevel(0);
        }

        // Simple sort order: max + 1 (simplified for now, ideally should handle insertion)
        // Find existing items to determine sort order
        // For now, just put at end of list
        List<DdItem> items = getItems(requestId);
        int maxOrder = items.stream().mapToInt(DdItem::getSortOrder).max().orElse(0);
        item.setSortOrder(maxOrder + 1);

        return ddItemRepository.save(item);
    }

    /**
     * 移动项（变父节点/缩进）
     */
    @Transactional
    public DdItem moveItem(Long itemId, Long newParentId) {
        DdItem item = ddItemRepository.findById(itemId).orElseThrow(() -> new IllegalArgumentException("项不存在"));
        
        if (newParentId != null) {
            // Check loop
            if (itemId.equals(newParentId)) throw new IllegalArgumentException("不能移动到自己下面");
            // TODO: check deeper loops if needed
            
            DdItem newParent = ddItemRepository.findById(newParentId).orElseThrow(() -> new IllegalArgumentException("父项不存在"));
            item.setParentId(newParentId);
            item.setLevel(newParent.getLevel() + 1);
        } else {
            item.setParentId(null);
            item.setLevel(0);
        }
        
        return ddItemRepository.save(item);
    }


    /**
     * 删除项
     * 逻辑：
     * 1. 检查该项是否有关联的上传文件
     * 2. 如果有，且文件未被移动（路径匹配），则物理删除文件
     * 3. 尝试删除该项对应的文件夹（如果存在且未移动，且为空或仅包含上述文件）
     * 4. 递归删除子项
     * 5. 删除 DB 记录
     */
    @Transactional
    public void deleteItem(Long itemId, Long userId) {
        DdItem item = ddItemRepository.findById(itemId).orElse(null);
        if (item == null) return;
        
        // Handle Children first (Recursion)
        List<DdItem> children = ddItemRepository.findByDdRequestIdOrderBySortOrderAsc(item.getDdRequestId())
                .stream().filter(i -> itemId.equals(i.getParentId())).collect(Collectors.toList());
        for (DdItem child : children) {
            deleteItem(child.getId(), userId);
        }
        
        // Handle File linked to this item
        if (item.getUploadedFileId() != null) {
            verifyAndSafeDeleteFile(item, userId);
        }
        
        // Handle the Folder for this item
        verifyAndSafeDeleteFolder(item, userId);

        // Delete Comments
        List<DdComment> comments = ddCommentRepository.findByDdItemIdOrderByCreatedAtAsc(itemId);
        ddCommentRepository.deleteAll(comments);

        // Delete Item
        ddItemRepository.delete(item);
    }
    
    /**
     * 删除整个清单
     */
    @Transactional
    public void deleteRequest(Long requestId, Long userId) {
        DdRequest request = ddRequestRepository.findById(requestId).orElse(null);
        if (request == null) return;
        
        // Delete all items first (to clean up files)
        List<DdItem> items = ddItemRepository.findByDdRequestIdOrderBySortOrderAsc(requestId);
        List<DdItem> roots = items.stream().filter(i -> i.getParentId() == null).collect(Collectors.toList());
        for (DdItem root : roots) {
            deleteItem(root.getId(), userId);
        }
        
        // Try to delete the Request Folder
        verifyAndSafeDeleteRequestFolder(request, userId);
        
        ddRequestRepository.delete(request);
    }

    /**
     * 复制整个清单
     */
    @Transactional
    public DdRequest copyRequest(Long requestId, Long userId) {
        DdRequest original = getRequest(requestId);
        
        // 1. Create new request record
        DdRequest copy = new DdRequest();
        copy.setProjectId(original.getProjectId());
        copy.setName("【副本】" + original.getName());
        copy.setCreatedBy(userId);
        copy.setStatus("DRAFT");
        copy = ddRequestRepository.save(copy);
        
        // 2. Copy Items (preserving hierarchy)
        List<DdItem> items = ddItemRepository.findByDdRequestIdOrderBySortOrderAsc(requestId);
        copyItemsRecursively(items, null, copy.getId(), null);
        
        return copy;
    }

    private void copyItemsRecursively(List<DdItem> allItems, Long originalParentId, Long newRequestId, Long newParentId) {
        List<DdItem> children = allItems.stream()
                .filter(i -> (originalParentId == null && i.getParentId() == null) || 
                            (originalParentId != null && originalParentId.equals(i.getParentId())))
                .collect(Collectors.toList());
        
        for (DdItem item : children) {
            DdItem itemCopy = new DdItem();
            itemCopy.setDdRequestId(newRequestId);
            itemCopy.setTitle(item.getTitle());
            itemCopy.setDescription(item.getDescription());
            itemCopy.setStatus("PENDING");
            itemCopy.setSortOrder(item.getSortOrder());
            itemCopy.setParentId(newParentId);
            itemCopy.setLevel(item.getLevel());
            itemCopy.setExampleFileId(item.getExampleFileId()); // Copy example reference if exists
            
            itemCopy = ddItemRepository.save(itemCopy);
            
            // Recurse for its children
            copyItemsRecursively(allItems, item.getId(), newRequestId, itemCopy.getId());
        }
    }
    
    // --- Helper Methods for Safe Delete ---

    private void verifyAndSafeDeleteFile(DdItem item, Long userId) {
        Long fileId = item.getUploadedFileId();
        Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) return;
        
        ProjectFile file = fileOpt.get();
        
        // Check 1: Is it in the expected hierarchy?
        String expectedRelative = buildExpectedRelativePath(item); // "客户提供的文件/Req/P/Item" (Folder)
        
        String actualPath = file.getFilePath();
        String expectedPathPrefix = "projects/" + file.getProjectId() + "/" + expectedRelative + "/";
        
        String fullExpectedPath = expectedPathPrefix + file.getName();
        
        String normActual = actualPath.replace("\\", "/");
        String normExpected = fullExpectedPath.replace("\\", "/");
        
        if (normActual.equals(normExpected)) {
            try {
                projectFileService.delete(fileId, userId);
            } catch (Exception e) {
                log.warn("Failed to delete DD file: {}", fileId, e);
            }
        } else {
            log.info("DD File appears moved, skipping physical delete: Expected={}, Actual={}", normExpected, normActual);
        }
    }
    
    private void verifyAndSafeDeleteFolder(DdItem item, Long userId) {
        String relativePath = buildExpectedRelativePath(item);
        Long projectId = getRequest(item.getDdRequestId()).getProjectId();
        
        ProjectFile folder = findFolderByPath(projectId, relativePath);
        
        if (folder != null) {
            try {
                projectFileService.delete(folder.getId(), userId);
            } catch (Exception e) {
                log.warn("Failed to delete DD Item Folder: {}", folder.getId(), e);
            }
        }
    }
    
    private void verifyAndSafeDeleteRequestFolder(DdRequest request, Long userId) {
        String relativePath = "客户提供的文件/" + request.getName();
        ProjectFile folder = findFolderByPath(request.getProjectId(), relativePath);
        if (folder != null) {
            try {
                 projectFileService.delete(folder.getId(), userId);
            } catch (Exception e) {
                log.warn("Failed to delete DD Request Folder", e);
            }
        }
    }

    private String buildExpectedRelativePath(DdItem item) {
        List<DdItem> stack = new ArrayList<>();
        DdItem curr = item;
        while (curr != null) {
            stack.add(0, curr);
            if (curr.getParentId() != null) {
                curr = ddItemRepository.findById(curr.getParentId()).orElse(null);
            } else {
                curr = null;
            }
        }
        DdRequest req = getRequest(item.getDdRequestId());
        StringBuilder sb = new StringBuilder();
        sb.append("客户提供的文件/").append(req.getName());
        for (DdItem i : stack) {
            sb.append("/").append(i.getTitle());
        }
        return sb.toString();
    }
    
    private ProjectFile findFolderByPath(Long projectId, String relativePath) {
        String[] parts = relativePath.split("/");
        Long parentId = null;
        ProjectFile current = null;
        
        for (String part : parts) {
            if (part.isEmpty()) continue;
            Optional<ProjectFile> p = projectFileRepository.findByProjectIdAndParentIdAndName(projectId, parentId, part);
            if (p.isPresent() && Boolean.TRUE.equals(p.get().getIsFolder())) {
                current = p.get();
                parentId = current.getId();
            } else {
                return null;
            }
        }
        return current;
    }
        private String generateWpsFileId(Long projectId) {
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format("project_%d_doc_%d_%s", projectId, System.currentTimeMillis(), rand);
    }
}
