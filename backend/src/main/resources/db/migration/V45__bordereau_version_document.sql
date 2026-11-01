-- ============================================================================
-- V45 — bordereau_version → the Excel file it was imported from.
--
-- Each Excel import is stored as a revision of ONE document "Bordereau des prix"
-- in the chantier folder 02_TECH / "Bordereaux et DQE". The version keeps the
-- document and the revision label it came from (e.g. 'Rev.0', 'Rev.1').
-- Both null for versions created by hand (avenant copy) or imported before V45.
-- ============================================================================

alter table bordereau_version
    add column if not exists source_document_id uuid null references document(id),
    add column if not exists source_revision    text null;

comment on column bordereau_version.source_document_id is 'Document holding the imported Excel file (null = not imported / before V45).';
comment on column bordereau_version.source_revision    is 'Revision label of that document for this import, e.g. Rev.1.';
