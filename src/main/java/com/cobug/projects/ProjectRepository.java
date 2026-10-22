package com.cobug.projects;

import com.cobug.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOwnerOrderByCreatedAtDesc(User owner);
}
