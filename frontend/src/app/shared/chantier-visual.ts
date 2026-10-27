/**
 * ============================================================================
 *  LANGAGE VISUEL CHANTIER — source unique de vérité (icônes + couleurs)
 * ============================================================================
 *  Principe UX (leçon Odoo) : l'utilisateur doit COMPRENDRE SANS LIRE.
 *  - Chaque type de chantier = 1 couleur + 1 icône, partout (liste, détail, filtres)
 *  - Chaque statut = 1 feu tricolore (point de couleur + icône)
 *  - Chaque type de document = 1 icône métier reconnaissable
 *  - Les mots restent présents (accessibilité) mais ne portent pas seuls le sens.
 * ============================================================================
 */

export interface TypeVisual {
  icon: string;       // classe PrimeIcons
  color: string;      // couleur principale (bandes, icônes, chips)
  soft: string;       // fond très clair (disques, tags doux)
  gradient: string;   // dégradé pour les héros / en-têtes
  label: string;      // libellé FR court
}

/** Identité visuelle par type de chantier. */
export const CHANTIER_TYPE_VISUAL: Record<string, TypeVisual> = {
  RESERVOIR: {
    icon: 'pi-database', color: '#0891b2', soft: '#ecfeff',
    gradient: 'linear-gradient(135deg, #22d3ee 0%, #0891b2 100%)',
    label: 'Réservoir'
  },
  RESEAU: {
    icon: 'pi-sitemap', color: '#4f46e5', soft: '#eef2ff',
    gradient: 'linear-gradient(135deg, #818cf8 0%, #4f46e5 100%)',
    label: 'Réseau'
  },
  BRANCHEMENTS: {
    icon: 'pi-share-alt', color: '#0d9488', soft: '#f0fdfa',
    gradient: 'linear-gradient(135deg, #2dd4bf 0%, #0d9488 100%)',
    label: 'Branchements'
  },
  MIXTE: {
    icon: 'pi-th-large', color: '#7c3aed', soft: '#f5f3ff',
    gradient: 'linear-gradient(135deg, #a78bfa 0%, #7c3aed 100%)',
    label: 'Mixte'
  },
  AUTRE: {
    icon: 'pi-folder', color: '#64748b', soft: '#f8fafc',
    gradient: 'linear-gradient(135deg, #94a3b8 0%, #64748b 100%)',
    label: 'Autre'
  },
  // ── natures métier hydraulique (miroir du backend ProjectType) ─────────
  ADDUCTION: {
    icon: 'pi-compass', color: '#2563eb', soft: '#eff6ff',
    gradient: 'linear-gradient(135deg, #60a5fa 0%, #2563eb 100%)',
    label: 'Adduction'
  },
  IRRIGATION: {
    icon: 'pi-sun', color: '#b45309', soft: '#fffbeb',
    gradient: 'linear-gradient(135deg, #fbbf24 0%, #b45309 100%)',
    label: 'Irrigation'
  },
  VANNE: {
    icon: 'pi-toggle-on', color: '#0ea5e9', soft: '#f0f9ff',
    gradient: 'linear-gradient(135deg, #38bdf8 0%, #0284c7 100%)',
    label: 'Chambre de vanne'
  },
  FORAGE: {
    icon: 'pi-angle-double-down', color: '#92400e', soft: '#fffbeb',
    gradient: 'linear-gradient(135deg, #d97706 0%, #92400e 100%)',
    label: 'Forage'
  },
  POMPAGE: {
    icon: 'pi-cog', color: '#ea580c', soft: '#fff7ed',
    gradient: 'linear-gradient(135deg, #fb923c 0%, #ea580c 100%)',
    label: 'Station de pompage'
  },
  ASSAINISSEMENT: {
    icon: 'pi-filter', color: '#059669', soft: '#ecfdf5',
    gradient: 'linear-gradient(135deg, #34d399 0%, #059669 100%)',
    label: 'Assainissement'
  },
  REHABILITATION: {
    icon: 'pi-refresh', color: '#7c3aed', soft: '#f5f3ff',
    gradient: 'linear-gradient(135deg, #a78bfa 0%, #7c3aed 100%)',
    label: 'Réhabilitation'
  },
  BASSIN: {
    icon: 'pi-inbox', color: '#0891b2', soft: '#ecfeff',
    gradient: 'linear-gradient(135deg, #22d3ee 0%, #0e7490 100%)',
    label: 'Bassin'
  }
};

export function typeVisual(t: string | null | undefined): TypeVisual {
  return (t && CHANTIER_TYPE_VISUAL[t]) || CHANTIER_TYPE_VISUAL['AUTRE'];
}

export interface StatusVisual {
  icon: string;
  color: string;   // couleur du point / icône
  soft: string;    // fond doux du chip
  label: string;
}

/** Feu tricolore du cycle de vie du chantier. */
export const CHANTIER_STATUS_VISUAL: Record<string, StatusVisual> = {
  ACTIF:   { icon: 'pi-play',         color: '#16a34a', soft: '#f0fdf4', label: 'En cours' },
  CLOTURE: { icon: 'pi-lock',         color: '#64748b', soft: '#f1f5f9', label: 'Clôturé' }
};

export function statusVisual(s: string | null | undefined): StatusVisual {
  return (s && CHANTIER_STATUS_VISUAL[s]) || CHANTIER_STATUS_VISUAL['ACTIF'];
}

export interface DocTypeVisual {
  icon: string;
  color: string;
  soft: string;
}

/** Icône métier par type de document — reconnaissable au premier regard. */
export const DOC_TYPE_VISUAL: Record<string, DocTypeVisual> = {
  APD:           { icon: 'pi-file',        color: '#64748b', soft: '#f1f5f9' },
  ETUDE:         { icon: 'pi-book',        color: '#2563eb', soft: '#eff6ff' },
  PLAN:          { icon: 'pi-map',         color: '#7c3aed', soft: '#f5f3ff' },
  PV_RECEPTION:  { icon: 'pi-verified',    color: '#16a34a', soft: '#f0fdf4' },
  MARCHE:        { icon: 'pi-briefcase',   color: '#b45309', soft: '#fffbeb' },
  FACTURE:       { icon: 'pi-euro',        color: '#dc2626', soft: '#fef2f2' },
  SITUATION:     { icon: 'pi-chart-line',  color: '#0891b2', soft: '#ecfeff' },
  BON_LIVRAISON: { icon: 'pi-truck',       color: '#ca8a04', soft: '#fefce8' },
  RAPPORT:       { icon: 'pi-file-edit',   color: '#475569', soft: '#f8fafc' },
  PHOTO:         { icon: 'pi-camera',      color: '#db2777', soft: '#fdf2f8' },
  HSE:           { icon: 'pi-shield',      color: '#ea580c', soft: '#fff7ed' },
  AUTRE:         { icon: 'pi-file',        color: '#64748b', soft: '#f1f5f9' }
};

export function docTypeVisual(t: string | null | undefined): DocTypeVisual {
  return (t && DOC_TYPE_VISUAL[t]) || DOC_TYPE_VISUAL['AUTRE'];
}

export interface DocStatusVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

/** Cycle de vie documentaire : brouillon → revue → approuvé / rejeté / obsolète. */
export const DOC_STATUS_VISUAL: Record<string, DocStatusVisual> = {
  DRAFT:      { icon: 'pi-pencil',       color: '#64748b', soft: '#f1f5f9', label: 'Brouillon' },
  IN_REVIEW:  { icon: 'pi-clock',        color: '#ea580c', soft: '#fff7ed', label: 'En revue' },
  APPROVED:   { icon: 'pi-check-circle', color: '#16a34a', soft: '#f0fdf4', label: 'Approuvé' },
  REJECTED:   { icon: 'pi-times-circle', color: '#dc2626', soft: '#fef2f2', label: 'Rejeté' },
  SUPERSEDED: { icon: 'pi-history',      color: '#94a3b8', soft: '#f8fafc', label: 'Obsolète' },
  ARCHIVED:   { icon: 'pi-archive',      color: '#64748b', soft: '#f1f5f9', label: 'Archivé' }
};

export function docStatusVisual(s: string | null | undefined): DocStatusVisual {
  return (s && DOC_STATUS_VISUAL[s]) || DOC_STATUS_VISUAL['DRAFT'];
}

/**
 * Icône de dossier déduite du nom (heuristique métier BTP/hydraulique) :
 * "Plans" → carte, "Photos" → appareil, "PV" → vérifié, etc.
 */
export function folderIcon(name: string | null | undefined): string {
  const n = (name ?? '').toLowerCase();
  if (/(plan|dessin|drawing|dwg)/.test(n)) { return 'pi-map'; }
  if (/(photo|image|cran)/.test(n)) { return 'pi-camera'; }
  if (/\bpv\b|réception|reception|épreuve|epreuve|qualité|qualite/.test(n)) { return 'pi-verified'; }
  if (/(facture|situation|paiement|finance|comptabilit)/.test(n)) { return 'pi-euro'; }
  if (/(marché|marche|contract|contrat)/.test(n)) { return 'pi-briefcase'; }
  if (/(hse|sécurité|securite|hygi|environnement)/.test(n)) { return 'pi-shield'; }
  if (/(rapport|compte rendu|cr\b)/.test(n)) { return 'pi-file-edit'; }
  if (/(correspondance|courrier|lettre|mail|administrat)/.test(n)) { return 'pi-envelope'; }
  if (/(étude|etude|note de calcul|synthèse|technique)/.test(n)) { return 'pi-book'; }
  if (/(livraison|approvisionnement|achat)/.test(n)) { return 'pi-truck'; }
  if (/(exécution|execution|travaux|chantier)/.test(n)) { return 'pi-wrench'; }
  return 'pi-folder';
}
