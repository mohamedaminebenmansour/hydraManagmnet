import { CtlLine } from '@app/core/services/controle.service';

/**
 * Constants of the « Synthèse » page: groupements, account sources, facture
 * levels, recap rubriques and famille order.
 */

// ── Groupements ──────────────────────────────────────────────────────────────

export type GroupementKey = 'ngnet' | 'hb' | 'sigat';

export interface Groupement {
  key: GroupementKey;
  label: string;
  /** null = every project that is in no other groupement. */
  projects: string[] | null;
}

export const GROUPEMENTS: Groupement[] = [
  { key: 'ngnet', label: 'SIGAT + NGNET', projects: ['Hajeb', 'Hammed', 'Ouled Khalfallah'] },
  { key: 'hb', label: 'SIGAT + Hani Barkia', projects: ['Zaghdoud'] },
  { key: 'sigat', label: 'SIGAT seule', projects: null },
];

export const DEFAULT_GROUPEMENT: GroupementKey = 'ngnet';

const NAMED_PROJECTS = new Set(GROUPEMENTS.flatMap(g => g.projects ?? []));

export function groupementOf(key: string | null | undefined): Groupement {
  return GROUPEMENTS.find(g => g.key === key) ?? GROUPEMENTS[0];
}

/** Whether a project belongs to a groupement. */
export function inGroupement(key: GroupementKey, proj: string): boolean {
  const g = groupementOf(key);
  return g.projects ? g.projects.includes(proj) : !NAMED_PROJECTS.has(proj);
}

/** Projects of a groupement, in its own order (« SIGAT seule »: alphabetical among {@code allProjects}). */
export function projectsOf(key: GroupementKey, allProjects: Iterable<string>): string[] {
  const g = groupementOf(key);
  if (g.projects) return [...g.projects];
  return [...new Set(allProjects)].filter(p => p && !NAMED_PROJECTS.has(p))
    .sort((a, b) => a.localeCompare(b, 'fr'));
}

// ── Account sources ──────────────────────────────────────────────────────────

/** Source titles, in display order. */
export const SOURCES = [
  'Banques (Caisse Central)',
  'Caisse bureau SIGAT',
  'Caisses NGNET (Banque Zitouna, à confirmer)',
  'Société Hani Barkia',
  'Caisses chantier',
  'Paiements à échéance',
  'Autres comptes',
] as const;

export function sourceOf(acctId: string): string {
  if (acctId === 'uib-sigat' || acctId === 'uib-omar' || acctId === 'uib-ngnet') return SOURCES[0];
  if (acctId === 'caisse-sigat') return SOURCES[1];
  if (acctId.startsWith('ngnet-')) return SOURCES[2];
  if (acctId.startsWith('hb-')) return SOURCES[3];
  if (acctId.startsWith('chantier-')) return SOURCES[4];
  if (acctId === 'caisse-echeance') return SOURCES[5];
  return SOURCES[6];
}

// ── Facture level ────────────────────────────────────────────────────────────

export const FACTURE_LEVELS = ['Facture', 'Bon', 'Bon signé (S)', 'Non facturée'] as const;
export type FactureLevel = typeof FACTURE_LEVELS[number];

/** Facture if a N° de facture, else Bon signé (S), else Bon, else Non facturée. */
export function factureLevel(l: Pick<CtlLine, 'fct' | 'bon'>): FactureLevel {
  const f = (l.fct ?? '').trim();
  const b = (l.bon ?? '').trim();
  if (f && f !== '-') return 'Facture';
  if (b === 'S' || b === 's') return 'Bon signé (S)';
  if (b && b !== '-') return 'Bon';
  return 'Non facturée';
}

/** Facturée = Facture + Bon + Bon signé (S). */
export function isFacturee(level: FactureLevel): boolean {
  return level !== 'Non facturée';
}

// ── Recap rubriques and familles ─────────────────────────────────────────────

/** The 27 headers of the Excel « Récap des dépenses par projet », in the Excel order (= ControleRubriques). */
export const RECAP_RUBRIQUES = [
  'Abri Forage', 'Charge sur marché', 'Conduite en PEHD', 'Conduite en Fonte',
  'Piéces spéciales', 'piéces des niches', 'RSP', 'Clôture', 'BC', 'Local GDA',
  'RSE 500', 'RSE 150 D', 'RSE 100 M', 'Station de Surpresseur', 'Travaux GC',
  'Travaux Divers', 'ST Abd Moula', 'ST Med Hedi El Abed', 'ST Ahmed Houimli',
  'ST Aymen Amri', 'ST Hamza Msalmi', 'ST Ismaiil Mansouri', 'MO RSP', 'MO RSE 500',
  'MO RSE 150', 'MO RSE 100', 'ST Haider Methneni',
];

export const MOUVEMENTS_INTERNES = 'Mouvements internes';

/** Famille order in the « Dépenses par famille » table; Mouvements internes last. */
export const FAMILLE_ORDER = [
  'Sous-traitance', "Main-d'œuvre", 'Fournitures et conduites', 'Ouvrages', 'Travaux',
  'Charges', 'Non classé', MOUVEMENTS_INTERNES,
];

/** Familles whose rubriques open one row per project. */
export const FAMILLES_PAR_PROJET = new Set(['Sous-traitance', "Main-d'œuvre"]);
