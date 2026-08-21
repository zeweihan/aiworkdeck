package com.checkba.service;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.repository.ProjectVariableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectVariableService {

    @Autowired
    private ProjectVariableRepository repository;

    /**
     * 本 bean 的懒加载自身代理，只为让 createOrUpdateVariable 的重试真正经过 Spring 的事务
     * 代理开出一个新事务——同类方法互相调用（this.xxxTx(...)）不经代理，@Transactional
     * 会被静默绕过。字段注入 + @Lazy 打破自引用的构造期死环，写法与 ProjectProfileService.self
     * 同一套（该类头部注释有更完整的踩坑记录）。
     */
    @Autowired
    @Lazy
    ProjectVariableService self;

    public List<ProjectVariable> getVariablesByProject(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    /** 返回变量所属项目 id（用于越权校验），不存在返回 null。 */
    public Long getProjectIdById(Long id) {
        return repository.findById(id).map(ProjectVariable::getProjectId).orElse(null);
    }

    /**
     * 先查后建，(project_id, name) 上有真实 DB 唯一约束。两个并发请求都读到空 Optional
     * 时都会落入插入分支，第二个物理 INSERT 撞约束抛 DataIntegrityViolationException——
     * 若这段逻辑本身带 @Transactional，撞约束会在方法出口把当前事务标记 rollback-only，
     * 同方法内 catch 住异常继续执行也救不回来，方法能正常返回但提交时照样抛
     * UnexpectedRollbackException（ProjectProfileService 类头部注释记录了这个踩过的坑）。
     *
     * 于是本方法不带 @Transactional，重试通过 {@link #self} 转发到 REQUIRES_NEW 的
     * {@link #createOrUpdateVariableTx}，两次尝试各自落在独立的新事务里：第二次重试进
     * 新事务后再 find，能读到对方已提交的那一行，按更新语义写、保留它的 id/创建者信息。
     */
    public ProjectVariable createOrUpdateVariable(ProjectVariable variable) {
        try {
            return self.createOrUpdateVariableTx(variable);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            return self.createOrUpdateVariableTx(variable);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProjectVariable createOrUpdateVariableTx(ProjectVariable variable) {
        ProjectVariable existing = repository.findByProjectIdAndName(variable.getProjectId(), variable.getName())
                .orElse(null);

        if (existing != null) {
            existing.setValue(variable.getValue());
            existing.setType(variable.getType());
            existing.setVariableGroup(variable.getVariableGroup());
            // Preserve creator info if not present in update
            if (variable.getCreatorId() != null) {
                existing.setCreatorId(variable.getCreatorId());
                existing.setCreatorName(variable.getCreatorName());
            }

            // TODO: Implement template parsing logic for nested variables
            if ("TEXT".equals(variable.getType())) {
                existing.setResolvedValue(variable.getValue());
            } else {
                existing.setResolvedValue(variable.getValue());
            }
            return repository.save(existing);
        } else {
            if ("TEXT".equals(variable.getType())) {
                variable.setResolvedValue(variable.getValue());
            } else {
                variable.setResolvedValue(variable.getValue());
            }
            return repository.save(variable);
        }
    }

    public void deleteVariable(Long id) {
        repository.deleteById(id);
    }
}

