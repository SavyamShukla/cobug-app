package com.cobug.reviews;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReviewCommentRepository extends JpaRepository<ReviewComment, UUID> { List<ReviewComment> findByReviewIdOrderByCreatedAtAsc(UUID reviewId); void deleteByReviewId(UUID reviewId); }
