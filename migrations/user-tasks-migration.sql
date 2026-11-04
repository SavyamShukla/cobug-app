-- Global personal tasks. These are intentionally not tied to a COBUG project.
-- owner_id is the authenticated user's users.id.
CREATE TABLE IF NOT EXISTS user_tasks (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    priority VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    due_date DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_tasks_owner_created
    ON user_tasks(owner_id, created_at);
