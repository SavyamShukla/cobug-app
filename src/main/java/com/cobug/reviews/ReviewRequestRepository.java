package com.cobug.reviews;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, UUID> {
    List<ReviewRequest> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<ReviewRequest> findByProjectIdAndAuthorIdOrderByCreatedAtDesc(UUID projectId, UUID authorId);
    long countByProjectIdAndAuthorId(UUID projectId, UUID authorId);

    @Query("select r.id from ReviewRequest r where r.projectId = :projectId")
    List<UUID> findIdsByProjectId(@Param("projectId") UUID projectId);

    /**
     * Bulk-deletes review rows without loading ReviewRequest entities.
     *
     * This is intentionally used by repository-disconnect cleanup because old
     * review records can contain very large @Lob source fields. Loading those
     * entities through Hibernate during cleanup can cause JDBC CLOB extraction
     * failures before the delete is performed.
     */
    @Modifying
    @Query("delete from ReviewRequest r where r.id = :reviewId")
    int deleteByIdBulk(@Param("reviewId") UUID reviewId);

    @Modifying
    @Query("delete from ReviewRequest r where r.projectId = :projectId")
    int deleteAllByProjectIdBulk(@Param("projectId") UUID projectId);
}
