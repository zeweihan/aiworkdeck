package com.checkba.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户实体
 *
 * 说明：
 * - 用于存储用户基本信息
 * - 支持用户对项目的管理和权限控制
 */
@Entity
@Table(name = "app_users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户名（唯一）
     */
    @Column(length = 64, nullable = false, unique = true)
    private String username;

    /**
     * 用户显示名称
     */
    @Column(length = 128, nullable = false)
    private String displayName;

    /**
     * 用户头像 URL（可选）
     */
    @Column(length = 512)
    private String avatarUrl;

    /**
     * 用户邮箱（可选）
     */
    @Column(length = 256)
    private String email;

    /**
     * 绑定手机号（可选，登录短信验证用；唯一，未绑定为 null）。
     * 大陆号存 11 位裸号（历史形态），境外号存 E.164（带 + 前缀）。
     */
    @Column(length = 32, unique = true)
    private String phone;

    /**
     * TOTP 认证器密钥（base32）。设置未完成时也已落库（待 totpEnabled 置真才生效）
     */
    @Column(length = 64)
    private String totpSecret;

    /**
     * TOTP 是否已启用（完成一次验证才置真）。
     * 用可空 Boolean 而非原始 boolean：ddl-auto=update 给已有数据的表加 NOT NULL 列会直接失败，
     * 且旧行读出的 NULL 灌进原始 boolean 会抛异常（真机升级即全站 500）。读取一律走 isTotpEnabled()。
     */
    @Column
    private Boolean totpEnabled;

    /**
     * 最近一次已消费的 TOTP 时间片序号，用于重放拦截（同一码不可用两次）
     */
    private Long totpLastUsedStep;

    /**
     * 用户密码（加密存储）
     * 注意：实际生产环境应使用 BCrypt 等加密算法
     */
    @Column(length = 256, nullable = false)
    private String password;

    /**
     * 用户角色
     * - USER: 普通用户
     * - ADMIN: 系统管理员
     */
    @Column(length = 32, nullable = false)
    private String role = "USER";

    /**
     * 订阅类型
     * - FREE: 免费用户
     * - PAID: 付费用户
     */
    @Column(length = 32, nullable = false)
    private String subscriptionType = "FREE";

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最近更新时间
     */
    private LocalDateTime updatedAt;

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    /** 历史行的 NULL 一律视为未启用。 */
    public boolean isTotpEnabled() {
        return Boolean.TRUE.equals(totpEnabled);
    }

    /** 原始值（含 null），仅供测试断言「未预置 false」这条迁移约束。 */
    public Boolean getTotpEnabledRaw() {
        return totpEnabled;
    }

    public void setTotpEnabled(boolean totpEnabled) {
        this.totpEnabled = totpEnabled;
    }

    public Long getTotpLastUsedStep() {
        return totpLastUsedStep;
    }

    public void setTotpLastUsedStep(Long totpLastUsedStep) {
        this.totpLastUsedStep = totpLastUsedStep;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getSubscriptionType() {
        return subscriptionType;
    }

    public void setSubscriptionType(String subscriptionType) {
        this.subscriptionType = subscriptionType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
