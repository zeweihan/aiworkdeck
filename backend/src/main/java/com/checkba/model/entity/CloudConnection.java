package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 本机连接到的一个云端账号（服务端 + 登录身份 + 设备令牌）。 */
@Entity
@Table(name = "cloud_connection")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CloudConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * 连接归属人。设备令牌是长期凭证，多人共用一个后端时必须按人隔离，
     * 否则任何登录用户都能拿别人的令牌列/克隆对方的云端项目。
     * 本列是后加的，升级前建的旧行为空——旧行一律拒用，请重新连接一次。
     */
    @Column
    private Long userId;

    @Column(nullable = false, length = 255)
    private String serverUrl;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(length = 128)
    private String displayName;

    @Column(nullable = false, length = 128)
    private String deviceToken;

    /** 服务端设备令牌行的 id（用于 disconnect 撤销），旧行可能为空。 */
    @Column
    private Long tokenId;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
