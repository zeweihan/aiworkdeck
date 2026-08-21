package com.checkba.service;

import com.checkba.model.entity.UserVariable;
import com.checkba.repository.UserVariableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserVariableService {

    @Autowired
    private UserVariableRepository repository;

    /**
     * 本 bean 的懒加载自身代理。同类方法互相调用不经 Spring 代理，@Transactional 会被静默
     * 绕过，所以重试必须经由它转发。字段注入 + @Lazy 打破自引用的构造期死环，
     * 写法与 ProjectVariableService.self / ProjectProfileService.self 同一套。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    UserVariableService self;


    public List<UserVariable> getVariablesByUser(Long userId) {
        return repository.findByUserId(userId);
    }

    /**
     * 先查后建，(user_id, name) 上有真实 DB 唯一约束。两个并发保存都读到空时都会走插入分支，
     * 第二个 INSERT 撞约束抛 DataIntegrityViolationException，被兜底处理器变成
     * 「服务器内部错误」——而用户做的只是同时在两处保存同名变量。
     * 本方法不带 @Transactional：撞约束会把当前事务标 rollback-only，同方法内 catch
     * 也救不回来（提交时照样抛 UnexpectedRollbackException）。重试经 self 转发到
     * REQUIRES_NEW 的新事务，再查一次就能读到对方已提交的那行、按更新语义写。
     */
    public UserVariable createOrUpdateVariable(Long userId, UserVariable variable) {
        try {
            return self.createOrUpdateVariableTx(userId, variable);
        } catch (org.springframework.dao.DataIntegrityViolationException
                | org.springframework.transaction.UnexpectedRollbackException e) {
            return self.createOrUpdateVariableTx(userId, variable);
        }
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UserVariable createOrUpdateVariableTx(Long userId, UserVariable variable) {
        UserVariable existing = repository.findByUserIdAndName(userId, variable.getName())
                .orElse(null);

        if (existing != null) {
            existing.setValue(variable.getValue());
            existing.setType(variable.getType());
            existing.setVariableGroup(variable.getVariableGroup());
            existing.setResolvedValue(variable.getValue());
            return repository.save(existing);
        } else {
            variable.setUserId(userId);
            variable.setResolvedValue(variable.getValue());
            return repository.save(variable);
        }
    }

    public void deleteVariable(Long userId, Long id) {
        // 简单所有权校验（防误删他人变量）
        UserVariable v = repository.findById(id).orElse(null);
        if (v == null) return;
        if (!userId.equals(v.getUserId())) return;
        repository.deleteById(id);
    }
}

