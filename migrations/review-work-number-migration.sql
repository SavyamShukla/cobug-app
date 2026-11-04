ALTER TABLE review_requests ADD COLUMN IF NOT EXISTS work_number INTEGER;
ALTER TABLE review_requests ADD COLUMN IF NOT EXISTS update_number INTEGER DEFAULT 0;
UPDATE review_requests SET update_number = 0 WHERE update_number IS NULL;
