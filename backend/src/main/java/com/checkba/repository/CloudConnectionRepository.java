package com.checkba.repository;

import com.checkba.model.entity.CloudConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CloudConnectionRepository extends JpaRepository<CloudConnection, Long> {

    List<CloudConnection> findByUserId(Long userId);
}
