-- Core 1a: tenant context helpers. No RLS policy here (that is core 1b).
-- The app sets app.org_id / app.user_id on every connection (TenantAwareDataSource);
-- '' means "no tenant". Roles and grants are NOT created here: see docs/core/DB-ROLES.md.

create or replace function hydra_current_org() returns uuid
  language sql stable parallel safe
as $$ select nullif(current_setting('app.org_id', true), '')::uuid $$;

create or replace function hydra_current_user() returns uuid
  language sql stable parallel safe
as $$ select nullif(current_setting('app.user_id', true), '')::uuid $$;

-- Login runs before any org is known: this function reads app_user as its owner,
-- so it keeps working once RLS is enabled on app_user.
create or replace function hydra_login_lookup(p_email text)
  returns table (
    id                   uuid,
    org_id               uuid,
    email                text,
    password_hash        text,
    full_name            text,
    is_owner             boolean,
    is_active            boolean,
    must_change_password boolean
  )
  language sql stable security definer
  set search_path = public, pg_temp
as $$
  select u.id, u.org_id, u.email, u.password_hash, u.full_name,
         u.is_owner, u.is_active, u.must_change_password
    from app_user u
   where u.email = p_email
$$;

revoke all on function hydra_login_lookup(text) from public;

-- Supabase grants EXECUTE on new functions to anon / authenticated / service_role through
-- default privileges; the function returns password hashes, so take it away from them too.
-- (Those roles do not exist on CI / local Postgres.)
do $$
declare r text;
begin
  foreach r in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = r) then
      execute format('revoke all on function hydra_login_lookup(text) from %I', r);
    end if;
  end loop;
end $$;
