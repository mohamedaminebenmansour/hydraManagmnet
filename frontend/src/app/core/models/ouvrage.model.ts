/**
 * ============================================================================
 *  Ouvrage model — the structured objects of a chantier.
 * ============================================================================
 *  Mirrors the backend OuvrageType enum + characteristics spec from
 *  docs/BORDEREAU-ANALYSIS.md §5 (grounded in 8 bordereaux analysed on disk).
 *
 *  The spec drives the dynamic form in the chantier creation wizard and the
 *  Ouvrages tab: each characteristic key knows its kind (number with unit,
 *  integer, boolean, choice, text, or a troncons array for CONDUITE), so the
 *  UI builds the right input automatically.
 * ============================================================================
 */

export type OuvrageType =
  | 'RESERVOIR' | 'CONDUITE' | 'STATION_POMPAGE' | 'FORAGE'
  | 'OUVRAGE_ANNEXE' | 'BRANCHEMENTS' | 'ASSAINISSEMENT'
  | 'COMMUN' | 'AUTRE';

export type OuvrageKind =
  | 'NUMBER_WITH_UNIT' | 'INTEGER' | 'BOOLEAN' | 'CHOICE' | 'TEXT'
  | 'TRONCONS_ARRAY';

export interface OuvrageSpec {
  kind: OuvrageKind;
  /** For CHOICE only — the closed list of allowed values. */
  choices?: string[];
  /** French label shown above the input. */
  label: string;
  /** Unit suffix shown beside a NUMBER_WITH_UNIT input (e.g. "m³", "m", "bar"). */
  unit?: string;
}

/** A tronçon of a CONDUITE — one diameter run inside a network. */
export interface Troncon {
  de_mm: number;
  pn_bar: number;   // 10 or 16
  longueur_m: number;
}

/** An ouvrage as returned by GET /api/projects/{projectId}/ouvrages. */
export interface Ouvrage {
  id: string;
  projectId: string;
  type: OuvrageType;
  code?: string | null;
  name: string;
  characteristics: Record<string, any>;
  sortOrder?: number | null;
  createdAt?: string | null;
}

/** POST /api/projects/{projectId}/ouvrages body. */
export interface OuvrageCreate {
  type: OuvrageType;
  code?: string | null;
  name: string;
  characteristics?: Record<string, any>;
  sortOrder?: number | null;
}

/** PUT /api/projects/{projectId}/ouvrages/{ouvrageId} body. */
export interface OuvrageUpdate {
  code?: string | null;
  name?: string | null;
  characteristics?: Record<string, any>;
  sortOrder?: number | null;
}

// ============================================================================
//  TYPE LABELS + COLORS (French, single source of truth for the UI)
// ============================================================================

export const OUVRAGE_TYPE_LABELS: Record<OuvrageType, string> = {
  RESERVOIR: 'Réservoir',
  CONDUITE: 'Conduite',
  STATION_POMPAGE: 'Station de pompage',
  FORAGE: 'Forage',
  OUVRAGE_ANNEXE: 'Ouvrage annexe',
  BRANCHEMENTS: 'Branchements',
  ASSAINISSEMENT: 'Assainissement',
  COMMUN: 'Commun',
  AUTRE: 'Autre',
};

/** Color + icon per ouvrage type — reuses the HYDRA design palette so the
 *  cards feel native. Mirrors the role-color discipline of hydra-design.ts. */
export const OUVRAGE_TYPE_VISUAL: Record<OuvrageType, { icon: string; color: string; soft: string }> = {
  RESERVOIR:       { icon: 'pi-database',  color: '#7c3aed', soft: '#f5f3ff' },
  CONDUITE:        { icon: 'pi-arrow-right-arrow-left', color: '#0ea5e9', soft: '#f0f9ff' },
  STATION_POMPAGE: { icon: 'pi-bolt',      color: '#0891b2', soft: '#ecfeff' },
  FORAGE:          { icon: 'pi-circle-fill', color: '#b45309', soft: '#fffbeb' },
  OUVRAGE_ANNEXE:  { icon: 'pi-sitemap',   color: '#64748b', soft: '#f1f5f9' },
  BRANCHEMENTS:    { icon: 'pi-share-alt', color: '#db2777', soft: '#fdf2f8' },
  ASSAINISSEMENT:  { icon: 'pi-trash',     color: '#16a34a', soft: '#f0fdf4' },
  COMMUN:          { icon: 'pi-compass',   color: '#475569', soft: '#f8fafc' },
  AUTRE:           { icon: 'pi-box',       color: '#64748b', soft: '#f1f5f9' },
};

export function ouvrageTypeVisual(t: OuvrageType | string | null | undefined) {
  const k = String(t ?? '').toUpperCase() as OuvrageType;
  return OUVRAGE_TYPE_VISUAL[k] ?? OUVRAGE_TYPE_VISUAL['AUTRE'];
}

export function ouvrageTypeLabel(t: OuvrageType | string | null | undefined): string {
  const k = String(t ?? '').toUpperCase() as OuvrageType;
  return OUVRAGE_TYPE_LABELS[k] ?? t ?? '—';
}

// ============================================================================
//  CHARACTERISTICS SPEC (verbatim from BORDEREAU-ANALYSIS §5)
// ============================================================================
//
//  Each entry: key → { kind, choices?, label, unit? }.
//  - NUMBER_WITH_UNIT → numeric input with the unit suffix shown beside it.
//  - INTEGER → numeric input (no decimal).
//  - BOOLEAN → toggle switch.
//  - CHOICE → p-select dropdown with the closed list.
//  - TEXT → free text input.
//  - TRONCONS_ARRAY → only CONDUITE; a sub-form to add/remove tronçons.
//
//  COMMUN and ASSAINISSEMENT have NO characteristics (empty map) — the
//  import rejects unknown keys server-side, so the UI shows no form fields.

export const OUVRAGE_CHARACTERISTICS: Record<OuvrageType, Record<string, OuvrageSpec>> = {
  CONDUITE: {
    usage:              { kind: 'CHOICE', choices: ['ADDUCTION', 'REFOULEMENT', 'DISTRIBUTION'], label: 'Usage' },
    materiau:           { kind: 'CHOICE', choices: ['PEHD', 'FONTE', 'PVC', 'ACIER_GALVANISE', 'AC'], label: 'Matériau' },
    de_mm:              { kind: 'INTEGER', label: 'Diamètre extérieur (PEHD/PVC)', unit: 'mm' },
    dn_mm:              { kind: 'INTEGER', label: 'Diamètre nominal (fonte/acier)', unit: 'mm' },
    pn_bar:             { kind: 'CHOICE', choices: ['10', '16'], label: 'Pression nominale', unit: 'bar' },
    assemblage:         { kind: 'CHOICE', choices: ['ELECTROSOUDABLE', 'BOUT_A_BOUT', 'BRIDE', 'JOINT_CAOUTCHOUC'], label: 'Assemblage' },
    longueur_m:         { kind: 'NUMBER_WITH_UNIT', label: 'Longueur totale', unit: 'm' },
    largeur_tranchee_m: { kind: 'NUMBER_WITH_UNIT', label: 'Largeur de tranchée', unit: 'm' },
    troncons:           { kind: 'TRONCONS_ARRAY', label: 'Tronçons (par diamètre)' },
  },
  RESERVOIR: {
    sous_type:            { kind: 'CHOICE', choices: ['SEMI_ENTERRE', 'SURELEVE', 'SUR_PILIERS', 'BRISE_CHARGE', 'BACHE_REPRISE', 'CUVE_SUR_SUPPORT', 'ENTERRE'], label: 'Sous-type' },
    capacite_m3:          { kind: 'NUMBER_WITH_UNIT', label: 'Capacité', unit: 'm³' },
    materiau:             { kind: 'CHOICE', choices: ['BETON_ARME', 'RESINE', 'MACONNERIE'], label: 'Matériau' },
    dosage_beton_kg_m3:   { kind: 'INTEGER', label: 'Dosage béton', unit: 'kg/m³' },
    revetement_interieur: { kind: 'CHOICE', choices: ['ENDUIT_ETANCHE', 'CUVELAGE', 'CARRELAGE', 'ENDUIT_HYDROFUGE'], label: 'Revêtement intérieur' },
    dn_arrivee:           { kind: 'INTEGER', label: 'DN arrivée', unit: 'mm' },
    dn_depart:            { kind: 'INTEGER', label: 'DN départ', unit: 'mm' },
    dn_trop_plein:        { kind: 'INTEGER', label: 'DN trop-plein', unit: 'mm' },
    dn_vidange:           { kind: 'INTEGER', label: 'DN vidange', unit: 'mm' },
    cloture:              { kind: 'BOOLEAN', label: 'Clôture' },
  },
  STATION_POMPAGE: {
    sous_type:       { kind: 'CHOICE', choices: ['STATION_REPRISE', 'POMPAGE_FORAGE'], label: 'Sous-type' },
    debit_m3h:       { kind: 'NUMBER_WITH_UNIT', label: 'Débit', unit: 'm³/h' },
    hmt_m:           { kind: 'NUMBER_WITH_UNIT', label: 'HMT', unit: 'm' },
    nb_pompes:       { kind: 'INTEGER', label: 'Nombre de pompes' },
    puissance_kw:    { kind: 'NUMBER_WITH_UNIT', label: 'Puissance', unit: 'kW' },
    energie:         { kind: 'CHOICE', choices: ['RESEAU_ELECTRIQUE', 'SOLAIRE'], label: 'Énergie' },
    chloration:      { kind: 'BOOLEAN', label: 'Chloration' },
    local_technique: { kind: 'BOOLEAN', label: 'Local technique' },
  },
  FORAGE: {
    type:                   { kind: 'CHOICE', choices: ['FORAGE_MECANIQUE', 'FORAGE_MANUEL', 'PUITS'], label: 'Type' },
    profondeur_m:           { kind: 'NUMBER_WITH_UNIT', label: 'Profondeur', unit: 'm' },
    diametre_tubage_mm:     { kind: 'NUMBER_WITH_UNIT', label: 'Diamètre de tubage', unit: 'mm' },
    materiau_tubage:        { kind: 'CHOICE', choices: ['PVC', 'ACIER'], label: 'Matériau de tubage' },
    debit_exploitation_m3h: { kind: 'NUMBER_WITH_UNIT', label: 'Débit d\'exploitation', unit: 'm³/h' },
  },
  OUVRAGE_ANNEXE: {
    type:       { kind: 'CHOICE', choices: ['VENTOUSE', 'VIDANGE', 'SECTIONNEMENT', 'PIQUAGE', 'REDUCTEUR_PRESSION', 'BRISE_CHARGE_EN_LIGNE'], label: 'Type' },
    modele:     { kind: 'TEXT', label: 'Modèle' },
    dimensions: { kind: 'TEXT', label: 'Dimensions' },
    pk:         { kind: 'NUMBER_WITH_UNIT', label: 'Position (PK)', unit: 'm' },
  },
  BRANCHEMENTS: {
    nb_bornes_fontaines:          { kind: 'INTEGER', label: 'Bornes fontaines' },
    nb_potences:                  { kind: 'INTEGER', label: 'Potences' },
    nb_branchements_publics:      { kind: 'INTEGER', label: 'Branchements publics' },
    nb_branchements_particuliers: { kind: 'INTEGER', label: 'Branchements particuliers' },
  },
  ASSAINISSEMENT: {},
  COMMUN: {},
  AUTRE: {},
};

/** Helper: the ordered list of (key, spec) pairs for a type — used by the
 *  dynamic form. Returns [] for COMMUN and ASSAINISSEMENT (no characteristics). */
export function ouvrageCharacteristics(t: OuvrageType | string | null | undefined): { key: string; spec: OuvrageSpec }[] {
  const k = String(t ?? '').toUpperCase() as OuvrageType;
  const map = OUVRAGE_CHARACTERISTICS[k] ?? {};
  return Object.entries(map).map(([key, spec]) => ({ key, spec }));
}

/** Defensive mapper: backend → Ouvrage. */
export function mapOuvrage(raw: any): Ouvrage {
  return {
    id: String(raw?.id ?? ''),
    projectId: String(raw?.projectId ?? raw?.project_id ?? ''),
    type: String(raw?.type ?? 'AUTRE').toUpperCase() as OuvrageType,
    code: raw?.code ?? null,
    name: String(raw?.name ?? ''),
    characteristics: (raw?.characteristics && typeof raw.characteristics === 'object')
      ? raw.characteristics : {},
    sortOrder: raw?.sortOrder ?? raw?.sort_order ?? null,
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}
