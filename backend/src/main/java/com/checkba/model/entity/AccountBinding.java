package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 官网账户 → server 用户的绑定（插件云后端 awdk 桥）。
 *
 * 映射键是官网返回的稳定 accountId（不是 username——username 可改名，也不是 Key 哈希——
 * Key 可轮换；两者做键都会在变更后凭空生出第二个 server 用户）。
 * awdk_ Key 明文**不落库**：每次 awdk-login 都实时向官网重验，本表只存映射关系。
 */
@Entity
@Table(name = "account_binding",
        uniqueConstraints = @UniqueConstraint(columnNames = {"externalAccountId"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AccountBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** 官网侧的稳定账户标识（契约待官网实施，见 doc/desktop-contract.md）。 */
    @Column(nullable = false, length = 128)
    private String externalAccountId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;
}
