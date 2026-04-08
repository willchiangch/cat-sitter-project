-- V21: Rename visit_tasks to visit_services to align with VisitService entity
-- This fixes the SchemaManagementException on Cloud Run deployment

-- 1. Rename the table
ALTER TABLE visit_tasks RENAME TO visit_services;

-- 2. Rename the type column
ALTER TABLE visit_services RENAME COLUMN task_type TO service_type;

-- 3. Rename indexes for consistency
ALTER INDEX IF EXISTS idx_visit_tasks_visit_id RENAME TO idx_visit_services_visit_id;
ALTER INDEX IF EXISTS idx_visit_tasks_pet_id RENAME TO idx_visit_services_pet_id;

-- 4. Double check auditing columns (already exist from V1, but documenting for clarity)
-- created_at, created_by, updated_at, updated_by
