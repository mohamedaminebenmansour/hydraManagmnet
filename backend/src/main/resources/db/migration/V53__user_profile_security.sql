-- ============================================================================
-- V51: user profile + security columns
--
-- Adds phone, job_title, last_login_at, password_changed_at, first_login_at
-- to app_user. Nothing else.
-- ============================================================================

alter table app_user add column phone               text;
alter table app_user add column job_title           text;
alter table app_user add column last_login_at       timestamptz;
alter table app_user add column password_changed_at timestamptz;
alter table app_user add column first_login_at      timestamptz;
