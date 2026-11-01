-- ============================================================================
-- V46 — two-level folder tree (main folders + sub-folders) from the template.
--
-- Folders are identified by STABLE CODES; names are display only.
--   main folder : '02_TECH'                 (doc_folder_template.parent_code null)
--   sub-folder  : '02_TECH.BORDEREAUX'      (parent_code = '02_TECH')
--
-- 1. doc_folder_template.parent_code
-- 2. global (org_id null) sub-folder templates + new main folder 08_SST
-- 3. hydra_backfill_folder_tree(): idempotent backfill of every project
--    (also used by tests); called once here
-- 4. bordereau sub-folder code '02_TECH_BDQE' -> '02_TECH.BORDEREAUX'
-- 5. unique (project_id, code) on doc_folder, only when no duplicates exist
--
-- Never moves or deletes documents. 07 has no sub-folders.
-- ============================================================================

-- 1. parent_code ------------------------------------------------------------
alter table doc_folder_template add column if not exists parent_code text null;
comment on column doc_folder_template.parent_code is
    'Code of the main folder this template row is a sub-folder of; null = main folder.';

-- 2. global templates ---------------------------------------------------------
insert into doc_folder_template (org_id, code, name, sort_order, parent_code)
select null::uuid, v.code, v.name, v.sort_order, v.parent_code
from (values
    -- new main folder
    ('08_SST',                 'Sous-traitants',                    8, null),
    -- 01_ADMIN
    ('01_ADMIN.MARCHE',        'Marché et avenants',                1, '01_ADMIN'),
    ('01_ADMIN.CAUTIONS',      'Cautions et garanties',             2, '01_ADMIN'),
    ('01_ADMIN.OS',            'Ordres de service',                 3, '01_ADMIN'),
    ('01_ADMIN.COURRIER',      'Correspondance',                    4, '01_ADMIN'),
    ('01_ADMIN.ASSURANCES',    'Assurances',                        5, '01_ADMIN'),
    -- 02_TECH
    ('02_TECH.BORDEREAUX',     'Bordereaux et DQE',                 1, '02_TECH'),
    ('02_TECH.PLANS',          'Plans',                             2, '02_TECH'),
    ('02_TECH.CALCULS',        'Notes de calcul',                   3, '02_TECH'),
    ('02_TECH.FICHES',         'Fiches techniques et agréments',    4, '02_TECH'),
    -- 03_EXEC
    ('03_EXEC.ATTACHEMENTS',   'Attachements',                      1, '03_EXEC'),
    ('03_EXEC.JOURNAL',        'Journal de chantier',               2, '03_EXEC'),
    ('03_EXEC.PHOTOS',         'Photos',                            3, '03_EXEC'),
    ('03_EXEC.REUNIONS',       'PV de réunion',                     4, '03_EXEC'),
    -- 04_QUAL
    ('04_QUAL.ESSAIS',         'PV d''essais',                      1, '04_QUAL'),
    ('04_QUAL.RECEPTION',      'PV de réception',                   2, '04_QUAL'),
    ('04_QUAL.RESERVES',       'Réserves et levées',                3, '04_QUAL'),
    -- 05_ACHAT
    ('05_ACHAT.DEVIS',         'Devis',                             1, '05_ACHAT'),
    ('05_ACHAT.BC',            'Bons de commande',                  2, '05_ACHAT'),
    ('05_ACHAT.BL',            'Bons de livraison',                 3, '05_ACHAT'),
    -- 06_FIN
    ('06_FIN.FACTURES',        'Factures fournisseurs',             1, '06_FIN'),
    ('06_FIN.DECOMPTES',       'Décomptes client',                  2, '06_FIN'),
    ('06_FIN.SITUATIONS',      'Situations sous-traitants',         3, '06_FIN'),
    ('06_FIN.PAIEMENTS',       'Paiements et reçus',                4, '06_FIN'),
    ('06_FIN.RETENUES',        'Retenues de garantie',              5, '06_FIN'),
    -- 08_SST
    ('08_SST.CONTRATS',        'Contrats et avenants',              1, '08_SST'),
    ('08_SST.ATTESTATIONS',    'Attestations (fiscale, CNSS)',      2, '08_SST')
) as v(code, name, sort_order, parent_code)
where not exists (
    select 1 from doc_folder_template t where t.org_id is null and t.code = v.code
);

-- 4 (before the backfill, so the existing bordereau sub-folder is found by code).
update doc_folder set code = '02_TECH.BORDEREAUX' where code = '02_TECH_BDQE';

-- 3. backfill function ------------------------------------------------------------
-- A main folder of a project matches a template main code when its code is the same,
-- the same with a space instead of '_' (legacy '01 ADMIN'), or starts with the same
-- 2 digits (legacy '07_HSE' for '07_INT'). Exact match wins, then the space form,
-- then the lowest sort_order / oldest. Returns the number of folders created.
create or replace function hydra_backfill_folder_tree() returns integer
language plpgsql as $$
declare
    created_main integer;
    created_sub  integer;
begin
    -- main folders missing in a project (08_SST everywhere; others only if absent)
    insert into doc_folder (project_id, parent_id, code, name, sort_order)
    select p.id, null, t.code, t.name, t.sort_order
    from project p
    cross join doc_folder_template t
    where t.org_id is null and t.parent_code is null
      and not exists (
          select 1 from doc_folder f
          where f.project_id = p.id and f.parent_id is null
            and (f.code = t.code
                 or replace(f.code, ' ', '_') = t.code
                 or left(f.code, 2) = left(t.code, 2)));
    get diagnostics created_main = row_count;

    -- sub-folders missing in a project, under the matching main folder
    with mains as (
        select distinct on (f.project_id, t.code)
               f.project_id, f.id as main_id, t.code as main_code
        from doc_folder f
        join doc_folder_template t
          on t.org_id is null and t.parent_code is null
         and (f.code = t.code
              or replace(f.code, ' ', '_') = t.code
              or left(f.code, 2) = left(t.code, 2))
        where f.parent_id is null
        order by f.project_id, t.code,
                 (f.code = t.code) desc,
                 (replace(f.code, ' ', '_') = t.code) desc,
                 f.sort_order, f.created_at
    )
    insert into doc_folder (project_id, parent_id, code, name, sort_order)
    select m.project_id, m.main_id, s.code, s.name, s.sort_order
    from mains m
    join doc_folder_template s on s.org_id is null and s.parent_code = m.main_code
    where not exists (
        select 1 from doc_folder d where d.project_id = m.project_id and d.code = s.code);
    get diagnostics created_sub = row_count;

    return created_main + created_sub;
end;
$$;

comment on function hydra_backfill_folder_tree() is
    'Idempotent: creates the missing main folders and sub-folders of every project from the global template (V46).';

select hydra_backfill_folder_tree();

-- 5. unique (project_id, code), only if there is no duplicate --------------------
do $$
declare
    dup record;
    has_dup boolean := false;
begin
    for dup in
        select project_id, code, count(*) as n
        from doc_folder where code is not null
        group by project_id, code having count(*) > 1
    loop
        has_dup := true;
        raise notice 'V46: duplicate doc_folder code % in project % (% rows) — unique index skipped',
            dup.code, dup.project_id, dup.n;
    end loop;
    if not has_dup then
        create unique index if not exists uq_doc_folder_project_code
            on doc_folder (project_id, code) where code is not null;
    end if;
end;
$$;
