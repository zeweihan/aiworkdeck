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
     * 绑定手机号（可选，登录短信验证用；唯一，未绑定为 null）
     */
    @Column(length = 32, unique = true)
    private String phone;

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
