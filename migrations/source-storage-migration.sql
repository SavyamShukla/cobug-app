-- COBUG hybrid source storage metadata.
-- Small text/source content remains in PostgreSQL. Large/binary artifacts use
-- the local storage/source-artifacts directory in the current test build.
-- Expected storageType values: DATABASE and LOCAL_STORAGE.
-- No Supabase-specific schema is required.


-- Optional schema cleanup for installations that want the column to reflect
-- the hybrid model exactly. The application remains compatible with the old
-- NOT NULL constraint by storing an empty marker for LOCAL_STORAGE rows.
-- Run this once in PostgreSQL when convenient:
-- ALTER TABLE main_source_objects ALTER COLUMN content DROP NOT NULL;
