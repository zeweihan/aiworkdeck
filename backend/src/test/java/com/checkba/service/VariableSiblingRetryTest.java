package com.checkba.service;

import com.checkba.model.entity.FileVariable;
import com.checkba.model.entity.UserVariable;
import com.checkba.repository.FileVariableRepository;
import com.checkba.repository.UserVariableRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户变量与文件变量的「先查后建」撞唯一约束。
 *
 * <p>这两个服务与 ProjectVariableService 是同一形状、同样有真实 DB 唯一约束
 * （user_id+name / file_id+name）。两个并发保存都读到空时都会走插入分支，
 * 第二个 INSERT 撞约束抛 DataIntegrityViolationException，被兜底处理器变成
 * 「服务器内部错误」——而用户做的只是同时在两处保存同名变量。
 *
 * <p>事务语义那一层（为什么重试必须落在 REQUIRES_NEW 的新事务里）已由
 * {@code ProjectVariableConcurrentSaveTest} 用真容器 + 真 H2 + 真唯一约束钉住；
 * 这里只钉「撞约束后确实会重试一次、并返回对方已提交的那一行」这条行为，
 * 不重复搭那套重量级脚手架。
 */
class VariableSiblingRetryTest {

    @Test
    @DisplayName("用户变量：撞唯一约束后重试并返回既有行，而不是把异常抛给用户")
    void userVariableRetriesOnConstraintViolation() {
        UserVariableRepository repo = mock(UserVariableRepository.class);
        UserVariableService svc = new UserVariableService();
        ReflectionTestUtils.setField(svc, "repository", repo);
        ReflectionTestUtils.setField(svc, "self", svc);

        UserVariable winner = new UserVariable();
        winner.setId(7L);
        winner.setUserId(1L);
        winner.setName("甲方");

        AtomicInteger finds = new AtomicInteger();
        when(repo.findByUserIdAndName(1L, "甲方")).thenAnswer(inv ->
                finds.incrementAndGet() == 1 ? Optional.empty() : Optional.of(winner));
        when(repo.save(any(UserVariable.class))).thenAnswer(inv -> {
            UserVariable v = inv.getArgument(0);
            if (v.getId() == null) throw new DataIntegrityViolationException("unique(user_id,name)");
            return v;
        });

        UserVariable input = new UserVariable();
        input.setName("甲方");
        input.setValue("北京某某公司");

        UserVariable saved = svc.createOrUpdateVariable(1L, input);
        assertSame(winner, saved, "重试后应落到对方已提交的那一行上做更新");
        assertEquals("北京某某公司", saved.getValue());
        assertEquals(2, finds.get(), "必须重查一次，否则拿不到对方刚提交的行");
    }

    @Test
    @DisplayName("文件变量：撞唯一约束后重试并返回既有行")
    void fileVariableRetriesOnConstraintViolation() {
        FileVariableRepository repo = mock(FileVariableRepository.class);
        FileVariableService svc = new FileVariableService(repo);
        ReflectionTestUtils.setField(svc, "self", svc);

        FileVariable winner = new FileVariable();
        winner.setId(9L);
        winner.setFileId(3L);
        winner.setName("乙方");

        AtomicInteger finds = new AtomicInteger();
        when(repo.findByFileIdAndName(3L, "乙方")).thenAnswer(inv ->
                finds.incrementAndGet() == 1 ? Optional.empty() : Optional.of(winner));
        when(repo.save(any(FileVariable.class))).thenAnswer(inv -> {
            FileVariable v = inv.getArgument(0);
            if (v.getId() == null) throw new DataIntegrityViolationException("unique(file_id,name)");
            return v;
        });

        FileVariable input = new FileVariable();
        input.setFileId(3L);
        input.setName("乙方");
        input.setValue("上海某某公司");

        FileVariable saved = svc.createOrUpdateVariable(input);
        assertSame(winner, saved);
        assertEquals("上海某某公司", saved.getValue());
        assertEquals(2, finds.get());
    }
}
