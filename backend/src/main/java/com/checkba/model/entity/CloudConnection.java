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

    /**
     * 签发这条连接的官网账户指纹（{@code AccountService.accountFingerprintOrNull}）。
     * 只有官方案件库这条零配置直连路径会写：设备令牌是替某个官网账户换来的，
     * 换了账号必须重桥换令牌，否则新账号会顶着上一个账号的身份往案件库里交稿。
     * 手工填地址账号密码连的那条路上恒为空（那里的身份不是从官网账户派生的）。
     */
    @Column(length = 64)
    private String accountFingerprint;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
