package com.checkba.repository;

import com.checkba.model.entity.UserActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserActivityLogRepository extends JpaRepository<UserActivityLog, Long> {
    // 限制返回最近 500 条：活动日志随每次页面访问/开关文件持续增长，无界全量查询有 OOM/慢查询风险；
    // "最近动态"语义天然有界，500 条足够展示。
    List<UserActivityLog> findTop500ByUserIdOrderByTimestampDesc(Long userId);
}
