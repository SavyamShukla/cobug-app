-- COBUG project membership tables.
-- Normally Hibernate will create these automatically because the two Java
-- classes are @Entity classes and the existing project uses schema update.
-- Run this only if your Hibernate schema strategy does not create them.

CREATE TABLE IF NOT EXISTS project_members (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_project_member UNIQUE (project_id, user_id)
);

CREATE TABLE IF NOT EXISTS project_invitations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    invited_user_id UUID,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    token VARCHAR(80) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
