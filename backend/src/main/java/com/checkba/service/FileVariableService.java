package com.checkba.service;

import com.checkba.model.entity.FileVariable;
import com.checkba.repository.FileVariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FileVariableService {

    private final FileVariableRepository fileVariableRepository;

    /**
     * 本 bean 的懒加载自身代理。同类方法互相调用不经 Spring 代理，@Transactional 会被静默
     * 绕过，所以重试必须经由它转发。字段注入 + @Lazy 打破自引用的构造期死环，
     * 写法与 ProjectVariableService.self / ProjectProfileService.self 同一套。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    FileVariableService self;


    public List<FileVariable> getVariables(Long fileId) {
        return fileVariableRepository.findByFileId(fileId);
    }

    /**
     * 先查后建，(file_id, name) 上有真实 DB 唯一约束。理由与做法同
     * {@link UserVariableService#createOrUpdateVariable}：撞约束会把当前事务标 rollback-only，
     * 同方法内 catch 救不回来，重试必须落在经 self 转发出来的新事务里。
     */
    public FileVariable createOrUpdateVariable(FileVariable variable) {
        try {
            return self.createOrUpdateVariableTx(variable);
        } catch (org.springframework.dao.DataIntegrityViolationException
                | org.springframework.transaction.UnexpectedRollbackException e) {
            return self.createOrUpdateVariableTx(variable);
        }
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public FileVariable createOrUpdateVariableTx(FileVariable variable) {
        return fileVariableRepository.findByFileIdAndName(variable.getFileId(), variable.getName())
                .map(existing -> {
                    existing.setValue(variable.getValue());
                    existing.setType(variable.getType());
                    existing.setResolvedValue(variable.getResolvedValue());
                    existing.setVariableGroup(variable.getVariableGroup());
                    return fileVariableRepository.save(existing);
                })
                .orElseGet(() -> fileVariableRepository.save(variable));
    }

    public void deleteVariable(Long id) {
        fileVariableRepository.deleteById(id);
    }

    /** 返回文件变量绑定的 fileId（用于越权校验：fileId→projectId），不存在返回 null。 */
    public Long getFileIdById(Long id) {
        return fileVariableRepository.findById(id).map(FileVariable::getFileId).orElse(null);
    }
}
