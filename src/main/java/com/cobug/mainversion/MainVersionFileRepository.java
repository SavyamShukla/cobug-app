package com.cobug.mainversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface MainVersionFileRepository extends JpaRepository<MainVersionFile, UUID> {
    @Query("select f from MainVersionFile f join fetch f.sourceObject where f.version.id = :versionId order by f.path asc")
    List<MainVersionFile> findByVersionIdOrderByPathAsc(@Param("versionId") UUID versionId);

    @Modifying
    @Transactional
    @Query("delete from MainVersionFile f where f.version.project.id = :projectId")
    int deleteByProjectId(@Param("projectId") UUID projectId);

    @Modifying
    @Transactional
    @Query(value = "delete from main_source_objects o where not exists (select 1 from main_version_files f where f.source_object_id = o.id)", nativeQuery = true)
    int deleteOrphanSourceObjects(); }
