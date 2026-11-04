-- Project task visibility / assignment redesign.
-- PUBLIC tasks are visible to all project members.
-- PRIVATE tasks are visible only to their creator.
-- Owner-assigned tasks are forced PUBLIC by the API.

ALTER TABLE project_tasks
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';

UPDATE project_tasks
SET visibility = 'PUBLIC'
WHERE visibility IS NULL OR visibility = '';

CREATE INDEX IF NOT EXISTS idx_task_project_visibility
    ON project_tasks(project_id, visibility);

CREATE INDEX IF NOT EXISTS idx_task_project_assignee
    ON project_tasks(project_id, assignee_id);
