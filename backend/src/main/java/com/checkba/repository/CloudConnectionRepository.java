package com.checkba.repository;

import com.checkba.model.entity.CloudConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CloudConnectionRepository extends JpaRepository<CloudConnection, Long> {

    List<CloudConnection> findByUserId(Long userId);

    /**
     * 官方案件库的幂等键：每个本机用户对同一个案件库地址至多一条连接。
     * 没有数据库唯一约束兜底（手工连接那条路允许同一地址连多次不同账号），
     * 所以取 first 而不是要求唯一。
     */
    Optional<CloudConnection> findFirstByUserIdAndServerUrl(Long userId, String serverUrl);
}
