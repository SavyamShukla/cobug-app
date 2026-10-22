package com.cobug.reviews;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Keeps the PostgreSQL review status CHECK constraint aligned with the simplified review lifecycle. */
@Component
public class ReviewStatusConstraintMigration {
    public ReviewStatusConstraintMigration(JdbcTemplate jdbcTemplate) {
        // If an earlier build left a VALIDATED review behind, move it back to the
        // normal reviewer state before removing VALIDATED from the database constraint.
        jdbcTemplate.update("UPDATE review_requests SET status='IN_REVIEW' WHERE status='VALIDATED'");
        jdbcTemplate.execute("ALTER TABLE review_requests DROP CONSTRAINT IF EXISTS review_requests_status_check");
        jdbcTemplate.execute("ALTER TABLE review_requests ADD CONSTRAINT review_requests_status_check " +
                "CHECK (status IN ('SUBMITTED','IN_REVIEW','CHANGES_REQUESTED','APPROVED','REJECTED','MERGED'))");
    }
}
