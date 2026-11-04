-- COBUG simplified review lifecycle: no separate VALIDATED state.
UPDATE review_requests SET status='IN_REVIEW' WHERE status='VALIDATED';
ALTER TABLE review_requests DROP CONSTRAINT IF EXISTS review_requests_status_check;
ALTER TABLE review_requests ADD CONSTRAINT review_requests_status_check
CHECK (status IN ('SUBMITTED','IN_REVIEW','CHANGES_REQUESTED','APPROVED','REJECTED','MERGED'));
