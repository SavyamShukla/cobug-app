package com.cobug.reviews;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReviewFileRepository extends JpaRepository<ReviewFile, UUID> { List<ReviewFile> findByReviewIdOrderByPathAsc(UUID reviewId); void deleteByReviewId(UUID reviewId); }
