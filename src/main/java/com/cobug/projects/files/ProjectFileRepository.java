package com.cobug.projects.files;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, UUID> {

    @Query("""
            SELECT f
            FROM ProjectFile f
            WHERE f.project.id = :projectId
            ORDER BY f.path ASC
            """)
    List<ProjectFile> findProjectTree(
            @Param("projectId") UUID projectId
    );

    @Query("""
            SELECT f
            FROM ProjectFile f
            WHERE f.project.id = :projectId
            AND f.path = :path
            """)
    Optional<ProjectFile> findByProjectAndPath(
            @Param("projectId") UUID projectId,
            @Param("path") String path
    );

    @Query("""
            SELECT COUNT(f) > 0
            FROM ProjectFile f
            WHERE f.project.id = :projectId
            AND f.path = :path
            """)
    boolean existsByProjectAndPath(
            @Param("projectId") UUID projectId,
            @Param("path") String path
    );

    @Modifying
    @Query("""
            DELETE FROM ProjectFile f
            WHERE f.project.id = :projectId
            AND (
                f.path = :path
                OR f.path LIKE :prefix
            )
            """)
    void deletePathAndChildren(
            @Param("projectId") UUID projectId,
            @Param("path") String path,
            @Param("prefix") String prefix
    );
}
