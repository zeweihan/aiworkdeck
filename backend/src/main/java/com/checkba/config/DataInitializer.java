package com.checkba.config;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据初始化器
 * 在应用启动时创建默认用户 admin/123，并将所有项目归到该用户下
 */
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    @Override
    public void run(String... args) {
        // 创建或获取默认用户 admin
        User adminUser = userRepository.findByUsername("admin")
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername("admin");
                    user.setPassword(com.checkba.service.UserService.encodePassword("123")); // 默认密码（BCrypt）
                    user.setDisplayName("管理员");
                    user.setCreatedAt(LocalDateTime.now());
                    user.setUpdatedAt(LocalDateTime.now());
                    return userRepository.save(user);
                });

        // 将所有项目都归到 admin 用户下（包括没有 userId、userId 为 0、或其他用户的）
        List<Project> allProjects = projectRepository.findAll();
        int updatedCount = 0;
        for (Project project : allProjects) {
            // 如果项目不属于 admin 用户，或者 userId 为空/0，则归到 admin 下
            if (project.getUserId() == null || project.getUserId() == 0 || !project.getUserId().equals(adminUser.getId())) {
                project.setUserId(adminUser.getId());
                project.setUpdatedAt(LocalDateTime.now());
                projectRepository.save(project);
                updatedCount++;
            }
        }
        
        if (updatedCount > 0) {
            System.out.println("已将 " + updatedCount + " 个项目归到 admin 用户下");
        } else if (!allProjects.isEmpty()) {
            System.out.println("所有项目已属于 admin 用户，共 " + allProjects.size() + " 个项目");
        } else {
            System.out.println("数据库中暂无项目");
        }

        System.out.println("数据初始化完成，默认用户: admin/123 (ID: " + adminUser.getId() + ")");
    }
}


