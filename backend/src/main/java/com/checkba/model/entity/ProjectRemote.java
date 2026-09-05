package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一个项目与云端仓库的绑定关系。
 *
 * <p>两条唯一约束方向相反、缺一不可：{@code project_id} 唯一 = 一个本地项目至多接一个
 * 案件库；{@code (connection_id, remote_project_id)} 唯一 = 同一个案件库里的同一份案卷
 * 在本机至多落一份。后者是「换机器取回」的兜底——服务层已经先查后返，但先查后插不是
 * 原子的，并发点两下「取到本机」会真的造出两个本地项目，各带一个 origin 绑定，
 * 律师在两边各改一半而谁也不知道另一半的存在。
 */
@Entity
@Table(name = "project_remote",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"project_id"}),
                @UniqueConstraint(columnNames = {"connection_id", "remote_project_id"})
        })
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProjectRemote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long connectionId;

    private String remoteProjectId;

    private String lastSyncSha;

    @Column(nullable = false)
    private Boolean pendingUpload = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
