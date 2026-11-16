# HYDRA — database roles (core 1a)

From core 1a on, the app can run with **two** sets of database credentials:

| Env vars | Role | Used by |
|---|---|---|
| `DB_USER` / `DB_PASSWORD` | `hydra_app` — normal role, **not** owner of the tables, no superuser, no bypassrls | everything the users do (JPA, JdbcTemplate, Spring Session) through `TenantAwareDataSource` |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` (default: `DB_USER` / `DB_PASSWORD`) | the table owner (`postgres` on Supabase) | Flyway migrations and the small `systemDataSource` (pool of 2) exposed as `@Qualifier("system") JdbcTemplate` |

Until the owner runs the SQL below and changes `DB_USER`, both sets are the same and nothing changes.

## Rule: who may use the system JdbcTemplate
**Only bootstrap, migrations and cross-tenant system jobs may use the system JdbcTemplate.**
Today: `BootstrapRunner` only. Any new user of `@Qualifier("system") JdbcTemplate` must be added to
this list in the same pull request, with the reason. Everything else uses the normal `JdbcTemplate`
/ repositories, which carry the tenant.

## How the tenant reaches the database
- `TenantContextFilter` (in the security chain, after authentication) puts the user's org and id in
  `TenantContext` for the request; non-request code uses `TenantContext.runAs(org, user, …)`.
- `TenantAwareDataSource` (primary DataSource, wraps Hikari) runs on every borrowed connection
  `select set_config('app.org_id', ?, false), set_config('app.user_id', ?, false)` and resets both to
  `''` when the connection goes back to the pool.
- SQL side (V58): `hydra_current_org()` / `hydra_current_user()` return the uuid or null.
  `hydra_login_lookup(email)` (SECURITY DEFINER) lets login read `app_user` before any org is known.

**Supabase:** keep `DB_URL` on the **direct / session** connection (port 5432). Never the transaction
pooler (port 6543): session variables are not safe there.

## SQL for the owner (run once, in the Supabase SQL editor, as `postgres`)
Replace `CHANGE-ME` with a long random password and keep it in the host's secrets only.

```sql
-- 1. The application role
create role hydra_app login password 'CHANGE-ME'
  nosuperuser nobypassrls nocreatedb nocreaterole noinherit;

-- 2. Schema and existing objects
grant usage on schema public to hydra_app;
grant select, insert, update, delete on all tables in schema public to hydra_app;
grant usage, select on all sequences in schema public to hydra_app;

-- 3. Objects created later by migrations (run by postgres)
alter default privileges for role postgres in schema public
  grant select, insert, update, delete on tables to hydra_app;
alter default privileges for role postgres in schema public
  grant usage, select on sequences to hydra_app;

-- 4. Tenant functions (V58)
grant execute on function hydra_current_org()       to hydra_app;
grant execute on function hydra_current_user()      to hydra_app;
grant execute on function hydra_login_lookup(text)  to hydra_app;

-- 5. Check: must print f, f, f
select rolsuper, rolbypassrls,
       exists (select 1 from pg_tables where schemaname = 'public' and tableowner = 'hydra_app')
  from pg_roles where rolname = 'hydra_app';
```

Then on the host:
```
DB_USER=hydra_app            DB_PASSWORD=<the password above>
FLYWAY_USER=postgres         FLYWAY_PASSWORD=<the postgres password>
```
(With Supabase's direct connection the user name may need the project suffix, e.g. `hydra_app.<project-ref>`
for the pooler; on the direct host it is plain `hydra_app`.)

## Notes
- Do not create roles in Flyway: Supabase permissions differ from local Postgres / CI.
- Tests and CI connect as `postgres` (superuser) — superusers and table owners bypass RLS, so RLS
  tests in core 1b must switch to `hydra_app` (or `set role`) to prove anything.
- `spring.session.jdbc.initialize-schema=always`: the `spring_session*` tables already exist, so
  `hydra_app` only needs the DML grants above. In core 1b these tables must stay **without** RLS
  (sessions are read before the user is known).
- Functions in the public schema: on Supabase, new functions are executable by `anon` /
  `authenticated` by default. V58 revokes that for `hydra_login_lookup` (it returns password hashes).
