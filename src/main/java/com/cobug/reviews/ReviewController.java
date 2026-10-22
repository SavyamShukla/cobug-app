package com.cobug.reviews;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/reviews")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @PathVariable UUID projectId,
            Authentication a
    ) {
        return service.list(projectId, a.getName());
    }

    @GetMapping("/{reviewId}")
    public Map<String, Object> get(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            Authentication a
    ) {
        return service.get(projectId, reviewId, a.getName());
    }

    @PostMapping
    public Map<String, Object> submit(
            @PathVariable UUID projectId,
            @RequestBody ReviewService.SubmitRequest request,
            Authentication a
    ) {
        return service.submit(projectId, a.getName(), request);
    }

    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            Authentication a
    ) {
        service.deleteWork(projectId, reviewId, a.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{reviewId}/start")
    public Map<String, Object> start(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            Authentication a
    ) {
        return service.startReview(projectId, reviewId, a.getName());
    }

    @GetMapping("/{reviewId}/test-package")
    public Map<String, Object> testPackage(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestParam(required = false) UUID mainVersionId,
            Authentication a
    ) {
        return service.testPackage(projectId, reviewId, a.getName(), mainVersionId);
    }

    @GetMapping("/{reviewId}/test-package/download")
    public ResponseEntity<byte[]> downloadTestPackage(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestParam(required = false) UUID mainVersionId,
            Authentication a
    ) {
        byte[] bytes = service.downloadTestPackage(projectId, reviewId, a.getName(), mainVersionId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=COBUG-review-test-" + reviewId + ".zip")
                .header("Content-Type", "application/zip")
                .body(bytes);
    }

    @PostMapping("/{reviewId}/request-changes")
    public Map<String, Object> requestChanges(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody(required = false) ReviewService.DecisionRequest request,
            Authentication a
    ) {
        return service.requestChanges(
                projectId,
                reviewId,
                a.getName(),
                request == null ? "" : request.comment()
        );
    }

    @PostMapping("/{reviewId}/approve")
    public Map<String, Object> approve(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody(required = false) ReviewService.DecisionRequest request,
            Authentication a
    ) {
        return service.approve(
                projectId,
                reviewId,
                a.getName(),
                request == null ? "" : request.comment()
        );
    }

    @PostMapping("/{reviewId}/reject")
    public Map<String, Object> reject(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody(required = false) ReviewService.DecisionRequest request,
            Authentication a
    ) {
        return service.reject(
                projectId,
                reviewId,
                a.getName(),
                request == null ? "" : request.comment()
        );
    }

    @PostMapping("/{reviewId}/merge")
    public Map<String, Object> merge(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            Authentication a
    ) {
        return service.merge(projectId, reviewId, a.getName());
    }

    @GetMapping("/{reviewId}/file")
    public ResponseEntity<Map<String, String>> file(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestParam String path,
            Authentication a
    ) {
        return ResponseEntity.ok(
                service.fileContent(
                        projectId,
                        reviewId,
                        path,
                        a.getName()
                )
        );
    }

    @PostMapping("/{reviewId}/comments")
    public Map<String, Object> comment(
            @PathVariable UUID projectId,
            @PathVariable UUID reviewId,
            @RequestBody ReviewService.CommentRequest request,
            Authentication a
    ) {
        return service.addComment(
                projectId,
                reviewId,
                a.getName(),
                request
        );
    }

    @GetMapping("/contributions")
    public Map<String, Object> contributions(
            @PathVariable UUID projectId,
            Authentication a
    ) {
        return service.contribution(
                projectId,
                a.getName()
        );
    }
}