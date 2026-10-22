package com.cobug.reviews;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cobug.notifications.NotificationRepository;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Repository-context cleanup for review/contribution data.
 * Contribution is derived from ReviewRequest rows, so deleting the review
 * history automatically removes it from contribution totals.
 */
@Service
public class ReviewCleanupService {
    private final ReviewRequestRepository reviews;
    private final ReviewFileRepository files;
    private final ReviewCommentRepository comments;
    private final NotificationRepository notifications;
    private final Path storageRoot;

    public ReviewCleanupService(
            ReviewRequestRepository reviews,
            ReviewFileRepository files,
            ReviewCommentRepository comments,
            NotificationRepository notifications,
            @Value("${cobug.storage.root:storage}") String storageRoot
    ) {
        this.reviews = reviews;
        this.files = files;
        this.comments = comments;
        this.notifications = notifications;
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public void deleteReview(UUID reviewId) {
        if (reviewId == null) return;
        comments.deleteByReviewId(reviewId);
        files.deleteByReviewId(reviewId);
        notifications.deleteByReferenceIdAndReferenceType(reviewId, "REVIEW_REQUEST");
        reviews.deleteByIdBulk(reviewId);
        deleteReviewDirectory(reviewId);
        cleanupEmptyReviewsRoot();
    }

    @Transactional
    public int clearProjectReviews(UUID projectId) {
        /*
         * Do not load ReviewRequest entities here.
         *
         * ReviewFile.beforeContent/afterContent are @Lob fields and older
         * review rows can contain large source files. Hibernate may try to
         * materialize those CLOBs while preparing an entity-based delete,
         * which can fail before the actual cleanup happens.
         *
         * We only need the review IDs for deleting child rows and filesystem
         * content, so obtain the lightweight IDs first and perform the parent
         * deletion as a bulk JPQL operation.
         */
        List<UUID> reviewIds = reviews.findIdsByProjectId(projectId);
        int count = reviewIds.size();

        for (UUID reviewId : reviewIds) {
            if (reviewId == null) continue;
            // Child rows must be removed before the parent review.
            comments.deleteByReviewId(reviewId);
            files.deleteByReviewId(reviewId);
            notifications.deleteByReferenceIdAndReferenceType(reviewId, "REVIEW_REQUEST");
            deleteReviewDirectory(reviewId);
        }

        // Bulk delete: never hydrate ReviewRequest/LOB content.
        if (!reviewIds.isEmpty()) {
            reviews.deleteAllByProjectIdBulk(projectId);
        }

        cleanupEmptyReviewsRoot();
        return count;
    }

    private void deleteReviewDirectory(UUID reviewId) {
        Path root = storageRoot.resolve("reviews").resolve(reviewId.toString()).normalize();
        if (!root.startsWith(storageRoot.resolve("reviews").normalize())) return;
        try {
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }

    private void cleanupEmptyReviewsRoot() {
        Path root = storageRoot.resolve("reviews").normalize();
        try {
            if (Files.isDirectory(root)) {
                try (var stream = Files.list(root)) {
                    if (!stream.findAny().isPresent()) Files.deleteIfExists(root);
                }
            }
        } catch (IOException ignored) {}
    }
}
