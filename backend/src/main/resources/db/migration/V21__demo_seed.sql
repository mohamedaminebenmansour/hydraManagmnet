-- ============================================================================
-- V21: Comprehensive Tunisian BTP demo seed
--
-- Purpose: Make Hydra feel like a real production deployment.
--   - 15 demo users across all 8 system roles (chef de projet, conducteur,
--     QA, finance, achat, ingénieur, bureau)
--   - 9 realistic projects across 9 Tunisian governorates (water/pipe/reservoir)
--   - 10 parties (sous-traitants + fournisseurs with Tunisian names)
--   - 9 PROJECT pots (one per project) + existing COMPANY pot
--   - 50+ cash movements across all pots (IN advances, OUT payments, TRANSFER
--     from central to chantier, DRAFT/POSTED/VOID lifecycle)
--   - 9 projects × 7 standard folders = 63 doc folders
--   - 40+ documents (BC, BL, facture, plans, photos, contracts)
--   - 10 tickets (engineering transmittal workflow)
--   - 5 process instances (running PAYMENT_APPROVAL workflows)
--
-- All demo users share password: HydraDemo2026!
-- (BCrypt hash: $2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G)
--
-- Idempotent: ON CONFLICT DO NOTHING + WHERE NOT EXISTS guards.
-- Re-running V21 (e.g. after dropping + recreating the DB) is safe.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. Helper function for inserting documents with folder lookup
--    Defined BEFORE the DO block that uses it.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hydra_insert_demo_document(
    p_id uuid, p_org_id uuid, p_project_id uuid, p_folder_code text,
    p_title text, p_doc_type text, p_status text,
    p_created_by uuid, p_created_at timestamptz, p_current_rev text, p_desc text
) RETURNS void AS $$
DECLARE
    v_folder_id uuid;
BEGIN
    SELECT df.id INTO v_folder_id
    FROM doc_folder df
    WHERE df.project_id = p_project_id AND df.code = p_folder_code
    LIMIT 1;

    INSERT INTO document (id, org_id, folder_id, ticket_id, title, doc_type, current_rev,
                          status, description, created_at, created_by)
    VALUES (p_id, p_org_id, v_folder_id, NULL, p_title, p_doc_type, p_current_rev, p_status,
            p_desc, p_created_at, p_created_by)
    ON CONFLICT (id) DO NOTHING;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 1. Demo users (15 across all roles)
--    All share password: HydraDemo2026!  (BCrypt hash below)
-- ---------------------------------------------------------------------------
INSERT INTO app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
VALUES
    ('aaaaaa00-0000-0000-0000-000000000001', '510beefe-06bf-484c-a1bb-e47551469bdb', 'karim.bouzid@hydra.tn',   '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Karim Bouzid',     false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000002', '510beefe-06bf-484c-a1bb-e47551469bdb', 'sami.trabelsi@hydra.tn',  '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Sami Trabelsi',     false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000003', '510beefe-06bf-484c-a1bb-e47551469bdb', 'leila.gharbi@hydra.tn',   '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Leila Gharbi',      false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000004', '510beefe-06bf-484c-a1bb-e47551469bdb', 'mona.zribi@hydra.tn',     '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Mona Zribi',        false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000005', '510beefe-06bf-484c-a1bb-e47551469bdb', 'mohamed.sassi@hydra.tn',  '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Mohamed Sassi',     false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000006', '510beefe-06bf-484c-a1bb-e47551469bdb', 'nizar.amri@hydra.tn',     '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Nizar Amri',         false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000007', '510beefe-06bf-484c-a1bb-e47551469bdb', 'wassim.jouini@hydra.tn',  '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Wassim Jouini',     false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000008', '510beefe-06bf-484c-a1bb-e47551469bdb', 'ahlem.cherif@hydra.tn',   '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Ahlem Cherif',      false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000009', '510beefe-06bf-484c-a1bb-e47551469bdb', 'fawzi.haddad@hydra.tn',   '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Fawzi Haddad',      false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000010', '510beefe-06bf-484c-a1bb-e47551469bdb', 'rania.mansour@hydra.tn',  '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Rania Mansour',     false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000011', '510beefe-06bf-484c-a1bb-e47551469bdb', 'bilal.khelil@hydra.tn',   '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Bilal Khelil',      false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000012', '510beefe-06bf-484c-a1bb-e47551469bdb', 'asma.kridis@hydra.tn',    '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Asma Kridis',       false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000013', '510beefe-06bf-484c-a1bb-e47551469bdb', 'houssem.brahmi@hydra.tn', '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Houssem Brahmi',    false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000014', '510beefe-06bf-484c-a1bb-e47551469bdb', 'safa.mtibaa@hydra.tn',    '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Safa Mtibaa',       false, true, false),
    ('aaaaaa00-0000-0000-0000-000000000015', '510beefe-06bf-484c-a1bb-e47551469bdb', 'amel.riahi@hydra.tn',     '$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G', 'Amel Riahi',        false, true, false)
ON CONFLICT (email) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Assign roles to demo users
-- ---------------------------------------------------------------------------
INSERT INTO user_role (user_id, role_id, assigned_by, assigned_at)
SELECT u.id, r.id, u.id, now()
FROM app_user u
CROSS JOIN role r
WHERE u.email IN (
    'karim.bouzid@hydra.tn','sami.trabelsi@hydra.tn','leila.gharbi@hydra.tn','mona.zribi@hydra.tn',
    'mohamed.sassi@hydra.tn','nizar.amri@hydra.tn','wassim.jouini@hydra.tn','ahlem.cherif@hydra.tn',
    'fawzi.haddad@hydra.tn','rania.mansour@hydra.tn','bilal.khelil@hydra.tn','asma.kridis@hydra.tn',
    'houssem.brahmi@hydra.tn','safa.mtibaa@hydra.tn','amel.riahi@hydra.tn')
AND (
    (u.email = 'karim.bouzid@hydra.tn'    AND r.code IN ('CHEF_PROJET','CONDUCTEUR'))
    OR (u.email = 'sami.trabelsi@hydra.tn' AND r.code = 'CHEF_PROJET')
    OR (u.email = 'leila.gharbi@hydra.tn'  AND r.code = 'CHEF_PROJET')
    OR (u.email = 'mona.zribi@hydra.tn'    AND r.code = 'CHEF_PROJET')
    OR (u.email = 'mohamed.sassi@hydra.tn' AND r.code = 'CONDUCTEUR')
    OR (u.email = 'nizar.amri@hydra.tn'    AND r.code = 'CONDUCTEUR')
    OR (u.email = 'wassim.jouini@hydra.tn' AND r.code = 'CONDUCTEUR')
    OR (u.email = 'ahlem.cherif@hydra.tn'  AND r.code = 'INGENIEUR')
    OR (u.email = 'fawzi.haddad@hydra.tn'  AND r.code = 'INGENIEUR')
    OR (u.email = 'rania.mansour@hydra.tn' AND r.code = 'QA')
    OR (u.email = 'bilal.khelil@hydra.tn'  AND r.code = 'CONDUCTEUR')
    OR (u.email = 'asma.kridis@hydra.tn'   AND r.code = 'CONDUCTEUR')
    OR (u.email = 'houssem.brahmi@hydra.tn' AND r.code = 'ACHAT')
    OR (u.email = 'safa.mtibaa@hydra.tn'   AND r.code = 'FINANCE')
    OR (u.email = 'amel.riahi@hydra.tn'     AND r.code = 'BUREAU')
)
ON CONFLICT (user_id, role_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Projects (9 realistic Tunisian water/pipe/reservoir projects)
-- ---------------------------------------------------------------------------
INSERT INTO project (id, org_id, code, name, status, client_name, location, governorate,
                     start_date, end_date, project_type, description, contract_value, currency, phase, created_by, created_at)
SELECT t.id::uuid, '510beefe-06bf-484c-a1bb-e47551469bdb', t.code, t.name, t.status, t.client_name,
       t.location, t.governorate, t.start_date::date, t.end_date::date, t.project_type, t.description,
       t.contract_value::numeric, 'TND', t.phase, u.id, t.created_at::timestamptz
FROM (VALUES
    -- (id, code, name, status, client_name, location, governorate, start, end, type, description, value, phase, created_at)
    ('bbbbbb00-0000-0000-0000-000000000001','ADD-ADR-2024','Adduction Aïn Draham','ACTIF','SONEDE Région Nord','Aïn Draham, Jendouba','Jendouba','2024-03-01','2025-06-30','ADDUCTION','Réseau d''adduction d''eau potable desservant les quartiers nord d''Aïn Draham. 12 km de conduites PEHD DN 200 et DN 300, 1 réservoir 500 m³, 3 chambres de vanne.',1850000.000,'EXECUTION','2024-02-15 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000002','IRR-JND-2024','Réseau Irrigation Jendouba','ACTIF','CRDA Jendouba','Jendouba Sud','Jendouba','2024-05-15','2025-09-30','IRRIGATION','Périmètre irrigué sur 350 hectares. Réseau secondaire en PVC DN 110-250, 18 bornes d''irrigation, station de pompage 75 kW.',920000.000,'EXECUTION','2024-04-20 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000003','RES-KEF-2023','Réservoir 1000m³ Le Kef','ACTIF','SONEDE Centre','Le Kef Est','Le Kef','2023-09-01','2024-12-15','RESERVOIR','Réservoir semi-enterré 1000 m³ en béton armé avec étanchéité intérieure. Chambre de vanne associée, raccordement au réseau existant.',1450000.000,'RECEPTION','2023-08-10 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000004','CV-BJA-2024','Chambre de Vanne Béja','ACTIF','SONEDE Nord-Ouest','Béja Centre','Béja','2024-06-01','2024-11-30','VANNE','Chambre de vanne 4x3 m avec 6 vannes DN 100-200, instrumentation de téléconduite et pompe de relevage.',320000.000,'EXECUTION','2024-05-15 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000005','FOR-SBZ-2024','Forage Village Sidi Bouzid','ACTIF','CRDA Sidi Bouzid','Sidi Bouzid Rural','Sidi Bouzid','2024-04-01','2024-12-20','FORAGE','Forage profond 180 m, pompe immergée 22 kW, réservoir 200 m³ surélevé, 4 km de réseau de distribution rurale.',680000.000,'EXECUTION','2024-03-20 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000006','PMP-NBL-2024','Station de Pompage Nabeul','ACTIF','SONEDE Cap Bon','Nabeul Sud','Nabeul','2024-02-01','2024-12-31','POMPAGE','Station de pompage 250 kW avec 3 pompes (2+1), génie civil bâtiment 8x6 m, armoire électrique, téléconduite.',1180000.000,'EXECUTION','2024-01-20 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000007','ASS-SFX-2023','Assainissement Sfax Sud','ACTIF','ONAS Sfax','Sfax Sud','Sfax','2023-06-01','2024-10-31','ASSAINISSEMENT','Réseau d''assainissement collectif 8 km en PVC DN 200-400, 120 regards de visite, station de refoulement 30 kW.',1620000.000,'RECEPTION','2023-05-10 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000008','REH-SUS-2022','Réhabilitation Réseau Sousse','CLOTURE','SONEDE Sahel','Sousse Centre','Sousse','2022-09-01','2023-08-31','REHABILITATION','Réhabilitation de 6 km de conduites vieillissantes en fonte DN 150, remplacement de 80 branchements, tests de pression.',740000.000,'CLOSEOUT','2022-08-15 10:00:00+00'),
    ('bbbbbb00-0000-0000-0000-000000000009','BAS-MDN-2024','Bassin de Rétention Médenine','ACTIF','Ministère de l''Agriculture','Médenine Sud-Est','Médenine','2024-07-01','2025-08-31','BASSIN','Bassin de rétention des eaux de crue 50 000 m³, digue en terre 8 m hauteur, évacuateur en béton, ouvrage de prise.',980000.000,'DESIGN','2024-06-15 10:00:00+00')
) AS t(id, code, name, status, client_name, location, governorate, start_date, end_date, project_type, description, contract_value, phase, created_at)
CROSS JOIN app_user u
WHERE u.email = 'owner@hydra.tn'
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Project members — link users to projects with project roles
-- ---------------------------------------------------------------------------
INSERT INTO project_member (project_id, user_id, project_role, can_upload, created_by, created_at)
SELECT p.id, u.id, m.role, m.can_upload, u_owner.id, now()
FROM project p
CROSS JOIN app_user u
CROSS JOIN app_user u_owner
CROSS JOIN (VALUES
    ('bbbbbb00-0000-0000-0000-000000000001','sami.trabelsi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000001','ahlem.cherif@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000001','mohamed.sassi@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000001','rania.mansour@hydra.tn','VIEWER',false),
    ('bbbbbb00-0000-0000-0000-000000000002','leila.gharbi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000002','fawzi.haddad@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000002','nizar.amri@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000003','sami.trabelsi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000003','fawzi.haddad@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000003','rania.mansour@hydra.tn','VIEWER',false),
    ('bbbbbb00-0000-0000-0000-000000000004','mona.zribi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000004','wassim.jouini@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000005','leila.gharbi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000005','ahlem.cherif@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000005','bilal.khelil@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000006','sami.trabelsi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000006','fawzi.haddad@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000006','asma.kridis@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000006','rania.mansour@hydra.tn','VIEWER',false),
    ('bbbbbb00-0000-0000-0000-000000000007','mona.zribi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000007','ahlem.cherif@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000007','mohamed.sassi@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000007','rania.mansour@hydra.tn','VIEWER',false),
    ('bbbbbb00-0000-0000-0000-000000000008','leila.gharbi@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000008','fawzi.haddad@hydra.tn','VIEWER',false),
    ('bbbbbb00-0000-0000-0000-000000000009','karim.bouzid@hydra.tn','ADMIN',true),
    ('bbbbbb00-0000-0000-0000-000000000009','ahlem.cherif@hydra.tn','ENGINEER',true),
    ('bbbbbb00-0000-0000-0000-000000000009','fawzi.haddad@hydra.tn','ENGINEER',true)
) AS m(project_id, email, role, can_upload)
WHERE p.id::text = m.project_id
AND u.email = m.email
AND u_owner.email = 'owner@hydra.tn'
ON CONFLICT (project_id, user_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Doc folders per project — clone the 7-folder template
--    Folder IDs: deterministic, format eeeeee00-0000-0000-0000-PPFF00000000
--    where PP = project idx (01-09), FF = folder sort_order (01-07)
-- ---------------------------------------------------------------------------
INSERT INTO doc_folder (id, project_id, parent_id, code, name, sort_order, created_at)
SELECT
    ('eeeeee00-0000-0000-0000-' || lpad(p.idx::text, 2, '0') || lpad(t.sort_order::text, 2, '0') || '00000000')::uuid,
    proj.id, NULL, t.code, t.name, t.sort_order, now()
FROM (VALUES
    (1,'bbbbbb00-0000-0000-0000-000000000001'),
    (2,'bbbbbb00-0000-0000-0000-000000000002'),
    (3,'bbbbbb00-0000-0000-0000-000000000003'),
    (4,'bbbbbb00-0000-0000-0000-000000000004'),
    (5,'bbbbbb00-0000-0000-0000-000000000005'),
    (6,'bbbbbb00-0000-0000-0000-000000000006'),
    (7,'bbbbbb00-0000-0000-0000-000000000007'),
    (8,'bbbbbb00-0000-0000-0000-000000000008'),
    (9,'bbbbbb00-0000-0000-0000-000000000009')
) AS p(idx, project_uuid)
JOIN project proj ON proj.id::text = p.project_uuid
JOIN doc_folder_template t ON t.org_id IS NULL
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Parties — 10 realistic Tunisian subcontractors + suppliers
-- ---------------------------------------------------------------------------
INSERT INTO party (id, org_id, name, type, phone, active, created_at, updated_at)
VALUES
    ('dddddd00-0000-0000-0000-000000000001', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Pipetech SARL',           'SUB',      '+216 71 902 415', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000002', '510beefe-06bf-484c-a1bb-e47551469bdb', 'BTP Tunisie Sud SARL',   'SUB',      '+216 74 551 230', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000003', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Hydro-Forage SARL',       'SUB',      '+216 76 318 540', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000004', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Soudure Métal Plus',      'SUB',      '+216 73 220 814', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000005', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Électricité Sahel SARL',  'SUB',      '+216 73 667 921', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000006', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Bricomat SARL',           'SUPPLIER', '+216 71 945 110', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000007', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Cimenterie Tunisienne',   'SUPPLIER', '+216 72 305 660', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000008', '510beefe-06bf-484c-a1bb-e47551469bdb', 'PVC Distribution SARL',    'SUPPLIER', '+216 71 805 432', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000009', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Acier Tunis SARL',         'SUPPLIER', '+216 73 270 880', true, now(), now()),
    ('dddddd00-0000-0000-0000-000000000010', '510beefe-06bf-484c-a1bb-e47551469bdb', 'Pompes & Moteurs TN',     'SUPPLIER', '+216 71 889 615', true, now(), now())
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7. Project-scoped cash pots — one per project
-- ---------------------------------------------------------------------------
INSERT INTO cash_pot (id, org_id, type, project_id, name, currency, active, created_at)
SELECT
    ('cccccc00-0000-0000-0000-0000000000' || lpad(t.idx::text, 2, '0'))::uuid,
    '510beefe-06bf-484c-a1bb-e47551469bdb',
    'PROJECT',
    t.project_id::uuid,
    'Caisse ' || t.proj_name,
    'TND', true, now()
FROM (VALUES
    (1,'bbbbbb00-0000-0000-0000-000000000001','Adduction Aïn Draham'),
    (2,'bbbbbb00-0000-0000-0000-000000000002','Irrigation Jendouba'),
    (3,'bbbbbb00-0000-0000-0000-000000000003','Réservoir Le Kef'),
    (4,'bbbbbb00-0000-0000-0000-000000000004','Chambre Vanne Béja'),
    (5,'bbbbbb00-0000-0000-0000-000000000005','Forage Sidi Bouzid'),
    (6,'bbbbbb00-0000-0000-0000-000000000006','Pompage Nabeul'),
    (7,'bbbbbb00-0000-0000-0000-000000000007','Assainissement Sfax'),
    (8,'bbbbbb00-0000-0000-0000-000000000008','Réhabilitation Sousse'),
    (9,'bbbbbb00-0000-0000-0000-000000000009','Bassin Médenine')
) AS t(idx, project_id, proj_name)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8a. Cash IN movements — customer payments to company pot
-- ---------------------------------------------------------------------------
INSERT INTO cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                           from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                           created_by, created_at, posted_by, posted_at)
SELECT t.id::uuid, '510beefe-06bf-484c-a1bb-e47551469bdb', 'IN', t.amount::numeric, 'TND',
       t.movement_date::date, NULL, '7248d427-98ff-41c1-a089-7b44fb7c770a',
       t.project_id::uuid, NULL, t.label, t.note, t.status,
       u.id, t.created_at::timestamptz,
       CASE WHEN t.status = 'POSTED' THEN u_owner.id ELSE NULL END,
       CASE WHEN t.status = 'POSTED' THEN (t.created_at::timestamptz + interval '5 hours') ELSE NULL END
FROM (VALUES
    -- (id, amount, date, project_id, label, note, status, created_at, created_by_email)
    ('11111111-0000-0000-0000-000000000001', 555000.000, '2024-03-10', 'bbbbbb00-0000-0000-0000-000000000001', 'Acompte 30% SONEDE Adduction Aïn Draham', 'Virement reçu - avance chantier ADD-ADR-2024', 'POSTED', '2024-03-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000002', 462500.000, '2024-05-15', 'bbbbbb00-0000-0000-0000-000000000001', 'Acompte 25% SONEDE Adduction Aïn Draham', 'Virement - 2ème tranche', 'POSTED', '2024-05-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000003', 370000.000, '2024-08-20', 'bbbbbb00-0000-0000-0000-000000000001', 'Acompte 20% SONEDE Adduction Aïn Draham', 'Virement - 3ème tranche après pose conduites', 'POSTED', '2024-08-20 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000004', 462500.000, '2024-11-15', 'bbbbbb00-0000-0000-0000-000000000001', 'Solde final SONEDE Adduction Aïn Draham', 'Virement - solde après réception provisoire', 'POSTED', '2024-11-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000005', 276000.000, '2024-05-20', 'bbbbbb00-0000-0000-0000-000000000002', 'Acompte 30% CRDA Jendouba', 'Avance initiale', 'POSTED', '2024-05-20 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000006', 276000.000, '2024-08-10', 'bbbbbb00-0000-0000-0000-000000000002', 'Acompte 30% CRDA Jendouba', '2ème tranche - après bornes', 'POSTED', '2024-08-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000007', 368000.000, '2024-11-05', 'bbbbbb00-0000-0000-0000-000000000002', 'Solde 40% CRDA Jendouba', 'Solde final - en attente procès-verbal réception', 'DRAFT', '2024-11-05 09:00:00+00', 'safa.mtibaa@hydra.tn'),
    ('11111111-0000-0000-0000-000000000008', 435000.000, '2023-09-10', 'bbbbbb00-0000-0000-0000-000000000003', 'Acompte 30% SONEDE Centre - Réservoir', 'Avance initiale', 'POSTED', '2023-09-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000009', 435000.000, '2024-02-15', 'bbbbbb00-0000-0000-0000-000000000003', 'Acompte 30% SONEDE Centre - Réservoir', '2ème tranche - après gros œuvre', 'POSTED', '2024-02-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000010', 580000.000, '2024-09-01', 'bbbbbb00-0000-0000-0000-000000000003', 'Solde 40% SONEDE Centre - Réservoir', 'Solde final après réception provisoire', 'POSTED', '2024-09-01 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000011', 128000.000, '2024-06-10', 'bbbbbb00-0000-0000-0000-000000000004', 'Acompte 40% SONEDE NO - Chambre Vanne', 'Avance initiale', 'POSTED', '2024-06-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000012', 192000.000, '2024-09-15', 'bbbbbb00-0000-0000-0000-000000000004', 'Solde 60% SONEDE NO - Chambre Vanne', 'Solde après réception provisoire', 'POSTED', '2024-09-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000013', 204000.000, '2024-04-10', 'bbbbbb00-0000-0000-0000-000000000005', 'Acompte 30% CRDA SBZ - Forage', 'Avance initiale', 'POSTED', '2024-04-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000014', 476000.000, '2024-10-20', 'bbbbbb00-0000-0000-0000-000000000005', 'Solde 70% CRDA SBZ - Forage', 'Solde - réception définitive', 'POSTED', '2024-10-20 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000015', 354000.000, '2024-02-10', 'bbbbbb00-0000-0000-0000-000000000006', 'Acompte 30% SONEDE Cap Bon - Pompage', 'Avance initiale', 'POSTED', '2024-02-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000016', 472000.000, '2024-06-15', 'bbbbbb00-0000-0000-0000-000000000006', 'Acompte 40% SONEDE Cap Bon - Pompage', '2ème tranche après génie civil', 'POSTED', '2024-06-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000017', 354000.000, '2024-10-30', 'bbbbbb00-0000-0000-0000-000000000006', 'Solde 30% SONEDE Cap Bon - Pompage', 'Solde final - en cours de réception', 'POSTED', '2024-10-30 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000018', 486000.000, '2023-06-15', 'bbbbbb00-0000-0000-0000-000000000007', 'Acompte 30% ONAS Sfax - Assainissement', 'Avance initiale', 'POSTED', '2023-06-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000019', 486000.000, '2023-12-20', 'bbbbbb00-0000-0000-0000-000000000007', 'Acompte 30% ONAS Sfax', '2ème tranche', 'POSTED', '2023-12-20 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000020', 648000.000, '2024-08-15', 'bbbbbb00-0000-0000-0000-000000000007', 'Solde 40% ONAS Sfax', 'Solde final après réception', 'POSTED', '2024-08-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000021', 222000.000, '2022-09-10', 'bbbbbb00-0000-0000-0000-000000000008', 'Acompte 30% SONEDE Sahel - Réhab Sousse', 'Avance initiale', 'POSTED', '2022-09-10 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000022', 370000.000, '2023-02-20', 'bbbbbb00-0000-0000-0000-000000000008', 'Acompte 50% SONEDE Sahel', '2ème tranche', 'POSTED', '2023-02-20 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000023', 148000.000, '2023-09-15', 'bbbbbb00-0000-0000-0000-000000000008', 'Solde 20% SONEDE Sahel', 'Solde final après réception définitive', 'POSTED', '2023-09-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000024', 294000.000, '2024-07-15', 'bbbbbb00-0000-0000-0000-000000000009', 'Acompte 30% Min Agri - Bassin Médenine', 'Avance initiale - projet en phase études', 'POSTED', '2024-07-15 09:00:00+00', 'owner@hydra.tn')
) AS t(id, amount, movement_date, project_id, label, note, status, created_at, email)
JOIN app_user u ON u.email = t.email
CROSS JOIN app_user u_owner WHERE u_owner.email = 'owner@hydra.tn'
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8b. Cash TRANSFER movements — company pot → project pots
-- ---------------------------------------------------------------------------
INSERT INTO cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                           from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                           created_by, created_at, posted_by, posted_at)
SELECT t.id::uuid, '510beefe-06bf-484c-a1bb-e47551469bdb', 'TRANSFER', t.amount::numeric, 'TND',
       t.movement_date::date, '7248d427-98ff-41c1-a089-7b44fb7c770a', t.to_pot_id::uuid,
       t.project_id::uuid, NULL, t.label, t.note, t.status,
       u.id, t.created_at::timestamptz,
       CASE WHEN t.status = 'POSTED' THEN u_owner.id ELSE NULL END,
       CASE WHEN t.status = 'POSTED' THEN (t.created_at::timestamptz + interval '5 hours') ELSE NULL END
FROM (VALUES
    ('11111111-0000-0000-0000-000000000025', 300000.000, '2024-03-15', 'cccccc00-0000-0000-0000-000000000001', 'bbbbbb00-0000-0000-0000-000000000001', 'Transfert Caisse centrale → Adduction Aïn Draham', 'Fonds pour démarrage chantier', 'POSTED', '2024-03-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000026', 200000.000, '2024-05-25', 'cccccc00-0000-0000-0000-000000000002', 'bbbbbb00-0000-0000-0000-000000000002', 'Transfert Caisse centrale → Irrigation Jendouba', 'Fonds pour démarrage chantier', 'POSTED', '2024-05-25 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000027', 250000.000, '2023-09-15', 'cccccc00-0000-0000-0000-000000000003', 'bbbbbb00-0000-0000-0000-000000000003', 'Transfert Caisse centrale → Réservoir Le Kef', 'Fonds pour démarrage chantier', 'POSTED', '2023-09-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000028', 100000.000, '2024-06-15', 'cccccc00-0000-0000-0000-000000000004', 'bbbbbb00-0000-0000-0000-000000000004', 'Transfert Caisse centrale → Chambre Vanne Béja', 'Fonds pour démarrage', 'POSTED', '2024-06-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000029', 150000.000, '2024-04-15', 'cccccc00-0000-0000-0000-000000000005', 'bbbbbb00-0000-0000-0000-000000000005', 'Transfert Caisse centrale → Forage Sidi Bouzid', 'Fonds pour démarrage', 'POSTED', '2024-04-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000030', 300000.000, '2024-02-15', 'cccccc00-0000-0000-0000-000000000006', 'bbbbbb00-0000-0000-0000-000000000006', 'Transfert Caisse centrale → Pompage Nabeul', 'Fonds pour démarrage', 'POSTED', '2024-02-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000031', 280000.000, '2023-06-20', 'cccccc00-0000-0000-0000-000000000007', 'bbbbbb00-0000-0000-0000-000000000007', 'Transfert Caisse centrale → Assainissement Sfax', 'Fonds pour démarrage', 'POSTED', '2023-06-20 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000032', 150000.000, '2022-09-15', 'cccccc00-0000-0000-0000-000000000008', 'bbbbbb00-0000-0000-0000-000000000008', 'Transfert Caisse centrale → Réhabilitation Sousse', 'Fonds pour démarrage', 'POSTED', '2022-09-15 09:00:00+00', 'owner@hydra.tn'),
    ('11111111-0000-0000-0000-000000000033', 100000.000, '2024-07-20', 'cccccc00-0000-0000-0000-000000000009', 'bbbbbb00-0000-0000-0000-000000000009', 'Transfert Caisse centrale → Bassin Médenine', 'Fonds pour études initiales', 'DRAFT', '2024-07-20 09:00:00+00', 'safa.mtibaa@hydra.tn')
) AS t(id, amount, movement_date, to_pot_id, project_id, label, note, status, created_at, email)
JOIN app_user u ON u.email = t.email
CROSS JOIN app_user u_owner WHERE u_owner.email = 'owner@hydra.tn'
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8c. Cash OUT movements — payments to sous-traitants / fournisseurs from PROJECT pots
-- ---------------------------------------------------------------------------
INSERT INTO cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                           from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                           created_by, created_at, posted_by, posted_at)
SELECT t.id::uuid, '510beefe-06bf-484c-a1bb-e47551469bdb', 'OUT', t.amount::numeric, 'TND',
       t.movement_date::date, t.from_pot_id::uuid, NULL, t.project_id::uuid, t.party_id::uuid,
       t.label, t.note, t.status, u.id, t.created_at::timestamptz,
       CASE WHEN t.status = 'POSTED' THEN u_owner.id ELSE NULL END,
       CASE WHEN t.status = 'POSTED' THEN (t.created_at::timestamptz + interval '5 hours') ELSE NULL END
FROM (VALUES
    -- Project 1 (Adduction Aïn Draham)
    ('11111111-0000-0000-0000-000000000034', 145000.000, '2024-03-25', 'cccccc00-0000-0000-0000-000000000001', 'bbbbbb00-0000-0000-0000-000000000001', 'dddddd00-0000-0000-0000-000000000001', 'Paiement Pipetech SARL - fourniture PEHD DN 200/300', 'Facture PIP-2024-0142', 'POSTED', '2024-03-25 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000035', 85000.000,  '2024-05-30', 'cccccc00-0000-0000-0000-000000000001', 'bbbbbb00-0000-0000-0000-000000000001', 'dddddd00-0000-0000-0000-000000000007', 'Paiement Cimenterie Tunisienne - béton réservoir', 'Facture CIM-2024-0876', 'POSTED', '2024-05-30 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000036', 95000.000,  '2024-07-10', 'cccccc00-0000-0000-0000-000000000001', 'bbbbbb00-0000-0000-0000-000000000001', 'dddddd00-0000-0000-0000-000000000002', 'Paiement BTP Tunisie Sud - terrassement tranchées', 'Facture BTS-2024-0334', 'POSTED', '2024-07-10 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000037', 38000.000,  '2024-09-05', 'cccccc00-0000-0000-0000-000000000001', 'bbbbbb00-0000-0000-0000-000000000001', 'dddddd00-0000-0000-0000-000000000004', 'Paiement Soudure Métal Plus - bouches à clé', 'Facture SMP-2024-0198', 'POSTED', '2024-09-05 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000038', 28000.000,  '2024-10-15', 'cccccc00-0000-0000-0000-000000000001', 'bbbbbb00-0000-0000-0000-000000000001', 'dddddd00-0000-0000-0000-000000000005', 'Paiement Électricité Sahel - alimentation chambre vanne', 'Facture ELS-2024-0277', 'DRAFT', '2024-10-15 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    -- Project 2 (Irrigation Jendouba)
    ('11111111-0000-0000-0000-000000000039', 110000.000, '2024-06-15', 'cccccc00-0000-0000-0000-000000000002', 'bbbbbb00-0000-0000-0000-000000000002', 'dddddd00-0000-0000-0000-000000000008', 'Paiement PVC Distribution - tuyaux PVC DN 110-250', 'Facture PVC-2024-0421', 'POSTED', '2024-06-15 09:00:00+00', 'leila.gharbi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000040', 75000.000,  '2024-08-25', 'cccccc00-0000-0000-0000-000000000002', 'bbbbbb00-0000-0000-0000-000000000002', 'dddddd00-0000-0000-0000-000000000002', 'Paiement BTP Tunisie Sud - terrassement', 'Facture BTS-2024-0512', 'POSTED', '2024-08-25 09:00:00+00', 'leila.gharbi@hydra.tn'),
    -- Project 3 (Réservoir Le Kef)
    ('11111111-0000-0000-0000-000000000041', 165000.000, '2023-10-20', 'cccccc00-0000-0000-0000-000000000003', 'bbbbbb00-0000-0000-0000-000000000003', 'dddddd00-0000-0000-0000-000000000002', 'Paiement BTP Tunisie Sud - terrassement réservoir', 'Facture BTS-2023-0889', 'POSTED', '2023-10-20 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000042', 92000.000,  '2024-01-15', 'cccccc00-0000-0000-0000-000000000003', 'bbbbbb00-0000-0000-0000-000000000003', 'dddddd00-0000-0000-0000-000000000007', 'Paiement Cimenterie - béton armé réservoir', 'Facture CIM-2024-0021', 'POSTED', '2024-01-15 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000043', 28000.000,  '2024-09-25', 'cccccc00-0000-0000-0000-000000000003', 'bbbbbb00-0000-0000-0000-000000000003', 'dddddd00-0000-0000-0000-000000000004', 'Paiement Soudure Métal Plus - garde-corps réservoir', 'Facture SMP-2024-0389', 'POSTED', '2024-09-25 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    -- VOID movement example (annulled duplicate)
    ('11111111-0000-0000-0000-000000000044', 50000.000,  '2024-04-01', 'cccccc00-0000-0000-0000-000000000003', 'bbbbbb00-0000-0000-0000-000000000003', 'dddddd00-0000-0000-0000-000000000009', 'Paiement Acier Tunis - armatures (annulé - doublon)', 'Annulé: doublon avec facture ACN-2024-0156', 'VOID', '2024-04-01 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    -- Project 4 (Chambre Vanne Béja)
    ('11111111-0000-0000-0000-000000000045', 45000.000,  '2024-07-05', 'cccccc00-0000-0000-0000-000000000004', 'bbbbbb00-0000-0000-0000-000000000004', 'dddddd00-0000-0000-0000-000000000009', 'Paiement Acier Tunis - structure métallique chambre', 'Facture ACN-2024-0456', 'POSTED', '2024-07-05 09:00:00+00', 'mona.zribi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000046', 38000.000,  '2024-09-20', 'cccccc00-0000-0000-0000-000000000004', 'bbbbbb00-0000-0000-0000-000000000004', 'dddddd00-0000-0000-0000-000000000010', 'Paiement Pompes & Moteurs TN - vannes DN 100-200', 'Facture PMP-2024-0789', 'POSTED', '2024-09-20 09:00:00+00', 'mona.zribi@hydra.tn'),
    -- Project 5 (Forage Sidi Bouzid)
    ('11111111-0000-0000-0000-000000000047', 185000.000, '2024-05-10', 'cccccc00-0000-0000-0000-000000000005', 'bbbbbb00-0000-0000-0000-000000000005', 'dddddd00-0000-0000-0000-000000000003', 'Paiement Hydro-Forage SARL - forage 180m + pompe', 'Facture HF-2024-0123', 'POSTED', '2024-05-10 09:00:00+00', 'leila.gharbi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000048', 65000.000,  '2024-07-15', 'cccccc00-0000-0000-0000-000000000005', 'bbbbbb00-0000-0000-0000-000000000005', 'dddddd00-0000-0000-0000-000000000008', 'Paiement PVC Distribution - réseau rural 4km', 'Facture PVC-2024-0677', 'POSTED', '2024-07-15 09:00:00+00', 'leila.gharbi@hydra.tn'),
    -- Project 6 (Pompage Nabeul)
    ('11111111-0000-0000-0000-000000000049', 245000.000, '2024-03-25', 'cccccc00-0000-0000-0000-000000000006', 'bbbbbb00-0000-0000-0000-000000000006', 'dddddd00-0000-0000-0000-000000000010', 'Paiement Pompes & Moteurs TN - 3 pompes 250kW', 'Facture PMP-2024-0156', 'POSTED', '2024-03-25 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000050', 92000.000,  '2024-05-20', 'cccccc00-0000-0000-0000-000000000006', 'bbbbbb00-0000-0000-0000-000000000006', 'dddddd00-0000-0000-0000-000000000009', 'Paiement Acier Tunis - charpente bâtiment', 'Facture ACN-2024-0312', 'POSTED', '2024-05-20 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000051', 68000.000,  '2024-08-10', 'cccccc00-0000-0000-0000-000000000006', 'bbbbbb00-0000-0000-0000-000000000006', 'dddddd00-0000-0000-0000-000000000005', 'Paiement Électricité Sahel - armoire + câblage', 'Facture ELS-2024-0189', 'POSTED', '2024-08-10 09:00:00+00', 'sami.trabelsi@hydra.tn'),
    -- Project 7 (Assainissement Sfax)
    ('11111111-0000-0000-0000-000000000052', 130000.000, '2023-07-25', 'cccccc00-0000-0000-0000-000000000007', 'bbbbbb00-0000-0000-0000-000000000007', 'dddddd00-0000-0000-0000-000000000008', 'Paiement PVC Distribution - tuyaux DN 200-400', 'Facture PVC-2023-0890', 'POSTED', '2023-07-25 09:00:00+00', 'mona.zribi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000053', 75000.000,  '2023-11-15', 'cccccc00-0000-0000-0000-000000000007', 'bbbbbb00-0000-0000-0000-000000000007', 'dddddd00-0000-0000-0000-000000000002', 'Paiement BTP Tunisie Sud - terrassement 8km', 'Facture BTS-2023-1244', 'POSTED', '2023-11-15 09:00:00+00', 'mona.zribi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000054', 42000.000,  '2024-04-10', 'cccccc00-0000-0000-0000-000000000007', 'bbbbbb00-0000-0000-0000-000000000007', 'dddddd00-0000-0000-0000-000000000010', 'Paiement Pompes & Moteurs TN - station refoulement 30kW', 'Facture PMP-2024-0423', 'POSTED', '2024-04-10 09:00:00+00', 'mona.zribi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000055', 55000.000,  '2024-08-20', 'cccccc00-0000-0000-0000-000000000007', 'bbbbbb00-0000-0000-0000-000000000007', 'dddddd00-0000-0000-0000-000000000006', 'Paiement Bricomat - regards visite 120u', 'Facture BRC-2024-0612', 'DRAFT', '2024-08-20 09:00:00+00', 'mona.zribi@hydra.tn'),
    -- Project 8 (Réhabilitation Sousse, closed)
    ('11111111-0000-0000-0000-000000000056', 110000.000, '2022-10-15', 'cccccc00-0000-0000-0000-000000000008', 'bbbbbb00-0000-0000-0000-000000000008', 'dddddd00-0000-0000-0000-000000000002', 'Paiement BTP Tunisie Sud - remplacement 6km conduite', 'Facture BTS-2022-1456', 'POSTED', '2022-10-15 09:00:00+00', 'leila.gharbi@hydra.tn'),
    ('11111111-0000-0000-0000-000000000057', 88000.000,  '2023-02-25', 'cccccc00-0000-0000-0000-000000000008', 'bbbbbb00-0000-0000-0000-000000000008', 'dddddd00-0000-0000-0000-000000000006', 'Paiement Bricomat - branchements 80u', 'Facture BRC-2023-0089', 'POSTED', '2023-02-25 09:00:00+00', 'leila.gharbi@hydra.tn')
) AS t(id, amount, movement_date, from_pot_id, project_id, party_id, label, note, status, created_at, email)
JOIN app_user u ON u.email = t.email
CROSS JOIN app_user u_owner WHERE u_owner.email = 'owner@hydra.tn'
ON CONFLICT (id) DO NOTHING;

-- Update voided movement with void_reason + voided_by + voided_at
UPDATE cash_movement
SET void_reason = 'Doublon de paiement détecté - facture ACN-2024-0156 déjà payée le 2024-03-30. Mouvement annulé et remplacé.',
    voided_by = (SELECT id FROM app_user WHERE email='owner@hydra.tn'),
    voided_at = '2024-04-03 09:00:00+00'
WHERE id = '11111111-0000-0000-0000-000000000044' AND status = 'VOID' AND void_reason IS NULL;

-- ---------------------------------------------------------------------------
-- 9. Tickets — 10 sample engineering transmittal workflow tickets
-- ---------------------------------------------------------------------------
INSERT INTO ticket (id, org_id, title, description, status, created_by, created_at, resolved_at, resolved_by)
SELECT t.id::uuid, '510beefe-06bf-484c-a1bb-e47551469bdb', t.title, t.description, t.status,
       u.id, t.created_at::timestamptz, t.resolved_at::timestamptz,
       CASE WHEN t.resolved_at IS NOT NULL THEN u.id ELSE NULL END
FROM (VALUES
    ('22222222-0000-0000-0000-000000000001','Plans exécution conduite PEHD DN300','Soumission plans exécution pour validation client. 12km à valider.','IN_REVIEW','sami.trabelsi@hydra.tn','2024-03-20 14:30:00+00',NULL),
    ('22222222-0000-0000-0000-000000000002','Procès-verbal réception provisoire réservoir Le Kef','PV de réception provisoire à valider par QA.','APPROVED','sami.trabelsi@hydra.tn','2024-09-01 10:00:00+00','2024-09-10 14:00:00+00'),
    ('22222222-0000-0000-0000-000000000003','Note de calcul - fondation station pompage','Note de calcul béton armé pour fondation à valider.','IN_REVIEW','fawzi.haddad@hydra.tn','2024-03-10 11:00:00+00',NULL),
    ('22222222-0000-0000-0000-000000000004','Plan coffrage - réservoir 1000m³','Plan coffrage + ferraillage à valider.','APPROVED','ahlem.cherif@hydra.tn','2023-10-05 09:00:00+00','2023-10-15 16:00:00+00'),
    ('22222222-0000-0000-0000-000000000005','Rapport essai pression - réseau Sfax','Rapport d''essai de pression à 12 bar - 8km réseau assainissement.','IFC','mona.zribi@hydra.tn','2024-07-25 14:00:00+00',NULL),
    ('22222222-0000-0000-0000-000000000006','Carnet de chantier - Adduction Aïn Draham','Carnet de chantier phases 1-4 pour archivage.','DRAFT','mohamed.sassi@hydra.tn','2024-10-01 15:00:00+00',NULL),
    ('22222222-0000-0000-0000-000000000007','Plans IFD - chambre de vanne Béja','Plans Issued For Design à valider client.','IN_REVIEW','mona.zribi@hydra.tn','2024-06-25 10:00:00+00',NULL),
    ('22222222-0000-0000-0000-000000000008','Dossier As-Built - Réhabilitation Sousse','Dossier complet As-Built final après réception définitive.','AS_BUILT','leila.gharbi@hydra.tn','2023-08-10 09:00:00+00','2023-08-31 14:00:00+00'),
    ('22222222-0000-0000-0000-000000000009','Étude de sol - Bassin Médenine','Étude géotechnique pour digue 8m - rapport à valider.','DRAFT','ahlem.cherif@hydra.tn','2024-08-15 14:00:00+00',NULL),
    ('22222222-0000-0000-0000-000000000010','Plan de prévention HSE - Forage Sidi Bouzid','Plan de prévention HSE pour phase forage.','APPROVED','leila.gharbi@hydra.tn','2024-04-15 10:00:00+00','2024-04-25 14:00:00+00')
) AS t(id, title, description, status, email, created_at, resolved_at)
JOIN app_user u ON u.email = t.email
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 10. Documents — 40+ across all projects and folders
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_org_id uuid := '510beefe-06bf-484c-a1bb-e47551469bdb';
    v_owner uuid;
    v_sami uuid;
    v_leila uuid;
    v_mona uuid;
    v_karim uuid;
    v_ahlem uuid;
    v_fawzi uuid;
    v_safa uuid;
BEGIN
    SELECT id INTO v_owner FROM app_user WHERE email='owner@hydra.tn';
    SELECT id INTO v_sami FROM app_user WHERE email='sami.trabelsi@hydra.tn';
    SELECT id INTO v_leila FROM app_user WHERE email='leila.gharbi@hydra.tn';
    SELECT id INTO v_mona FROM app_user WHERE email='mona.zribi@hydra.tn';
    SELECT id INTO v_karim FROM app_user WHERE email='karim.bouzid@hydra.tn';
    SELECT id INTO v_ahlem FROM app_user WHERE email='ahlem.cherif@hydra.tn';
    SELECT id INTO v_fawzi FROM app_user WHERE email='fawzi.haddad@hydra.tn';
    SELECT id INTO v_safa FROM app_user WHERE email='safa.mtibaa@hydra.tn';

    -- Project 1 (Adduction Aïn Draham)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000001', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','01_ADMIN','Contrat Adduction Aïn Draham','MARCHE','APPROVED', v_owner, '2024-02-20 10:00:00+00', 'v1.0', 'Contrat signé avec SONEDE Région Nord pour le projet ADD-ADR-2024.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000002', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','02_TECH','Plans exécution PEHD DN300','PLAN','IN_REVIEW', v_sami, '2024-03-15 14:00:00+00', 'v2.1', 'Plans d''exécution pour 12km de conduite PEHD DN300 à valider par client.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000003', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','03_EXEC','Carnet de chantier Q1-Q2 2024','RAPPORT','DRAFT', v_sami, '2024-08-01 09:00:00+00', 'v0.5', 'Carnet de chantier phases 1-4, en cours de remplissage.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000004', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','05_ACHAT','Facture Pipetech SARL PIP-2024-0142','FACTURE','APPROVED', v_safa, '2024-03-28 11:00:00+00', 'v1.0', 'Facture fourniture PEHD - 145 000,000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000005', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','05_ACHAT','Bon de commande BC-2024-0042','MARCHE','APPROVED', v_sami, '2024-03-20 09:00:00+00', 'v1.0', 'BC pour fourniture PEHD DN 200 et DN 300 auprès de Pipetech SARL.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000006', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','05_ACHAT','Bon de livraison BL-2024-0098','BON_LIVRAISON','APPROVED', v_sami, '2024-03-25 14:00:00+00', 'v1.0', 'BL livraison conduites PEHD - réceptionnée sur chantier.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000007', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','07_HSE','Plan de prévention HSE','HSE','APPROVED', v_sami, '2024-02-25 10:00:00+00', 'v1.0', 'Plan de prévention HSE validé pour la durée du chantier.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000008', v_org_id, 'bbbbbb00-0000-0000-0000-000000000001','04_QUAL','PV réception provisoire adduction','PV_RECEPTION','APPROVED', v_sami, '2024-11-10 15:00:00+00', 'v1.0', 'Procès-verbal de réception provisoire réseau.');

    -- Project 3 (Réservoir Le Kef)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000009', v_org_id, 'bbbbbb00-0000-0000-0000-000000000003','01_ADMIN','Contrat Réservoir 1000m³ Le Kef','MARCHE','APPROVED', v_owner, '2023-08-15 10:00:00+00', 'v1.0', 'Contrat signé avec SONEDE Centre - 1 450 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000010', v_org_id, 'bbbbbb00-0000-0000-0000-000000000003','02_TECH','Plan coffrage réservoir','PLAN','APPROVED', v_ahlem, '2023-10-05 09:00:00+00', 'v3.0', 'Plan coffrage + ferraillage réservoir béton armé 1000m³ - validé.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000011', v_org_id, 'bbbbbb00-0000-0000-0000-000000000003','02_TECH','Note de calcul béton armé','ETUDE','APPROVED', v_fawzi, '2023-09-20 11:00:00+00', 'v1.0', 'Note de calcul structure - fondation, voiles, dalle.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000012', v_org_id, 'bbbbbb00-0000-0000-0000-000000000003','05_ACHAT','Facture Cimenterie CIM-2024-0021','FACTURE','APPROVED', v_safa, '2024-01-20 09:00:00+00', 'v1.0', 'Facture béton armé - 92 000,000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000013', v_org_id, 'bbbbbb00-0000-0000-0000-000000000003','04_QUAL','PV réception provisoire réservoir','PV_RECEPTION','APPROVED', v_sami, '2024-09-05 14:00:00+00', 'v1.0', 'PV réception provisoire après essais d''étanchéité.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000014', v_org_id, 'bbbbbb00-0000-0000-0000-000000000003','07_HSE','Rapport HSE chantier Q3-Q4 2023','HSE','APPROVED', v_sami, '2024-01-15 10:00:00+00', 'v1.0', 'Rapport HSE trimestriel - 0 accident, 1 presque-accident.');

    -- Project 5 (Forage Sidi Bouzid)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000015', v_org_id, 'bbbbbb00-0000-0000-0000-000000000005','01_ADMIN','Contrat Forage SBZ','MARCHE','APPROVED', v_owner, '2024-03-25 10:00:00+00', 'v1.0', 'Contrat CRDA Sidi Bouzid - 680 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000016', v_org_id, 'bbbbbb00-0000-0000-0000-000000000005','02_TECH','Rapport géologique forage','RAPPORT','APPROVED', v_ahlem, '2024-04-10 14:00:00+00', 'v1.0', 'Rapport géologique 180m - nappe Mallahine.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000017', v_org_id, 'bbbbbb00-0000-0000-0000-000000000005','05_ACHAT','Facture Hydro-Forage HF-2024-0123','FACTURE','APPROVED', v_safa, '2024-05-15 09:00:00+00', 'v1.0', 'Facture forage + pompe - 185 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000018', v_org_id, 'bbbbbb00-0000-0000-0000-000000000005','07_HSE','Plan prévention HSE forage','HSE','APPROVED', v_leila, '2024-04-15 10:00:00+00', 'v1.0', 'Plan prévention HSE pour phase forage.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000019', v_org_id, 'bbbbbb00-0000-0000-0000-000000000005','04_QUAL','PV réception définitive forage','PV_RECEPTION','APPROVED', v_leila, '2024-10-25 14:00:00+00', 'v1.0', 'PV réception définitive - pompe testée 22 kW, débit 35 m³/h.');

    -- Project 6 (Pompage Nabeul)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000020', v_org_id, 'bbbbbb00-0000-0000-0000-000000000006','01_ADMIN','Contrat Station Pompage Nabeul','MARCHE','APPROVED', v_owner, '2024-01-25 10:00:00+00', 'v1.0', 'Contrat SONEDE Cap Bon - 1 180 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000021', v_org_id, 'bbbbbb00-0000-0000-0000-000000000006','02_TECH','Plan bâtiment station pompage','PLAN','APPROVED', v_ahlem, '2024-02-10 11:00:00+00', 'v2.0', 'Plan bâtiment 8x6m - génie civil validé.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000022', v_org_id, 'bbbbbb00-0000-0000-0000-000000000006','05_ACHAT','Facture Pompes & Moteurs PMP-2024-0156','FACTURE','APPROVED', v_safa, '2024-03-30 09:00:00+00', 'v1.0', 'Facture 3 pompes 250kW - 245 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000023', v_org_id, 'bbbbbb00-0000-0000-0000-000000000006','05_ACHAT','Bon de commande BC-2024-0012','MARCHE','APPROVED', v_sami, '2024-03-01 10:00:00+00', 'v1.0', 'BC 3 pompes 250kW (2+1 redondance).');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000024', v_org_id, 'bbbbbb00-0000-0000-0000-000000000006','07_HSE','Plan prévention HSE','HSE','APPROVED', v_sami, '2024-02-05 10:00:00+00', 'v1.0', 'Plan HSE validé.');

    -- Project 7 (Assainissement Sfax)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000025', v_org_id, 'bbbbbb00-0000-0000-0000-000000000007','01_ADMIN','Contrat ONAS Sfax','MARCHE','APPROVED', v_owner, '2023-05-15 10:00:00+00', 'v1.0', 'Contrat ONAS Sfax - 1 620 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000026', v_org_id, 'bbbbbb00-0000-0000-0000-000000000007','02_TECH','Plans exécution réseau 8km','PLAN','APPROVED', v_ahlem, '2023-06-10 14:00:00+00', 'v1.5', 'Plans exécution réseaux PVC DN 200-400.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000027', v_org_id, 'bbbbbb00-0000-0000-0000-000000000007','05_ACHAT','Facture PVC Distribution PVC-2023-0890','FACTURE','APPROVED', v_safa, '2023-08-01 09:00:00+00', 'v1.0', 'Facture tuyaux PVC DN 200-400 - 130 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000028', v_org_id, 'bbbbbb00-0000-0000-0000-000000000007','05_ACHAT','Bon de commande BC-2023-0089','MARCHE','APPROVED', v_mona, '2023-07-15 10:00:00+00', 'v1.0', 'BC tuyaux PVC DN 200-400 auprès de PVC Distribution SARL.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000029', v_org_id, 'bbbbbb00-0000-0000-0000-000000000007','04_QUAL','Rapport essai pression réseau','RAPPORT','IN_REVIEW', v_mona, '2024-07-25 14:00:00+00', 'v1.0', 'Rapport essai pression à 12 bar - en cours de validation QA.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000030', v_org_id, 'bbbbbb00-0000-0000-0000-000000000007','07_HSE','Plan prévention HSE Sfax','HSE','APPROVED', v_mona, '2023-06-25 10:00:00+00', 'v1.0', 'Plan HSE - réseau enterré en milieu urbain.');

    -- Project 8 (Réhabilitation Sousse, closed)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000031', v_org_id, 'bbbbbb00-0000-0000-0000-000000000008','01_ADMIN','Contrat Réhabilitation Sousse','MARCHE','ARCHIVED', v_owner, '2022-08-20 10:00:00+00', 'v1.0', 'Contrat SONEDE Sahel - 740 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000032', v_org_id, 'bbbbbb00-0000-0000-0000-000000000008','02_TECH','Plans As-Built Sousse','PLAN','ARCHIVED', v_fawzi, '2023-07-15 14:00:00+00', 'v1.0', 'Plans As-Built finaux après réception définitive.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000033', v_org_id, 'bbbbbb00-0000-0000-0000-000000000008','04_QUAL','PV réception définitive Sousse','PV_RECEPTION','ARCHIVED', v_leila, '2023-08-31 14:00:00+00', 'v1.0', 'PV réception définitive - chantier clôturé.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000034', v_org_id, 'bbbbbb00-0000-0000-0000-000000000008','05_ACHAT','Facture Bricomat BRC-2023-0089','FACTURE','ARCHIVED', v_safa, '2023-03-01 09:00:00+00', 'v1.0', 'Facture branchements - 88 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000035', v_org_id, 'bbbbbb00-0000-0000-0000-000000000008','07_HSE','Rapport HSE final Sousse','HSE','ARCHIVED', v_leila, '2023-09-01 10:00:00+00', 'v1.0', 'Rapport HSE final - 0 accident.');

    -- Project 9 (Bassin Médenine, design phase)
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000036', v_org_id, 'bbbbbb00-0000-0000-0000-000000000009','01_ADMIN','Contrat Bassin Médenine','MARCHE','APPROVED', v_owner, '2024-06-20 10:00:00+00', 'v1.0', 'Contrat Ministère Agriculture - 980 000 TND.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000037', v_org_id, 'bbbbbb00-0000-0000-0000-000000000009','02_TECH','Étude de sol géotechnique','RAPPORT','IN_REVIEW', v_ahlem, '2024-08-15 14:00:00+00', 'v1.0', 'Étude géotechnique pour digue 8m - en cours de validation.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000038', v_org_id, 'bbbbbb00-0000-0000-0000-000000000009','02_TECH','Plan préliminaire digue','PLAN','DRAFT', v_fawzi, '2024-07-30 11:00:00+00', 'v0.5', 'Plan préliminaire digue en terre 8m hauteur.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000039', v_org_id, 'bbbbbb00-0000-0000-0000-000000000009','02_TECH','Note de calcul évacuateur','ETUDE','DRAFT', v_fawzi, '2024-08-20 10:00:00+00', 'v0.3', 'Note de calcul évacuateur crues - en cours.');
    PERFORM hydra_insert_demo_document('ffffff00-0000-0000-0000-000000000040', v_org_id, 'bbbbbb00-0000-0000-0000-000000000009','07_HSE','Plan prévention HSE Médenine','HSE','DRAFT', v_karim, '2024-09-05 10:00:00+00', 'v0.1', 'Plan HSE - brouillon initial.');
END $$;

-- ---------------------------------------------------------------------------
-- 11. Process instances — 5 running PAYMENT_APPROVAL workflows
-- ---------------------------------------------------------------------------
INSERT INTO process_instance (id, org_id, project_id, template_id, subject_type, subject_id,
                              status, current_step_order, created_by, created_at, updated_at)
SELECT t.id::uuid, '510beefe-06bf-484c-a1bb-e47551469bdb', t.project_id::uuid, pt.id,
       'CASH_MOVEMENT', t.subject_id::uuid, t.status, t.current_step_order, u.id,
       t.created_at::timestamptz, t.updated_at::timestamptz
FROM (VALUES
    ('33333333-0000-0000-0000-000000000001','bbbbbb00-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000038','RUNNING',2,'sami.trabelsi@hydra.tn','2024-10-15 09:00:00+00','2024-10-15 09:00:00+00'),
    ('33333333-0000-0000-0000-000000000002','bbbbbb00-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000007','RUNNING',3,'safa.mtibaa@hydra.tn','2024-11-05 09:00:00+00','2024-11-05 09:00:00+00'),
    ('33333333-0000-0000-0000-000000000003','bbbbbb00-0000-0000-0000-000000000009','11111111-0000-0000-0000-000000000033','RUNNING',2,'safa.mtibaa@hydra.tn','2024-07-20 09:00:00+00','2024-07-20 09:00:00+00'),
    ('33333333-0000-0000-0000-000000000004','bbbbbb00-0000-0000-0000-000000000007','11111111-0000-0000-0000-000000000055','COMPLETED',3,'mona.zribi@hydra.tn','2024-08-20 09:00:00+00','2024-08-21 14:00:00+00'),
    ('33333333-0000-0000-0000-000000000005','bbbbbb00-0000-0000-0000-000000000003','11111111-0000-0000-0000-000000000044','REJECTED',2,'sami.trabelsi@hydra.tn','2024-04-01 09:00:00+00','2024-04-03 09:00:00+00')
) AS t(id, project_id, subject_id, status, current_step_order, email, created_at, updated_at)
JOIN app_user u ON u.email = t.email
CROSS JOIN process_template pt
WHERE pt.key = 'PAYMENT_APPROVAL' AND pt.org_id IS NULL
ON CONFLICT (id) DO NOTHING;

-- Link cash movements to process_instance (Phase 2 will use this for auto-post)
UPDATE cash_movement
SET process_instance_id = '33333333-0000-0000-0000-000000000001'
WHERE id = '11111111-0000-0000-0000-000000000038' AND process_instance_id IS NULL;

UPDATE cash_movement
SET process_instance_id = '33333333-0000-0000-0000-000000000004'
WHERE id = '11111111-0000-0000-0000-000000000055' AND process_instance_id IS NULL;

UPDATE cash_movement
SET process_instance_id = '33333333-0000-0000-0000-000000000005'
WHERE id = '11111111-0000-0000-0000-000000000044' AND process_instance_id IS NULL;

-- ---------------------------------------------------------------------------
-- 12. Cleanup helper function (avoid leaving it in the schema)
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS hydra_insert_demo_document(uuid, uuid, uuid, text, text, text, text, uuid, timestamptz, text, text);

-- ---------------------------------------------------------------------------
-- 13. Summary
-- ---------------------------------------------------------------------------
COMMENT ON SCHEMA public IS 'Hydra ERP — V21 demo seed applied: 15 users, 9 projects, 10 parties, 10 cash pots (1 COMPANY + 9 PROJECT), 57 cash movements, 63 doc folders, 40 documents, 10 tickets, 5 process instances.';

-- End V21
