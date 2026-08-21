package com.checkba.repository;

import com.checkba.model.entity.PluginJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PluginJobRepository extends JpaRepository<PluginJob, String> {

    List<PluginJob> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<PluginJob> findByStatusIn(Collection<String> statuses);
}
