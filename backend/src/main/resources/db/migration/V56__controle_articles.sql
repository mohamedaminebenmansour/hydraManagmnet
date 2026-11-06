-- ============================================================================
-- V56: Contrôle de trésorerie — articles et pièces
--
-- Every ctl_line gets an « article » (the marché poste) and a « pièce », computed
-- at import like tools/controle/analyse.py article() / piece(). raw_key is the
-- cleaned Excel text (plain(cat)); ctl_article_alias lets the gérant regroup a
-- raw_key under another article without re-importing. Aliases survive a re-import.
-- ============================================================================

alter table ctl_line
  add column article text not null default '',
  add column piece   text not null default '',
  add column raw_key text not null default '';
create index ctl_line_raw_key_idx on ctl_line(org_id, raw_key);

create table ctl_article_alias (
  org_id      uuid not null references organization(id),
  raw_key     text not null,
  article     text not null,
  version     integer not null default 1,
  updated_by  uuid references app_user(id),
  updated_at  timestamptz not null default now(),
  primary key (org_id, raw_key)
);

alter table ctl_article_alias enable row level security;
