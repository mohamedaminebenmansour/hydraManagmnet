-- ============================================================================
-- V55: Contrôle de trésorerie — archive of the company's cash Excel
--
-- An archive of the company's cash Excel. It imports a JSON file (produced by
-- tools/controle/convert.py from the Excel), shows every line, and lets the
-- owner record a justification (invoice, attachment…) on each line and
-- validate each subcontractor. It never touches cash_pot / cash_movement.
--
-- All tables are org-scoped and RLS-enabled. No FK from ctl_justification to
-- ctl_line on purpose: a justification must survive a re-import.
-- ============================================================================

create table ctl_import (
  id            uuid primary key default gen_random_uuid(),
  org_id        uuid not null references organization(id),
  source_name   text not null,
  generated_at  text,
  imported_at   timestamptz not null default now(),
  imported_by   uuid references app_user(id),
  line_count    integer not null,
  st_line_count integer not null
);
create index ctl_import_org_idx on ctl_import(org_id, imported_at desc);

create table ctl_account (
  org_id   uuid not null references organization(id),
  id       text not null,
  name     text not null,
  kind     text not null check (kind in ('banque','especes','chantier','partenaire')),
  owner    text not null,
  project  text not null default '',
  note     text,
  primary key (org_id, id)
);

create table ctl_line (
  org_id         uuid not null references organization(id),
  line_key       text not null,
  sheet          text not null,
  excel_row      integer not null,
  line_date      date,
  account_id     text not null default '',
  project        text not null,
  amount_in      numeric(18,3) not null default 0,
  amount_out     numeric(18,3) not null default 0,
  pay_mode       text not null default '',
  bon            text not null default '',
  fct            text not null default '',
  category_excel text not null default '',
  designation    text not null default '',
  party          text not null default '',
  poste          text not null default '',
  kind           text not null check (kind in ('entree','sortie','transfert','partenaire')),
  obs            text not null default '',
  flags          text[] not null default '{}',
  st_line_key    text,
  primary key (org_id, line_key)
);
create index ctl_line_project_idx on ctl_line(org_id, project);

create table ctl_st_account (
  org_id    uuid not null references organization(id),
  id        text not null,
  sheet     text not null,
  person    text not null,
  project   text not null,
  supplier  boolean not null default false,
  primary key (org_id, id)
);

create table ctl_st_line (
  org_id         uuid not null references organization(id),
  line_key       text not null,
  st_account_id  text not null,
  sheet          text not null,
  excel_row      integer not null,
  line_date      date,
  travaux        numeric(18,3) not null default 0,
  paye           numeric(18,3) not null default 0,
  pay_mode       text not null default '',
  designation    text not null default '',
  link_line_key  text,
  flags          text[] not null default '{}',
  primary key (org_id, line_key)
);

-- No foreign key to ctl_line on purpose: a justification must survive a re-import.
create table ctl_justification (
  org_id      uuid not null references organization(id),
  line_key    text not null,
  statut      text not null check (statut in ('justifie','partiel','non-justifie','conteste')),
  doc_type    text,
  reference   text,
  doc_date    date,
  doc_amount  numeric(18,3),
  note        text,
  version     integer not null default 0,
  updated_by  uuid references app_user(id),
  updated_at  timestamptz not null default now(),
  primary key (org_id, line_key)
);

create table ctl_validation (
  org_id          uuid not null references organization(id),
  key             text not null,
  statut          text not null check (statut in ('valide','a-revoir','conteste')),
  agreed_balance  numeric(18,3),
  note            text,
  version         integer not null default 0,
  updated_by      uuid references app_user(id),
  updated_at      timestamptz not null default now(),
  primary key (org_id, key)
);

alter table ctl_import        enable row level security;
alter table ctl_account       enable row level security;
alter table ctl_line          enable row level security;
alter table ctl_st_account    enable row level security;
alter table ctl_st_line       enable row level security;
alter table ctl_justification enable row level security;
alter table ctl_validation    enable row level security;

insert into permission (id, description) values
 ('CTL_READ',    'Consulter le contrôle de trésorerie'),
 ('CTL_JUSTIFY', 'Saisir les justificatifs et les validations du contrôle'),
 ('CTL_IMPORT',  'Importer le fichier de caisse dans le contrôle')
on conflict (id) do nothing;

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r cross join permission p
where r.org_id is null and r.code = 'OWNER' and p.id in ('CTL_READ','CTL_JUSTIFY','CTL_IMPORT')
on conflict (role_id, permission_id) do nothing;
