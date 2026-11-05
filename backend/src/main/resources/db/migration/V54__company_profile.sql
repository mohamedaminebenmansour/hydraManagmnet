-- ============================================================================
-- V54: company_profile, groupement, groupement_member + ORG_SETTINGS permission
--
-- The owner records the company's legal identity, bank account and logo once.
-- Every future document (bon de commande, décompte, facture) will print them.
-- Groupements (joint ventures) with members and shares.
-- ============================================================================

create table company_profile (
  org_id            uuid primary key references organization(id),
  pays              text not null default 'TN',
  raison_sociale    text not null,
  nom_commercial    text,
  forme_juridique   text check (forme_juridique in ('SARL','SUARL','SA','SNC','SCS','EI','AUTRE')),
  matricule_fiscal  text,
  rne               text,
  capital           numeric(18,3) check (capital is null or capital >= 0),
  assujetti_tva     boolean not null default true,
  adresse           text,
  code_postal       text,
  ville             text,
  gouvernorat       text,
  telephone         text,
  email             text,
  site_web          text,
  banque            text,
  rib               text,
  logo_key          text,
  logo_content_type text,
  version           integer not null default 0,
  updated_at        timestamptz not null default now(),
  updated_by        uuid references app_user(id)
);

create table groupement (
  id               uuid primary key default gen_random_uuid(),
  org_id           uuid not null references organization(id),
  nom              text not null,
  type             text not null check (type in ('CONJOINT','SOLIDAIRE')),
  project_id       uuid references project(id),
  reference_marche text,
  notes            text,
  version          integer not null default 0,
  archived_at      timestamptz,
  created_at       timestamptz not null default now(),
  created_by       uuid references app_user(id),
  updated_at       timestamptz not null default now(),
  updated_by       uuid references app_user(id)
);
create index groupement_org_idx on groupement(org_id);

create table groupement_member (
  id               uuid primary key default gen_random_uuid(),
  org_id           uuid not null references organization(id),
  groupement_id    uuid not null references groupement(id),
  ordre            integer not null,
  is_self          boolean not null default false,
  raison_sociale   text not null,
  matricule_fiscal text,
  pays             text not null default 'TN',
  role             text not null check (role in ('MANDATAIRE','MEMBRE')),
  part_pct         numeric(7,4) not null check (part_pct > 0 and part_pct <= 100),
  prestations      text
);
create index groupement_member_grp_idx on groupement_member(groupement_id);
create unique index groupement_one_mandataire on groupement_member(groupement_id) where role = 'MANDATAIRE';
create unique index groupement_one_self       on groupement_member(groupement_id) where is_self;

alter table company_profile   enable row level security;
alter table groupement        enable row level security;
alter table groupement_member enable row level security;

-- Permission ORG_SETTINGS (same pattern as V22)
insert into permission (id, description) values
 ('ORG_SETTINGS', 'Modifier le profil de l''entreprise et les groupements')
on conflict (id) do nothing;

-- Grant to OWNER (system role, org_id is null)
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and r.code = 'OWNER'
  and p.id = 'ORG_SETTINGS'
on conflict (role_id, permission_id) do nothing;
