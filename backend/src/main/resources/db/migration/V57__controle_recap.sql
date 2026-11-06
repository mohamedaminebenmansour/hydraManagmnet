-- ============================================================================
-- V57: Contrôle de trésorerie — Caisse à échéance, récap Excel, rubriques, familles
--
-- - ctl_account.kind accepts 'echeance' (sheet « Caisse à échéance »).
-- - ctl_line gets rubrique (the column header of the Excel recap, see
--   ControleRubriques) and famille (group of rubriques), filled at import and
--   recomputed when an article alias changes.
-- - ctl_recap holds the table « Récap des dépenses par projet » of the sheet
--   « Récap T.d.B dépenses », replaced at each import.
-- ============================================================================

alter table ctl_account drop constraint ctl_account_kind_check;
alter table ctl_account add constraint ctl_account_kind_check
  check (kind in ('banque','especes','chantier','partenaire','echeance'));

alter table ctl_line
  add column rubrique text not null default '',
  add column famille  text not null default '';

create table ctl_recap (
  org_id    uuid not null references organization(id),
  project   text not null,
  rubrique  text not null,
  amount    numeric(18,3) not null
);
create index ctl_recap_org_idx on ctl_recap(org_id);

alter table ctl_recap enable row level security;
