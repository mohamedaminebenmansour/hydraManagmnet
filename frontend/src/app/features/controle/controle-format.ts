/**
 * controle-format.ts — constants and formatting functions ported word for word
 * from docs/prototype.html. Used by every page in features/controle/.
 *
 * Money is never rounded: Intl.NumberFormat('fr-FR') with 3 decimals.
 * Dates are dd/MM/yyyy. Slug strips accents.
 */

// The three projects of the SIGAT – NGNET groupement.
export const GRP = ['Hajeb', 'Hammed', 'Ouled Khalfallah'];

// Justification status: [code, French label]
export const STATUS: [string, string][] = [
  ['none', 'Non vérifié'],
  ['justifie', 'Justifié'],
  ['partiel', 'Partiel'],
  ['non-justifie', 'Non justifié'],
  ['conteste', 'Contesté'],
];
export const STATUS_LABEL: Record<string, string> = Object.fromEntries(STATUS);

// Validation status (subcontractor meeting): [code, French label]
export const VSTATUS: [string, string][] = [
  ['none', 'Pas encore vu'],
  ['valide', 'Validé avec lui'],
  ['a-revoir', 'À revoir'],
  ['conteste', 'Contesté'],
];
export const VLABEL: Record<string, string> = Object.fromEntries(VSTATUS);

// Document types for the justification form.
export const DOCS = [
  'Facture',
  'Attachement signé',
  'Bon de livraison',
  'Bon de commande',
  'Reçu signé',
  'Ordre de virement',
  'Relevé bancaire',
  'Décompte',
  'Autre',
];

// Flags (alerts): [short label, full explanation].
export const FLAGS: Record<string, [string, string]> = {
  'especes': ['Espèces > 5 000 TND', 'Payé en espèces plus de 5 000 TND : amende de 20 % (minimum 2 000 TND) pour celui qui paie.'],
  'hors-compte': ['Absent du compte ST', 'Payé à un sous-traitant mais introuvable dans son compte sous-traitant.'],
  'benefice': ['Avance sur bénéfice', 'Avance sur bénéfice versée à un sous-traitant : à rattacher à son compte et à son contrat.'],
  'doublon': ['Doublon possible', "Même jour, même montant, même texte et même projet qu'une autre ligne."],
  'interne': ['Transfert compté deux fois', 'Transfert entre comptes noté en encaissement et en décaissement.'],
  'date': ['Date impossible', 'La date est dans le futur ou avant 2023 : faute de frappe probable.'],
  'sans-date': ['Sans date', "La ligne n'a pas de date."],
  'non-retrouve': ['Paiement non retrouvé', "Ce paiement du compte sous-traitant n'a été retrouvé dans aucune caisse."],
};

// Account grouping by kind.
export const ACCT_GROUP: Record<string, string> = {
  banque: 'Banques',
  especes: 'Espèces',
  chantier: 'Caisses chantier',
  partenaire: 'Caisses partenaires',
  echeance: 'Caisse à échéance',
};

// ── Formatters (ported from the prototype, same behaviour) ──────────────────

const nf = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
const nf0 = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });
const pf = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 });

/** Money with 3 decimals. Empty string for 0/null/undefined (same as prototype). */
export function money(v: number | null | undefined): string {
  return v ? nf.format(v) : '';
}

/** Money with 0 decimals. '0' for 0/null/undefined. */
export function money0(v: number | null | undefined): string {
  return nf0.format(v || 0);
}

/** Percentage, 0 decimals. */
export function pct(v: number | null | undefined): string {
  return pf.format(v ?? 0);
}

/** Date dd/MM/yyyy from a yyyy-MM-dd string. '—' for empty. */
export function dfmt(s: string | null | undefined): string {
  return s ? s.slice(8, 10) + '/' + s.slice(5, 7) + '/' + s.slice(0, 4) : '—';
}

/** Amount for the CSV export: 3 decimals with a decimal comma; '' for empty or zero. */
export function csvAmount(v: number | null | undefined): string {
  return v ? Number(v).toFixed(3).replace('.', ',') : '';
}

/**
 * Download a CSV that opens correctly in French Excel: UTF-8 with BOM, « ; » separator,
 * file name SIGAT_<page>_<yyyy-mm-dd>.csv. (Phase 3 replaces it with a real .xlsx export.)
 */
export function downloadCsv(page: string, head: string[], rows: unknown[][]): void {
  const q = (v: unknown) => '"' + String(v ?? '').replace(/"/g, '""') + '"';
  const lines = [head.map(q).join(';'), ...rows.map(r => r.map(q).join(';'))];
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
  const now = new Date();
  const day = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-'
    + String(now.getDate()).padStart(2, '0');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'SIGAT_' + slug(page) + '_' + day + '.csv';
  document.body.append(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(a.href);
}

/** Slug: lowercase, strip accents, non-alphanumeric → '-', trim leading/trailing '-'. */
export function slug(s: string): string {
  return s.toLowerCase().normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}
