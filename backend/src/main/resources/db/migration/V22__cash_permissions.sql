-- ============================================================================
-- V22: Cash + Party permissions
--
-- Follows the exact pattern of V2 (permission table) + V4 (role_permission seeds).
-- Permission IDs are stored raw; the Java side prefixes them with PERM_ at
-- load time (see HydraUserDetailsService + PermissionService).
--
-- Idempotent: ON CONFLICT DO NOTHING throughout. Safe to re-run.
-- ============================================================================

-- 6 new permissions for the cash + party module.
insert into permission (id, description) values
 ('CASH_READ',    'Voir les caisses, mouvements et soldes'),
 ('CASH_DRAFT',   'Créer et modifier les mouvements DRAFT'),
 ('CASH_SUBMIT',  'Soumettre un paiement au workflow PAYMENT_APPROVAL'),
 ('CASH_POST',    'Comptabiliser (POST) un mouvement'),
 ('CASH_VOID',    'Annuler (VOID) un mouvement POSTED'),
 ('PARTY_MANAGE', 'Créer et modifier les sous-traitants / fournisseurs')
on conflict (id) do nothing;

-- Seed role_permission: system roles only (org_id is null).
-- OWNER (Gérant) gets everything — already implicit via allPermissions() in
-- HydraUserDetailsService, but we insert rows anyway so /api/roles shows
-- OWNER as having all perms.
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'OWNER'
  and p.id in ('CASH_READ','CASH_DRAFT','CASH_SUBMIT','CASH_POST','CASH_VOID','PARTY_MANAGE')
on conflict (role_id, permission_id) do nothing;

-- FINANCE: can read, draft, submit, post. CANNOT void (only OWNER can void
-- — separation of duties: the person who pays cannot be the person who
-- cancels a payment).
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'FINANCE'
  and p.id in ('CASH_READ','CASH_DRAFT','CASH_SUBMIT','CASH_POST')
on conflict (role_id, permission_id) do nothing;

-- CHEF_PROJET: can read cash + draft + submit. Cannot POST/VOID — those go
-- through the workflow. The chef writes the request, finance/owner approve.
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'CHEF_PROJET'
  and p.id in ('CASH_READ','CASH_DRAFT','CASH_SUBMIT')
on conflict (role_id, permission_id) do nothing;

-- CONDUCTEUR: can read + draft (site supervisor creates draft payment requests
-- for the chef to submit). Cannot submit (chef does that).
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'CONDUCTEUR'
  and p.id in ('CASH_READ','CASH_DRAFT')
on conflict (role_id, permission_id) do nothing;

-- ACHAT (purchasing): can read + draft + submit. They often initiate purchase
-- orders that turn into payments. Cannot POST/VOID.
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'ACHAT'
  and p.id in ('CASH_READ','CASH_DRAFT','CASH_SUBMIT')
on conflict (role_id, permission_id) do nothing;

-- BUREAU: can read only (oversight, no money actions).
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'BUREAU'
  and p.id = 'CASH_READ'
on conflict (role_id, permission_id) do nothing;

-- QA: read-only — auditor/segregation-of-duties role.
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'QA'
  and p.id = 'CASH_READ'
on conflict (role_id, permission_id) do nothing;

-- End V22
