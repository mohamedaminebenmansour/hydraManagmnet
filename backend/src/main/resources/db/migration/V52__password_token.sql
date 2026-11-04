-- ============================================================================
-- V52: password_token — reset and invitation tokens (U2)
--
-- One row per token. The raw token is NEVER stored: only its SHA-256 hex hash.
-- purpose: RESET (forgotten password / admin-triggered reset) or INVITE (invitation).
-- RESET valid 30 min, INVITE 72 h (enforced by the service, not a check constraint).
-- ============================================================================

create table password_token (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid not null,
    user_id     uuid not null references app_user(id) on delete cascade,
    purpose     text not null check (purpose in ('RESET','INVITE')),
    token_hash  text not null unique,
    expires_at  timestamptz not null,
    used_at     timestamptz,
    created_at  timestamptz not null default now(),
    created_ip  text
);

-- Find unused tokens for a user+purpose quickly (for "invalidate previous" + stats).
create index idx_password_token_user_purpose_unused
    on password_token (user_id, purpose) where used_at is null;
