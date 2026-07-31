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

    @Column(nullable = false, length = 255)
    private String serverUrl;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(length = 128)
    private String displayName;

    @Column(nullable = false, length = 128)
    private String deviceToken;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
