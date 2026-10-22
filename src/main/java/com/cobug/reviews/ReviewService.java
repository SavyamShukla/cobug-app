package com.cobug.reviews;

import com.cobug.activity.ProjectActivityService;
import com.cobug.auth.User;
import com.cobug.auth.UserRepository;
import com.cobug.github.GithubDtos;
import com.cobug.github.GithubService;
import com.cobug.notifications.NotificationService;
import com.cobug.projects.*;
import com.cobug.storage.ProjectStorageService;
import com.cobug.storage.StoredFileInfo;
import com.cobug.mainversion.MainVersion;
import com.cobug.mainversion.MainVersionRepository;
import com.cobug.mainversion.MainVersionFile;
import com.cobug.mainversion.MainVersionFileRepository;
import com.cobug.mainversion.MainVersionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private final ReviewRequestRepository reviews;
    private final ReviewFileRepository files;
    private final ReviewCommentRepository comments;
    private final ProjectAccessService access;
    private final ProjectMemberRepository members;
    private final UserRepository users;
    private final GithubService github;
    private final ProjectActivityService activity;
    private final NotificationService notifications;
    private final ProjectStorageService storage;
    private final Path storageRoot;
    private final MainVersionRepository mainVersions;
    private final MainVersionFileRepository mainVersionFiles;
    private final MainVersionService mainVersionService;
    private final ReviewCleanupService reviewCleanupService;

    public ReviewService(
            ReviewRequestRepository reviews,
            ReviewFileRepository files,
            ReviewCommentRepository comments,
            ProjectAccessService access,
            ProjectMemberRepository members,
            UserRepository users,
            GithubService github,
            ProjectActivityService activity,
            NotificationService notifications,
            ProjectStorageService storage,
            @Value("${cobug.storage.root:storage}") String storageRoot,
            MainVersionRepository mainVersions,
            MainVersionFileRepository mainVersionFiles,
            MainVersionService mainVersionService,
            ReviewCleanupService reviewCleanupService
    ) {
        this.reviews = reviews;
        this.files = files;
        this.comments = comments;
        this.access = access;
        this.members = members;
        this.users = users;
        this.github = github;
        this.activity = activity;
        this.notifications = notifications;
        this.storage = storage;
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.mainVersions = mainVersions;
        this.mainVersionFiles = mainVersionFiles;
        this.mainVersionService = mainVersionService;
        this.reviewCleanupService = reviewCleanupService;
    }

    public record SubmitFile(String path, String content, String baseSha, String baseCommitSha, boolean deleted) {}
    public record SubmitRequest(UUID reviewId, String title, String description, UUID reviewerId, UUID baseMainVersionId, String baseCommitSha, List<SubmitFile> files) {}
    public record DecisionRequest(String comment) {}
    public record CommentRequest(String body, String filePath) {}

    @Transactional
    public Map<String, Object> submit(UUID projectId, String email, SubmitRequest request) {
        final String trace = "[REVIEW][SUBMIT]";
        log.info("{} ENTER projectId={} email={} requestPresent={} reviewId={} fileCount={} reviewerId={} baseMainVersionId={} baseCommitShaPresent={}",
                trace,
                projectId,
                email,
                request != null,
                request == null ? null : request.reviewId(),
                request == null || request.files() == null ? null : request.files().size(),
                request == null ? null : request.reviewerId(),
                request == null ? null : request.baseMainVersionId(),
                request != null && request.baseCommitSha() != null && !request.baseCommitSha().isBlank());

        try {
            log.info("{} STEP 01 requireMember START", trace);
            User author = access.requireMember(projectId, email);
            log.info("{} STEP 01 requireMember OK authorId={} authorName={}", trace, author.getId(), author.getName());

            log.info("{} STEP 03 getProject START", trace);
            Project project = access.getProject(projectId);
            log.info("{} STEP 03 getProject OK projectName={}", trace, project.getName());

            if (request == null || request.files() == null || request.files().isEmpty()) {
                log.warn("{} STOP no files to submit", trace);
                throw bad("There are no local changes to submit for review.");
            }

            String title = request.title() == null || request.title().isBlank() ? "Workspace changes" : request.title().trim();
            if (title.length() > 240) {
                log.warn("{} STOP title too long length={}", trace, title.length());
                throw bad("Review title is too long.");
            }

            log.info("{} STEP 04 resolveReviewer START requestedReviewerId={}", trace, request.reviewerId());
            User reviewer = resolveReviewer(projectId, request.reviewerId(), project);
            log.info("{} STEP 04 resolveReviewer OK reviewerId={} reviewerName={}", trace, reviewer.getId(), reviewer.getName());

            List<SubmitFile> submitted = request.files().stream().filter(Objects::nonNull).toList();
            log.info("{} STEP 05 validate submitted list OK count={}", trace, submitted.size());
            if (submitted.size() > 500) throw bad("A review can contain at most 500 changed files.");

            MainVersion baseVersion = null;
            if (request.baseMainVersionId() != null) {
                log.info("{} STEP 06 load requested MainVersion START id={}", trace, request.baseMainVersionId());
                baseVersion = mainVersions.findById(request.baseMainVersionId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Base Main version not found."));
                if (!baseVersion.getProject().getId().equals(projectId)) throw bad("Base Main version does not belong to this project.");
                log.info("{} STEP 06 load requested MainVersion OK versionId={} versionNumber={}",
                        trace, baseVersion.getId(), baseVersion.getVersionNumber());
            } else {
                log.info("{} STEP 06 no baseMainVersionId supplied", trace);
            }

            ReviewRequest review;
            boolean resubmission = request.reviewId() != null;
            Map<String, ReviewFile> previousReviewFiles = new HashMap<>();
            log.info("{} STEP 07 determine flow resubmission={}", trace, resubmission);

            if (resubmission) {
                log.info("{} STEP 08 load existing review START reviewId={}", trace, request.reviewId());
                review = review(projectId, request.reviewId());
                log.info("{} STEP 08 load existing review OK status={} authorId={} authenticatedAuthorId={}",
                        trace, review.getStatus(), review.getAuthorId(), author.getId());

                if (!review.getAuthorId().equals(author.getId())) {
                    log.error("{} STOP 403 ownership mismatch reviewAuthorId={} authenticatedAuthorId={}",
                            trace, review.getAuthorId(), author.getId());
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only resubmit your own review.");
                }

                if (review.getStatus() != ReviewStatus.CHANGES_REQUESTED && review.getStatus() != ReviewStatus.SUBMITTED) {
                    log.warn("{} STOP invalid resubmission status={}", trace, review.getStatus());
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This Work can no longer be updated. Create a new Work after review completion.");
                }

                review.setUpdateNumber((review.getUpdateNumber() == null ? 0 : review.getUpdateNumber()) + 1);

                log.info("{} STEP 09 load previous ReviewFiles START", trace);
                for (ReviewFile previous : files.findByReviewIdOrderByPathAsc(review.getId())) {
                    previousReviewFiles.put(previous.getPath(), previous);
                }
                log.info("{} STEP 09 load previous ReviewFiles OK count={}", trace, previousReviewFiles.size());

                if (baseVersion == null && review.getBaseMainVersionId() != null) {
                    log.info("{} STEP 10 restore stored Main baseline START id={}", trace, review.getBaseMainVersionId());
                    baseVersion = mainVersions.findById(review.getBaseMainVersionId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The Work's Main baseline is no longer available."));
                    if (!baseVersion.getProject().getId().equals(projectId)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The Work's Main baseline does not belong to this project.");
                    }
                    log.info("{} STEP 10 restore stored Main baseline OK versionId={} versionNumber={}",
                            trace, baseVersion.getId(), baseVersion.getVersionNumber());
                }

                if (baseVersion == null) {
                    log.info("{} STEP 10 no MainVersion baseline stored; previous ReviewFile snapshots will be used", trace);
                }
            } else {
                log.info("{} STEP 08 NEW REVIEW PATH", trace);
                log.info("{} STEP 09 duplicateActiveReview START", trace);
                if (duplicateActiveReview(projectId, author.getId(), submitted)) {
                    log.warn("{} STOP duplicate active review", trace);
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The same Workspace changes are already waiting in an active review.");
                }
                log.info("{} STEP 09 duplicateActiveReview OK", trace);

                review = new ReviewRequest();
                review.setProjectId(projectId);
                review.setAuthorId(author.getId());
                review.setAuthorName(author.getName());
                review.setWorkNumber((int) reviews.countByProjectIdAndAuthorId(projectId, author.getId()) + 1);
                review.setUpdateNumber(0);
            }

            review.setReviewerId(reviewer.getId());
            review.setReviewerName(reviewer.getName());
            review.setTitle(title);
            review.setDescription(request.description() == null ? "" : request.description().trim());
            review.setStatus(ReviewStatus.SUBMITTED);
            review.setValidationStatus(ReviewValidationStatus.NOT_RUN);
            review.setValidationMainVersionId(null);
            review.setValidationMainVersionNumber(null);
            review.setApprovedPoints(0);
            review.setMergedAt(null);
            review.setMergedCommitSha(null);
            review.setMergedMainVersionId(null);
            review.setMergedMainVersionNumber(null);

            if (baseVersion != null) {
                review.setBaseMainVersionId(baseVersion.getId());
                review.setBaseMainVersionNumber(baseVersion.getVersionNumber());
                review.setBaseCommitSha(baseVersion.getPushedCommitSha());
            } else if (request.baseCommitSha() != null && !request.baseCommitSha().isBlank()) {
                review.setBaseCommitSha(request.baseCommitSha().trim());
            }

            log.info("{} STEP 11 baseline accepted baseVersionId={} baseVersionNumber={} baseCommitShaPresent={}",
                    trace,
                    baseVersion == null ? null : baseVersion.getId(),
                    baseVersion == null ? null : baseVersion.getVersionNumber(),
                    review.getBaseCommitSha() != null && !review.getBaseCommitSha().isBlank());

            long additions = 0, deletions = 0, points = 0;
            String baseCommit = baseVersion == null ? null : baseVersion.getPushedCommitSha();
            List<ReviewFile> createdFiles = new ArrayList<>();

            try {
                if (resubmission) {
                    log.info("{} STEP 12 delete previous ReviewFiles START reviewId={}", trace, review.getId());
                    files.deleteByReviewId(review.getId());
                    deleteReviewSnapshotFiles(review.getId());
                    log.info("{} STEP 12 delete previous ReviewFiles OK", trace);
                }

                log.info("{} STEP 13 create review storage directory START", trace);
                Files.createDirectories(reviewRootPlaceholder());
                reviews.saveAndFlush(review);
                Path reviewRoot = reviewRoot(review.getId());
                Files.createDirectories(reviewRoot);
                log.info("{} STEP 13 create review storage directory OK reviewId={} root={}", trace, review.getId(), reviewRoot);

                int index = 0;
                for (SubmitFile input : submitted) {
                    index++;
                    String path = normalizePath(input.path());
                    log.info("{} STEP 14 file {}/{} START path={} deleted={} baseShaPresent={} contentLength={}",
                            trace, index, submitted.size(), path, input.deleted(),
                            input.baseSha() != null && !input.baseSha().isBlank(),
                            input.content() == null ? 0 : input.content().length());

                    if (path.isBlank()) {
                        log.warn("{} file {}/{} skipped: blank path", trace, index, submitted.size());
                        continue;
                    }
                    if (path.length() > 2000) throw bad("A workspace path is too long.");
                    if (path.equalsIgnoreCase(".git") || path.startsWith(".git/")) {
                        log.info("{} file {}/{} skipped: .git path", trace, index, submitted.size());
                        continue;
                    }

                    String content = input.content() == null ? "" : input.content();
                    if (input.deleted()) content = "";
                    if (content.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024) {
                        throw bad("Review files are limited to 10 MB each in this V1 review flow.");
                    }

                    String baseSha = input.baseSha() == null ? "" : input.baseSha().trim();
                    String before = "";
                    boolean exists = false;
                    GithubDtos.FileResponse current = null;

                    if (baseVersion != null) {
                        log.info("{} file {} STEP A read MainVersion baseline START path={} versionId={}",
                                trace, index, path, baseVersion.getId());
                        MainVersionFile baselineFile = null;
                        for (MainVersionFile mf : mainVersionFiles.findByVersionIdOrderByPathAsc(baseVersion.getId())) {
                            if (mf.getPath().equals(path)) { baselineFile = mf; break; }
                        }

                        if (baselineFile != null) {
                            var sourceObject = baselineFile.getSourceObject();
                            exists = true;
                            boolean binaryOrExternal = sourceObject != null &&
                                    (sourceObject.isBinaryArtifact() || "LOCAL_STORAGE".equalsIgnoreCase(sourceObject.getStorageType()));
                            before = sourceObject == null || sourceObject.getContent() == null ? "" : sourceObject.getContent();
                            String storedGitBlobSha = sourceObject == null ? null : sourceObject.getGitBlobSha();
                            String expectedSha = storedGitBlobSha == null || storedGitBlobSha.isBlank()
                                    ? gitBlobSha(before) : storedGitBlobSha;

                            log.info("{} file {} STEP A baseline OK exists=true binaryOrExternal={} beforeLength={} storedGitBlobSha={} expectedSha={} receivedBaseSha={}",
                                    trace, index, binaryOrExternal, before.length(), storedGitBlobSha, expectedSha, baseSha);

                            // Binary/LOCAL_STORAGE artifacts keep an empty marker in PostgreSQL.
                            // Their real Git blob SHA lives in MainSourceObject.gitBlobSha.
                            if (!baseSha.isBlank() && !baseSha.equals(expectedSha)) {
                                log.warn("{} file {} STOP baseline SHA mismatch expected={} received={} binaryOrExternal={}",
                                        trace, index, expectedSha, baseSha, binaryOrExternal);
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        path + " changed locally from its Main baseline. Recreate the edit from the latest Main version.");
                            }

                            // The current desktop Work protocol does not transmit binary bytes as UTF-8 text.
                            // If the original Git blob SHA still matches Main and no bytes were supplied,
                            // this binary is unchanged. Do not create an empty ReviewFile for it.
                            if (binaryOrExternal && !input.deleted() && !baseSha.isBlank()
                                    && baseSha.equals(expectedSha)
                                    && (input.content() == null || input.content().isEmpty())) {
                                log.info("{} file {} SKIP unchanged binary/external artifact path={} sha={}",
                                        trace, index, path, expectedSha);
                                continue;
                            }

                            if (binaryOrExternal && !input.deleted()
                                    && (input.content() == null || input.content().isEmpty())) {
                                log.warn("{} file {} STOP binary/external artifact has no updated bytes path={}", trace, index, path);
                                throw bad(path + " is a binary project file, but its updated bytes were not available from the Workspace.");
                            }
                        } else {
                            log.info("{} file {} STEP A MainVersion baseline does not contain path={}", trace, index, path);
                            if (!baseSha.isBlank()) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        path + " no longer exists in the selected Main version.");
                            }
                        }
                    } else if (resubmission && previousReviewFiles.containsKey(path)) {
                        log.info("{} file {} STEP B reuse previous ReviewFile baseline START path={}", trace, index, path);
                        ReviewFile previous = previousReviewFiles.get(path);
                        before = previous.getBeforeContent() == null ? "" : previous.getBeforeContent();
                        exists = !"ADDED".equalsIgnoreCase(previous.getChangeType());
                        current = null;
                        log.info("{} file {} STEP B reuse previous ReviewFile baseline OK exists={} beforeLength={} previousChangeType={}",
                                trace, index, exists, before.length(), previous.getChangeType());
                    } else {
                        if (resubmission) {
                            if (!baseSha.isBlank()) {
                                log.warn("{} file {} LEGACY RESUBMIT path={} has no stored baseline; SKIPPING GitHub lookup because baseSha is present",
                                        trace, index, path);
                                continue;
                            }
                            before = "";
                            exists = false;
                        } else {
                            log.info("{} file {} STEP C github.file START path={} authenticatedEmail={}", trace, index, path, email);
                            try {
                                current = github.file(projectId, email, path, null);
                                exists = true;
                                log.info("{} file {} STEP C github.file OK sha={} commitSha={}",
                                        trace, index, current.sha(), current.commitSha());
                            } catch (ResponseStatusException ex) {
                                log.error("{} file {} STEP C github.file FAILED status={} reason={} message={}",
                                        trace, index, ex.getStatusCode(), ex.getReason(), ex.getMessage(), ex);
                                if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                                    current = null;
                                    exists = false;
                                    log.info("{} file {} github.file treated as NOT_FOUND; file is new", trace, index);
                                } else {
                                    throw ex;
                                }
                            }
                            if (exists && !baseSha.isBlank() && !baseSha.equals(current.sha())) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        path + " changed in GitHub after your Workspace was created. Refresh and resolve the conflict before submitting.");
                            }
                            if (!exists && !baseSha.isBlank()) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        path + " no longer exists in GitHub Main.");
                            }
                            before = exists && current != null ? current.content() : "";
                        }
                    }

                    if (!input.deleted() && exists && Objects.equals(before, content)) {
                        log.info("{} file {} SKIP unchanged path={}", trace, index, path);
                        continue;
                    }
                    if (input.deleted() && !exists) {
                        log.info("{} file {} SKIP delete of missing path={}", trace, index, path);
                        continue;
                    }

                    String changeType = input.deleted() ? "DELETED" : (exists ? "MODIFIED" : "ADDED");
                    DiffStats diff = diff(before, content);
                    long filePoints = Math.max(1, diff.additions + diff.deletions) + 10;
                    ReviewFile file = new ReviewFile();
                    file.setReviewId(review.getId());
                    file.setPath(path);
                    file.setChangeType(changeType);
                    String reviewBaseSha = null;
                    if (exists) {
                        MainVersionFile baselineForSha = null;
                        if (baseVersion != null) {
                            for (MainVersionFile mf : mainVersionFiles.findByVersionIdOrderByPathAsc(baseVersion.getId())) {
                                if (mf.getPath().equals(path)) { baselineForSha = mf; break; }
                            }
                        }
                        if (baselineForSha != null && baselineForSha.getSourceObject() != null
                                && baselineForSha.getSourceObject().getGitBlobSha() != null
                                && !baselineForSha.getSourceObject().getGitBlobSha().isBlank()) {
                            reviewBaseSha = baselineForSha.getSourceObject().getGitBlobSha();
                        } else {
                            reviewBaseSha = gitBlobSha(before);
                        }
                    }
                    file.setBaseSha(reviewBaseSha);
                    file.setContentSha(sha256(content.getBytes(StandardCharsets.UTF_8)));
                    file.setAdditions(diff.additions);
                    file.setDeletions(diff.deletions);
                    file.setSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
                    file.setBeforeContent(before);
                    file.setAfterContent(content);

                    log.info("{} file {} STEP D save ReviewFile START path={} changeType={}", trace, index, path, changeType);
                    files.save(file);
                    if (!input.deleted()) writeReviewContent(review.getId(), path, content);
                    createdFiles.add(file);
                    additions += diff.additions;
                    deletions += diff.deletions;
                    points += filePoints;
                    if (baseCommit == null && baseVersion == null && current != null) baseCommit = current.commitSha();
                    log.info("{} file {} STEP D save ReviewFile OK path={} additions={} deletions={}",
                            trace, index, path, diff.additions, diff.deletions);
                }

                log.info("{} STEP 15 file processing complete createdFiles={}", trace, createdFiles.size());
                if (createdFiles.isEmpty()) {
                    reviews.delete(review);
                    throw bad("No actual changes were found compared with Main.");
                }

                if (review.getBaseCommitSha() == null || review.getBaseCommitSha().isBlank()) {
                    if (baseCommit != null && !baseCommit.isBlank()) {
                        log.info("{} STEP 16 set baseCommitSha from captured file commit", trace);
                        review.setBaseCommitSha(baseCommit);
                    } else if (baseVersion == null) {
                        if (github.isConnected(projectId)) {
                            try {
                                review.setBaseCommitSha(github.currentCommit(projectId, email, null));
                            } catch (Exception ex) {
                                log.warn("{} github.currentCommit failed: {}", trace, ex.getMessage());
                            }
                        }
                    }
                }

                review.setFilesChanged(createdFiles.size());
                review.setAdditions(additions);
                review.setDeletions(deletions);
                review.setTotalPoints(points);
                review.setApprovedPoints(0);

                log.info("{} STEP 17 save review START reviewId={} status={}", trace, review.getId(), review.getStatus());
                reviews.save(review);
                log.info("{} STEP 17 save review OK", trace);

                log.info("{} STEP 18 activity.record START", trace);
                activity.record(projectId, author, "SUBMITTED_REVIEW", "REVIEW", review.getId().toString(),
                        "Submitted review '" + review.getTitle() + "' with " + createdFiles.size() + " changed file(s)");
                log.info("{} STEP 18 activity.record OK", trace);

                log.info("{} STEP 19 notifications.create START reviewerId={} authorId={}",
                        trace, reviewer.getId(), author.getId());
                notifications.create(reviewer.getId(), author.getId(), "REVIEW_SUBMITTED", "Review submitted",
                        author.getName() + " submitted '" + review.getTitle() + "' for review in " + project.getName() + ".",
                        review.getId(), "REVIEW_REQUEST");
                log.info("{} STEP 19 notifications.create OK", trace);

                log.info("{} STEP 20 detail START", trace);
                Map<String, Object> result = detail(projectId, email, review);
                log.info("{} SUCCESS reviewId={} status={} filesChanged={}",
                        trace, review.getId(), review.getStatus(), createdFiles.size());
                return result;

            } catch (RuntimeException ex) {
                log.error("{} INNER FAILURE reviewId={} type={} status={} message={}",
                        trace,
                        review == null ? null : review.getId(),
                        ex.getClass().getName(),
                        ex instanceof ResponseStatusException rse ? rse.getStatusCode() : "N/A",
                        ex.getMessage(),
                        ex);
                cleanupReviewSnapshot(review.getId());
                throw ex;
            } catch (IOException ex) {
                log.error("{} INNER IO FAILURE reviewId={} message={}", trace,
                        review == null ? null : review.getId(), ex.getMessage(), ex);
                cleanupReviewSnapshot(review.getId());
                throw new IllegalStateException("Unable to create review snapshot.", ex);
            }

        } catch (ResponseStatusException ex) {
            log.error("{} FINAL HTTP FAILURE status={} reason={} message={}", trace,
                    ex.getStatusCode(), ex.getReason(), ex.getMessage(), ex);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("{} FINAL UNEXPECTED FAILURE type={} message={}", trace,
                    ex.getClass().getName(), ex.getMessage(), ex);
            throw ex;
        } finally {
            log.info("{} EXIT projectId={} reviewId={}",
                    trace, projectId, request == null ? null : request.reviewId());
        }
    }

    @Transactional
    public List<Map<String,Object>> list(UUID projectId, String email) {
        access.requireMember(projectId, email);
        return reviews.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(UUID projectId, UUID reviewId, String email) {
        access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        return detail(projectId, email, review);
    }

    @Transactional
    public Map<String,Object> startReview(UUID projectId, UUID reviewId, String email) {
        User actor = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        requireReviewerOrOwner(projectId, actor, review);
        if (review.getStatus() == ReviewStatus.SUBMITTED) review.setStatus(ReviewStatus.IN_REVIEW);
        reviews.save(review);
        return detail(projectId, email, review);
    }

    @Transactional
    public Map<String,Object> requestChanges(UUID projectId, UUID reviewId, String email, String comment) {
        User actor = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        requireReviewerOrOwner(projectId, actor, review);
        String clean = cleanComment(comment);
        if (clean.isBlank()) throw bad("Please explain what changes are required.");
        review.setStatus(ReviewStatus.CHANGES_REQUESTED);
        review.setReviewerComment(clean);
        review.setValidationStatus(ReviewValidationStatus.NOT_RUN);
        review.setApprovedPoints(0);
        reviews.save(review);
        notifications.create(review.getAuthorId(), actor.getId(), "REVIEW_CHANGES_REQUESTED", "Changes requested",
                actor.getName() + " requested changes on '" + review.getTitle() + "'.", review.getId(), "REVIEW_REQUEST");
        activity.record(projectId, actor, "REQUESTED_REVIEW_CHANGES", "REVIEW", reviewId.toString(), cleanComment(comment));
        return detail(projectId, email, review);
    }

    @Transactional
    public Map<String,Object> approve(UUID projectId, UUID reviewId, String email, String comment) {
        User actor = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        requireReviewerOrOwner(projectId, actor, review);
        if (review.getStatus() != ReviewStatus.IN_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start the review before approving it.");
        }
        review.setStatus(ReviewStatus.APPROVED);
        review.setApprovedPoints(review.getTotalPoints());
        review.setReviewerComment(cleanComment(comment));
        reviews.save(review);
        notifications.create(review.getAuthorId(), actor.getId(), "REVIEW_APPROVED", "Review approved",
                actor.getName() + " approved '" + review.getTitle() + "'.", review.getId(), "REVIEW_REQUEST");
        activity.record(projectId, actor, "APPROVED_REVIEW", "REVIEW", reviewId.toString(), "Approved review '" + review.getTitle() + "'");
        return detail(projectId, email, review);
    }

    @Transactional
    public Map<String,Object> reject(UUID projectId, UUID reviewId, String email, String comment) {
        User actor = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        requireReviewerOrOwner(projectId, actor, review);
        review.setStatus(ReviewStatus.REJECTED);
        review.setApprovedPoints(0);
        review.setReviewerComment(cleanComment(comment));
        reviews.save(review);
        notifications.create(review.getAuthorId(), actor.getId(), "REVIEW_REJECTED", "Review rejected",
                actor.getName() + " rejected '" + review.getTitle() + "'.", review.getId(), "REVIEW_REQUEST");
        activity.record(projectId, actor, "REJECTED_REVIEW", "REVIEW", reviewId.toString(), "Rejected review '" + review.getTitle() + "'");
        return detail(projectId, email, review);
    }

    @Transactional
    public Map<String,Object> merge(UUID projectId, UUID reviewId, String email) {
        User actor = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        requireReviewerOrOwner(projectId, actor, review);
        if (review.getStatus() != ReviewStatus.APPROVED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an approved review can be merged.");

        List<ReviewFile> reviewFiles = files.findByReviewIdOrderByPathAsc(reviewId);
        List<GithubDtos.MergeChange> changes = new ArrayList<>();
        for (ReviewFile file : reviewFiles) {
            if ("DELETED".equalsIgnoreCase(file.getChangeType())) {
                changes.add(new GithubDtos.MergeChange(file.getPath(), "", file.getBaseSha(), true));
            } else {
                String after = file.getAfterContent();
                if (after == null) {
                    try { after = Files.readString(reviewFilePath(reviewId, file.getPath()), StandardCharsets.UTF_8); }
                    catch (IOException legacy) { throw new IllegalStateException("Unable to read review changeset: " + file.getPath(), legacy); }
                }
                changes.add(new GithubDtos.MergeChange(file.getPath(), after, file.getBaseSha(), false));
            }
        }

        // Final concurrency guard: compare every submitted file with the current
        // Main state. If Main changed the same file after this Work was submitted,
        // do not silently overwrite the newer work. Unrelated changes are safe.
        MainVersion currentMain = mainVersions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No current Main exists."));
        Map<String, MainVersionFile> currentFiles = new HashMap<>();
        for (MainVersionFile mf : mainVersionFiles.findByVersionIdOrderByPathAsc(currentMain.getId())) {
            currentFiles.put(mf.getPath(), mf);
        }
        List<String> conflicts = new ArrayList<>();
        for (ReviewFile file : reviewFiles) {
            MainVersionFile current = currentFiles.get(file.getPath());
            String currentSha = current == null ? null : current.getSourceObject().getGitBlobSha();
            String submittedBaseSha = file.getBaseSha();
            String type = String.valueOf(file.getChangeType()).toUpperCase(Locale.ROOT);
            if ("ADDED".equals(type)) {
                if (current != null) conflicts.add(file.getPath());
            } else if ("DELETED".equals(type)) {
                if (current != null && submittedBaseSha != null && !submittedBaseSha.isBlank() && !submittedBaseSha.equals(currentSha)) conflicts.add(file.getPath());
            } else if (submittedBaseSha != null && !submittedBaseSha.isBlank() && !submittedBaseSha.equals(currentSha)) {
                conflicts.add(file.getPath());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This work conflicts with newer changes in Main: " + String.join(", ", conflicts) + ". Resolve the affected files before merging.");
        }

        Map<String,Object> mergedMain = mainVersionService.mergeChangesIntoMain(
                projectId, email, changes, "Merged review " + review.getId() + " · " + review.getTitle());
        review.setStatus(ReviewStatus.MERGED);
        review.setMergedAt(Instant.now());
        review.setApprovedPoints(review.getTotalPoints());
        review.setMergedCommitSha(null);
        Object mainVersionId = mergedMain.get("id");
        Object mainVersionNumber = mergedMain.get("versionNumber");
        if (mainVersionId instanceof UUID id) review.setMergedMainVersionId(id);
        if (mainVersionNumber instanceof Number n) review.setMergedMainVersionNumber(n.longValue());
        reviews.save(review);

        notifications.create(review.getAuthorId(), actor.getId(), "REVIEW_MERGED", "Review merged",
                actor.getName() + " merged '" + review.getTitle() + "' into COBUG Main.", review.getId(), "REVIEW_REQUEST");
        activity.record(projectId, actor, "MERGED_REVIEW", "REVIEW", reviewId.toString(),
                "Merged review '" + review.getTitle() + "' into COBUG Main v" + review.getMergedMainVersionNumber() + ". GitHub was not changed.");
        return detail(projectId, email, review);
    }

    @Transactional
    public void deleteWork(UUID projectId, UUID reviewId, String email) {
        User actor = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);

        boolean author = actor.getId().equals(review.getAuthorId());
        boolean reviewerOrOwner = actor.getId().equals(review.getReviewerId()) || isReviewer(projectId, actor.getId());
        if (!author && !reviewerOrOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not authorized to delete this Work.");
        }
        if (review.getStatus() == ReviewStatus.MERGED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A Work that has already been merged cannot be deleted.");
        }

        log.info("[REVIEW][DELETE] deleting reviewId={} projectId={} actorId={} authorId={} status={}",
                reviewId, projectId, actor.getId(), review.getAuthorId(), review.getStatus());
        reviewCleanupService.deleteReview(reviewId);
        log.info("[REVIEW][DELETE] deleted reviewId={} completely", reviewId);
    }

    @Transactional(readOnly = true)
    public Map<String,String> fileContent(UUID projectId, UUID reviewId, String path, String email) {
        access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        String clean = normalizePath(path);
        ReviewFile storedFile = files.findByReviewIdOrderByPathAsc(reviewId).stream().filter(f -> f.getPath().equals(clean)).findFirst().orElse(null);
        if (storedFile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review file not found.");
        String after = storedFile.getAfterContent();
        String before = storedFile.getBeforeContent();
        try {
            if (after == null && !"DELETED".equalsIgnoreCase(storedFile.getChangeType())) {
                after = Files.readString(reviewFilePath(reviewId, clean), StandardCharsets.UTF_8);
            }
            if (before == null && review.getBaseCommitSha() != null && !review.getBaseCommitSha().isBlank()) {
                try {
                    GithubDtos.FileResponse base = github.file(projectId, email, clean, review.getBaseCommitSha());
                    before = base.content() == null ? "" : base.content();
                } catch (ResponseStatusException ignored) { before = ""; }
            }
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review file not found."); }
        return Map.of("before", before == null ? "" : before, "after", after == null ? "" : after);
    }

    @Transactional
    public Map<String,Object> addComment(UUID projectId, UUID reviewId, String email, CommentRequest request) {
        User author = access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        if (request == null || request.body() == null || request.body().isBlank()) throw bad("Comment cannot be empty.");
        ReviewComment comment = new ReviewComment();
        comment.setReviewId(reviewId); comment.setAuthorId(author.getId()); comment.setAuthorName(author.getName());
        comment.setFilePath(request.filePath() == null ? null : normalizePath(request.filePath()));
        comment.setBody(cleanComment(request.body()));
        comments.save(comment);
        return detail(projectId, email, review);
    }

    /** Build the exact source tree a reviewer would test: selected Main version + review changes. */
    @Transactional(readOnly = true)
    public Map<String,Object> testPackage(UUID projectId, UUID reviewId, String email, UUID requestedMainVersionId) {
        access.requireMember(projectId, email);
        ReviewRequest review = review(projectId, reviewId);
        MainVersion version = resolveMainVersion(projectId, review, requestedMainVersionId);
        Map<String,String> combined = buildCombinedSource(projectId, review, version);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("reviewId", review.getId());
        out.put("reviewTitle", review.getTitle());
        out.put("mainVersionId", version.getId());
        out.put("mainVersionNumber", version.getVersionNumber());
        out.put("mainLabel", "Current Main");
        out.put("mainCommitSha", version.getSourceCommitSha());
        out.put("sourceRootHash", version.getSourceRootHash());
        out.put("files", combined.entrySet().stream().map(e -> Map.of("path", e.getKey(), "content", e.getValue())).toList());
        return out;
    }

    @Transactional(readOnly = true)
    public byte[] downloadTestPackage(UUID projectId, UUID reviewId, String email, UUID requestedMainVersionId) {
        Map<String,Object> packageData = testPackage(projectId, reviewId, email, requestedMainVersionId);
        @SuppressWarnings("unchecked") List<Map<String,String>> packageFiles = (List<Map<String,String>>) packageData.get("files");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                String manifest = "{\n" +
                        "  \"reviewId\": \"" + packageData.get("reviewId") + "\",\n" +
                        "  \"reviewTitle\": " + jsonString(String.valueOf(packageData.get("reviewTitle"))) + ",\n" +
                        "  \"mainVersionNumber\": " + packageData.get("mainVersionNumber") + ",\n" +
                        "  \"mainCommitSha\": " + jsonString(String.valueOf(packageData.get("mainCommitSha"))) + "\n" +
                        "}\n";
                putZip(zip, ".cobug/test-manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
                for (Map<String,String> file : packageFiles) putZip(zip, file.get("path"), file.get("content").getBytes(StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Unable to create the review test package.", e); }
    }

    private void putZip(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path.replace('\\','/'));
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }

    private String jsonString(String value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception e) { return "\"\""; }
    }

    private MainVersion resolveMainVersion(UUID projectId, ReviewRequest review, UUID requestedId) {
        // Review setup always uses the current Main by default. Version IDs remain
        // internal safety metadata and are not a user-facing workflow requirement.
        MainVersion version = mainVersions.findTopByProjectIdOrderByVersionNumberDesc(projectId)
                .orElseThrow(() -> bad("No current Main exists for this project."));
        if (requestedId != null && !version.getId().equals(requestedId)) {
            // A caller may explicitly request a Main snapshot for historical tooling,
            // but the normal review UI deliberately sends no version id.
            version = mainVersions.findById(requestedId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected Main version not found."));
        }
        if (!version.getProject().getId().equals(projectId)) throw bad("Selected Main does not belong to this project.");
        return version;
    }

    private Map<String,String> buildCombinedSource(UUID projectId, ReviewRequest review, MainVersion version) {
        Map<String,String> combined = new TreeMap<>();
        for (MainVersionFile f : mainVersionFiles.findByVersionIdOrderByPathAsc(version.getId())) combined.put(f.getPath(), f.getSourceObject().getContent());
        List<ReviewFile> changed = files.findByReviewIdOrderByPathAsc(review.getId());
        for (ReviewFile file : changed) {
            String path = normalizePath(file.getPath());
            if (path.isBlank() || path.equalsIgnoreCase(".git") || path.startsWith(".git/")) throw bad("Invalid review path: " + path);
            boolean exists = combined.containsKey(path);
            String type = String.valueOf(file.getChangeType()).toUpperCase(Locale.ROOT);
            if ("ADDED".equals(type) && exists) throw new ResponseStatusException(HttpStatus.CONFLICT, "Test baseline conflict: " + path + " already exists in Main v" + version.getVersionNumber() + ".");
            if ("MODIFIED".equals(type) && !exists) throw new ResponseStatusException(HttpStatus.CONFLICT, "Test baseline conflict: " + path + " does not exist in Main v" + version.getVersionNumber() + ".");
            if ("DELETED".equals(type)) { if (!exists) throw new ResponseStatusException(HttpStatus.CONFLICT, "Test baseline conflict: " + path + " is already absent from Main v" + version.getVersionNumber() + "."); combined.remove(path); continue; }
            String after = file.getAfterContent();
            if (after == null) { try { after = Files.readString(reviewFilePath(review.getId(), path), StandardCharsets.UTF_8); } catch (IOException e) { throw new IllegalStateException("Review content is unavailable for " + path, e); } }
            combined.put(path, after);
        }
        return combined;
    }

    @Transactional
    public Map<String,Object> contribution(UUID projectId, String email) {
        access.requireMember(projectId, email);
        List<ReviewRequest> all = reviews.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<UUID, Long> total = new HashMap<>();
        Map<UUID, Long> approved = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Project project = access.getProject(projectId);
        names.put(project.getOwner().getId(), project.getOwner().getName());
        for (ProjectMember member : members.findByProjectIdOrderByJoinedAtAsc(projectId)) {
            users.findById(member.getUserId()).ifPresent(user -> names.put(user.getId(), user.getName()));
        }
        for (ReviewRequest r : all) {
            total.merge(r.getAuthorId(), r.getTotalPoints(), Long::sum);
            if (r.getStatus() == ReviewStatus.APPROVED || r.getStatus() == ReviewStatus.MERGED) approved.merge(r.getAuthorId(), r.getTotalPoints(), Long::sum);
            names.put(r.getAuthorId(), r.getAuthorName());
        }
        long totalPoints = total.values().stream().mapToLong(Long::longValue).sum();
        long approvedPoints = approved.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String,Object>> people = names.entrySet().stream().map(e -> {
            Map<String,Object> row = new LinkedHashMap<>();
            long t = total.getOrDefault(e.getKey(), 0L), a = approved.getOrDefault(e.getKey(), 0L);
            row.put("userId", e.getKey()); row.put("name", e.getValue()); row.put("totalPoints", t); row.put("approvedPoints", a);
            row.put("totalPercentage", totalPoints == 0 ? 0 : round(t * 100.0 / totalPoints));
            row.put("approvedPercentage", approvedPoints == 0 ? 0 : round(a * 100.0 / approvedPoints));
            row.put("attemptedReviews", all.stream().filter(r -> r.getAuthorId().equals(e.getKey())).count());
            row.put("approvedReviews", all.stream().filter(r -> r.getAuthorId().equals(e.getKey()) && (r.getStatus()==ReviewStatus.APPROVED || r.getStatus()==ReviewStatus.MERGED)).count());
            return row;
        }).sorted((a,b) -> Double.compare((Double)b.get("totalPercentage"), (Double)a.get("totalPercentage"))).toList();
        return Map.of("totalPoints", totalPoints, "approvedPoints", approvedPoints, "members", people);
    }

    private boolean duplicateActiveReview(UUID projectId, UUID authorId, List<SubmitFile> submitted) {
        Set<String> requestedHashes = submitted.stream()
                .filter(Objects::nonNull)
                .map(f -> normalizePath(f.path()) + "::" + (f.deleted() ? "DELETED" : sha256((f.content() == null ? "" : f.content()).getBytes(StandardCharsets.UTF_8))))
                .collect(Collectors.toSet());
        if (requestedHashes.isEmpty()) return false;
        return reviews.findByProjectIdAndAuthorIdOrderByCreatedAtDesc(projectId, authorId).stream()
                .filter(r -> r.getStatus() == ReviewStatus.SUBMITTED || r.getStatus() == ReviewStatus.IN_REVIEW || r.getStatus() == ReviewStatus.CHANGES_REQUESTED)
                .anyMatch(r -> files.findByReviewIdOrderByPathAsc(r.getId()).stream()
                        .map(f -> f.getPath() + "::" + f.getContentSha())
                        .collect(Collectors.toSet()).equals(requestedHashes));
    }

    private Map<String,Object> detail(UUID projectId, String email, ReviewRequest review) {
        Map<String,Object> out = summary(review);
        out.put("files", files.findByReviewIdOrderByPathAsc(review.getId()).stream().map(f -> {
            Map<String,Object> m = new LinkedHashMap<>(); m.put("id", f.getId()); m.put("path", f.getPath()); m.put("changeType", f.getChangeType()); m.put("baseSha", f.getBaseSha()); m.put("contentSha", f.getContentSha()); m.put("additions", f.getAdditions()); m.put("deletions", f.getDeletions()); m.put("sizeBytes", f.getSizeBytes()); return m;
        }).toList());
        out.put("comments", comments.findByReviewIdOrderByCreatedAtAsc(review.getId()).stream().map(c -> {
            Map<String,Object> m = new LinkedHashMap<>(); m.put("id", c.getId()); m.put("authorId", c.getAuthorId()); m.put("authorName", c.getAuthorName()); m.put("filePath", c.getFilePath()); m.put("body", c.getBody()); m.put("createdAt", c.getCreatedAt()); return m;
        }).toList());
        return out;
    }

    private Map<String,Object> summary(ReviewRequest r) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("projectId", r.getProjectId());
        m.put("workNumber", r.getWorkNumber()); m.put("updateNumber", r.getUpdateNumber() == null ? 0 : r.getUpdateNumber()); m.put("authorId", r.getAuthorId()); m.put("authorName", r.getAuthorName());
        m.put("reviewerId", r.getReviewerId()); m.put("reviewerName", r.getReviewerName()); m.put("title", r.getTitle()); m.put("description", r.getDescription());
        m.put("status", r.getStatus().name()); m.put("validationStatus", r.getValidationStatus().name()); m.put("baseMainVersionId", r.getBaseMainVersionId()); m.put("baseMainVersionNumber", r.getBaseMainVersionNumber()); m.put("baseCommitSha", r.getBaseCommitSha()); m.put("mergedCommitSha", r.getMergedCommitSha()); m.put("mergedMainVersionId", r.getMergedMainVersionId()); m.put("mergedMainVersionNumber", r.getMergedMainVersionNumber());
        m.put("totalPoints", r.getTotalPoints()); m.put("approvedPoints", r.getApprovedPoints()); m.put("filesChanged", r.getFilesChanged()); m.put("additions", r.getAdditions()); m.put("deletions", r.getDeletions());
        m.put("validationMainVersionId", r.getValidationMainVersionId()); m.put("validationMainVersionNumber", r.getValidationMainVersionNumber()); m.put("reviewerComment", r.getReviewerComment()); m.put("createdAt", r.getCreatedAt()); m.put("updatedAt", r.getUpdatedAt()); m.put("mergedAt", r.getMergedAt());
        return m;
    }

    private User resolveReviewer(UUID projectId, UUID requested, Project project) {
        if (requested != null) {
            User u = users.findById(requested).orElseThrow(() -> bad("Selected reviewer was not found."));
            if (!isReviewer(projectId, u.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Selected user cannot review this project.");
            return u;
        }
        return project.getOwner();
    }

    private boolean isReviewer(UUID projectId, UUID userId) {
        Project project = access.getProject(projectId);
        if (project.getOwner().getId().equals(userId)) return true;
        return members.findByProjectIdAndUserId(projectId, userId).map(m -> m.getRole() == ProjectMemberRole.REVIEWER || m.getRole() == ProjectMemberRole.ADMIN).orElse(false);
    }

    private void requireReviewerOrOwner(UUID projectId, User actor, ReviewRequest review) {
        if (!actor.getId().equals(review.getReviewerId()) && !isReviewer(projectId, actor.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not authorized to review this submission.");
    }

    private ReviewRequest review(UUID projectId, UUID id) {
        ReviewRequest r = reviews.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found."));
        if (!projectId.equals(r.getProjectId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found.");
        return r;
    }

    private void deleteReviewSnapshotFiles(UUID reviewId) {
        if (reviewId == null) return;
        Path root = reviewRoot(reviewId);
        try {
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private void cleanupReviewSnapshot(UUID reviewId) {
        if (reviewId == null) return;
        try { comments.deleteByReviewId(reviewId); } catch (Exception ignored) {}
        try { files.deleteByReviewId(reviewId); } catch (Exception ignored) {}
        try { if (reviews.existsById(reviewId)) reviews.deleteById(reviewId); } catch (Exception ignored) {}
        Path root = reviewRoot(reviewId);
        try {
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) { stream.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }); }
            }
        } catch (Exception ignored) {}
    }

    private Path reviewRoot(UUID id) { return storageRoot.resolve("reviews").resolve(id.toString()).normalize(); }
    private Path reviewRootPlaceholder() { return storageRoot.resolve("reviews").normalize(); }
    private Path reviewFilePath(UUID id, String path) throws IOException { Path root=reviewRoot(id).resolve("files").normalize(); Files.createDirectories(root); Path p=root.resolve(path).normalize(); if(!p.startsWith(root)) throw new IOException("Invalid review path"); return p; }
    private void writeReviewContent(UUID id, String path, String content) throws IOException { Path p=reviewFilePath(id,path); if(p.getParent()!=null)Files.createDirectories(p.getParent()); Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
    private String normalizePath(String path){return path==null?"":path.replace('\\','/').replaceAll("^/+|/+$","").replaceAll("/+/","/");}
    private String cleanComment(String s){return s==null?"":s.trim().substring(0, Math.min(s.trim().length(), 4000));}
    private ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private double round(double value){return Math.round(value*10.0)/10.0;}

    private record DiffStats(long additions,long deletions) {}
    private DiffStats diff(String before, String after){
        String[] a=before==null||before.isEmpty()?new String[0]:before.split("\\R",-1);
        String[] b=after==null||after.isEmpty()?new String[0]:after.split("\\R",-1);
        int commonPrefix=0; while(commonPrefix<a.length&&commonPrefix<b.length&&Objects.equals(a[commonPrefix],b[commonPrefix]))commonPrefix++;
        int commonSuffix=0; while(commonSuffix<a.length-commonPrefix&&commonSuffix<b.length-commonPrefix&&Objects.equals(a[a.length-1-commonSuffix],b[b.length-1-commonSuffix]))commonSuffix++;
        long deletions=Math.max(0,a.length-commonPrefix-commonSuffix); long additions=Math.max(0,b.length-commonPrefix-commonSuffix); return new DiffStats(additions,deletions);
    }
    /** Compute the SHA-1 used by Git for a blob containing the supplied UTF-8 source content. */
    private String gitBlobSha(String content) {
        try {
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(header);
            md.update(bytes);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Git blob SHA.", e);
        }
    }

    private String sha256(byte[] bytes){try{MessageDigest md=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(md.digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
