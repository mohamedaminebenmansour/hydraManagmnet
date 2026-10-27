/**
 * ============================================================================
 *  HYDRA DESIGN SYSTEM — source unique de vérité visuelle
 * ============================================================================
 *  Inspiré de la recherche UX :
 *    - Odoo/SAP échouent : trop de champs inutiles, navigation confuse,
 *      pensée "database-centric" (les écrans reflètent les tables SQL,
 *      pas les tâches utilisateur), absence de hiérarchie visuelle.
 *    - Instagram/TikTok/Facebook réussissent : hiérarchie visuelle forte
 *      (le plus important = le plus gros), couleur+icône portent le sens
 *      (pas la déco), feedback micro-interactif, personnalisation
 *      ("montre-moi ce qui est à MOI d'abord"), gestalt (groupes visuels).
 *
 *  Principes appliqués à Hydra (BTP hydraulique Tunisie) :
 *    1. ONE-SCREEN-ONE-PURPOSE — chaque écran dit ce qu'il fait en 1 regard
 *    2. COLOR + ICON = SENS — pas déco. Le rouge = danger/bloqué,
 *       le vert = OK, l'orange = en attente, le gris = inactif
 *    3. PROGRESSIVE DISCLOSURE — montrer ce qui est utile maintenant,
 *       révéler le reste au clic
 *    4. PERSONALISATION — "Mes tâches" avant "Toutes les tâches"
 *    5. VISUAL HIERARCHY — le bouton principal gros + coloré, le
 *       secondaire petit + outline
 *    6. ROLE-BADGE PARTOUT — quand on voit une personne, on voit
 *       IMMÉDIATEMENT son rôle métier (couleur + icône)
 * ============================================================================
 */

// ============================================================================
//  1. RÔLES MÉTIERS — couleur + icône + libellé FR
// ============================================================================

export interface RoleVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
  description?: string;
}

/**
 * Couleurs dérivées d'une palette BTP cohérente :
 *   - OWNER = or (gérant, autorité)
 *   - DIRECTION/CHEF_PROJET = indigo (stratégie)
 *   - INGENIEUR/BUREAU = bleu (technique)
 *   - QA = vert (qualité)
 *   - ACHAT = ambre (transactions)
 *   - FINANCE = cyan (argent)
 *   - CHEF_CHANTIER = rouge (terrain, urgence)
 *   - HSE = orange (sécurité)
 *   - SOUS_TRAITANT = jaune (externe, accès restreint)
 */
export const ROLE_VISUAL: Record<string, RoleVisual> = {
  OWNER: {
    icon: 'pi-crown', color: '#b45309', soft: '#fffbeb',
    label: 'Gérant', description: 'Propriétaire de l\'entreprise'
  },
  DIRECTION: {
    icon: 'pi-briefcase', color: '#7c3aed', soft: '#f5f3ff',
    label: 'Direction', description: 'DG / Direction générale'
  },
  CHEF_PROJET: {
    icon: 'pi-briefcase', color: '#6366f1', soft: '#eef2ff',
    label: 'Chef de projet', description: 'Pilote les chantiers'
  },
  CONDUCTEUR: {
    icon: 'pi-compass', color: '#4f46e5', soft: '#eef2ff',
    label: 'Conducteur', description: 'Conducteur de travaux'
  },
  INGENIEUR: {
    icon: 'pi-calculator', color: '#2563eb', soft: '#eff6ff',
    label: 'Ingénieur', description: 'Bureau d\'études'
  },
  BUREAU: {
    icon: 'pi-pencil', color: '#2563eb', soft: '#eff6ff',
    label: 'Bureau d\'études', description: 'Études techniques'
  },
  QA: {
    icon: 'pi-verified', color: '#16a34a', soft: '#f0fdf4',
    label: 'Qualité', description: 'Contrôle qualité / réception'
  },
  DOC_CTRL: {
    icon: 'pi-file-edit', color: '#0891b2', soft: '#ecfeff',
    label: 'Contrôle doc.', description: 'Documentation'
  },
  ACHAT: {
    icon: 'pi-shopping-cart', color: '#b45309', soft: '#fffbeb',
    label: 'Achat', description: 'Achats / fournisseurs'
  },
  FINANCE: {
    icon: 'pi-wallet', color: '#0d9488', soft: '#f0fdfa',
    label: 'Finance', description: 'Comptabilité / paiements'
  },
  CHEF_CHANTIER: {
    icon: 'pi-hammer', color: '#dc2626', soft: '#fef2f2',
    label: 'Chef chantier', description: 'Team leader terrain'
  },
  HSE: {
    icon: 'pi-shield', color: '#ea580c', soft: '#fff7ed',
    label: 'HSE', description: 'Sécurité / environnement'
  },
  SOUS_TRAITANT: {
    icon: 'pi-truck', color: '#ca8a04', soft: '#fefce8',
    label: 'Sous-traitant', description: 'Accès restreint externe'
  },
};

/** Retourne la visualisation d'un rôle métier, ou un fallback neutre. */
export function roleVisual(role: string | null | undefined): RoleVisual {
  if (!role) return { icon: 'pi-user', color: '#64748b', soft: '#f8fafc', label: '—' };
  return ROLE_VISUAL[role.toUpperCase()] || {
    icon: 'pi-user', color: '#64748b', soft: '#f8fafc', label: role
  };
}

/** Liste ordonnée pour les selects du builder (13 rôles actifs). */
export const ROLE_OPTIONS = Object.keys(ROLE_VISUAL).map(code => ({
  code,
  label: ROLE_VISUAL[code].label,
  icon: ROLE_VISUAL[code].icon,
  color: ROLE_VISUAL[code].color,
  soft: ROLE_VISUAL[code].soft,
  description: ROLE_VISUAL[code].description,
}));

// ============================================================================
//  2. PROJECT ROLES (permission level — pas business role)
//     ADMIN/MANAGER/ENGINEER/VIEWER sur project_member
// ============================================================================

export interface ProjectRoleVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

export const PROJECT_ROLE_VISUAL: Record<string, ProjectRoleVisual> = {
  ADMIN:    { icon: 'pi-shield', color: '#dc2626', soft: '#fef2f2', label: 'Admin projet' },
  MANAGER:  { icon: 'pi-briefcase', color: '#f97316', soft: '#fff7ed', label: 'Gestionnaire' },
  ENGINEER: { icon: 'pi-cog', color: '#2563eb', soft: '#eff6ff', label: 'Ingénieur projet' },
  VIEWER:   { icon: 'pi-eye', color: '#6b7280', soft: '#f1f5f9', label: 'Lecture seule' },
};

export function projectRoleVisual(role: string | null | undefined): ProjectRoleVisual {
  if (!role) return { icon: 'pi-eye', color: '#6b7280', soft: '#f1f5f9', label: 'Lecture' };
  return PROJECT_ROLE_VISUAL[role.toUpperCase()] || PROJECT_ROLE_VISUAL['VIEWER'];
}

// ============================================================================
//  3. STATUTS PROCESSUS & ÉTAPES — feu tricolore
// ============================================================================

export interface StatusVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

export const PROCESS_STATUS_VISUAL: Record<string, StatusVisual> = {
  RUNNING:   { icon: 'pi-play',         color: '#0891b2', soft: '#ecfeff',  label: 'En cours' },
  COMPLETED: { icon: 'pi-check-circle', color: '#16a34a', soft: '#f0fdf4', label: 'Terminé' },
  REJECTED:  { icon: 'pi-times-circle', color: '#dc2626', soft: '#fef2f2', label: 'Refusé' },
  CANCELLED: { icon: 'pi-ban',          color: '#64748b', soft: '#f1f5f9', label: 'Annulé' },
};

export function processStatusVisual(s: string | null | undefined): StatusVisual {
  return (s && PROCESS_STATUS_VISUAL[s.toUpperCase()]) || PROCESS_STATUS_VISUAL['RUNNING'];
}

export const STEP_STATUS_VISUAL: Record<string, StatusVisual> = {
  PENDING:  { icon: 'pi-clock',         color: '#ea580c', soft: '#fff7ed', label: 'En attente' },
  DONE:     { icon: 'pi-check-circle',  color: '#16a34a', soft: '#f0fdf4', label: 'Terminé' },
  REJECTED: { icon: 'pi-times-circle',  color: '#dc2626', soft: '#fef2f2', label: 'Refusé' },
  SKIPPED:  { icon: 'pi-minus-circle',  color: '#94a3b8', soft: '#f8fafc', label: 'Ignoré' },
};

export function stepStatusVisual(s: string | null | undefined): StatusVisual {
  return (s && STEP_STATUS_VISUAL[s.toUpperCase()]) || STEP_STATUS_VISUAL['PENDING'];
}

// ============================================================================
//  4. TYPES DE CHANTIERS — couleur + dégradé + icône
// ============================================================================
// Single source of truth is chantier-visual.ts (extended with the hydraulic
// domain types ADDUCTION/IRRIGATION/VANNE/FORAGE/POMPAGE/ASSAINISSEMENT/
// REHABILITATION/BASSIN). Re-exported here so legacy imports keep working.
export {
  CHANTIER_TYPE_VISUAL, typeVisual,
  CHANTIER_STATUS_VISUAL, statusVisual
} from './chantier-visual';

// ============================================================================
//  5. TYPES DE PROCESSUS — par catégorie métier
// ============================================================================

export interface ProcessTypeVisual {
  icon: string;
  color: string;
  soft: string;
  gradient: string;
  label: string;
  description: string;
}

export const PROCESS_TYPE_VISUAL: Record<string, ProcessTypeVisual> = {
  PAYMENT:    { icon: 'pi-wallet', color: '#0891b2', soft: '#ecfeff', gradient: 'linear-gradient(135deg, #22d3ee 0%, #0891b2 100%)', label: 'Paiement', description: 'Approbation et décaissement' },
  MATERIAL:   { icon: 'pi-shopping-cart', color: '#b45309', soft: '#fffbeb', gradient: 'linear-gradient(135deg, #fbbf24 0%, #b45309 100%)', label: 'Achat matière', description: 'Besoin stock → BC → BL → facture' },
  SUBCONTRACT:{ icon: 'pi-users', color: '#7c3aed', soft: '#f5f3ff', gradient: 'linear-gradient(135deg, #a78bfa 0%, #7c3aed 100%)', label: 'Sous-traitance', description: 'Commande et réception sous-traitant' },
  SITE:       { icon: 'pi-briefcase', color: '#0d9488', soft: '#f0fdfa', gradient: 'linear-gradient(135deg, #2dd4bf 0%, #0d9488 100%)', label: 'Réception chantier', description: 'PV de réception d\'ouvrage' },
  HSE:        { icon: 'pi-shield', color: '#ea580c', soft: '#fff7ed', gradient: 'linear-gradient(135deg, #fb923c 0%, #ea580c 100%)', label: 'HSE', description: 'Inspection sécurité / environnement' },
  DOC:        { icon: 'pi-file-edit', color: '#2563eb', soft: '#eff6ff', gradient: 'linear-gradient(135deg, #60a5fa 0%, #2563eb 100%)', label: 'Documentaire', description: 'Cycle de vie documentaire' },
  OTHER:      { icon: 'pi-sitemap', color: '#64748b', soft: '#f8fafc', gradient: 'linear-gradient(135deg, #94a3b8 0%, #64748b 100%)', label: 'Autre', description: 'Processus personnalisé' }
};

export function processTypeVisual(templateKey: string | null | undefined): ProcessTypeVisual {
  if (!templateKey) return PROCESS_TYPE_VISUAL['OTHER'];
  const prefix = templateKey.split('_')[0].toUpperCase();
  return PROCESS_TYPE_VISUAL[prefix] || PROCESS_TYPE_VISUAL['OTHER'];
}

// ============================================================================
//  6. TYPES DE DOCUMENTS — icône métier reconnaissable
// ============================================================================

export interface DocTypeVisual {
  icon: string;
  color: string;
  soft: string;
}

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

export const DOC_STATUS_VISUAL: Record<string, StatusVisual> = {
  DRAFT:      { icon: 'pi-pencil',       color: '#64748b', soft: '#f1f5f9',  label: 'Brouillon' },
  IN_REVIEW:  { icon: 'pi-clock',        color: '#ea580c', soft: '#fff7ed',  label: 'En revue' },
  APPROVED:   { icon: 'pi-check-circle', color: '#16a34a', soft: '#f0fdf4',  label: 'Approuvé' },
  REJECTED:   { icon: 'pi-times-circle', color: '#dc2626', soft: '#fef2f2',  label: 'Rejeté' },
  SUPERSEDED: { icon: 'pi-history',      color: '#94a3b8', soft: '#f8fafc',  label: 'Obsolète' },
  ARCHIVED:   { icon: 'pi-archive',      color: '#64748b', soft: '#f1f5f9',  label: 'Archivé' }
};

export function docStatusVisual(s: string | null | undefined): StatusVisual {
  return (s && DOC_STATUS_VISUAL[s]) || DOC_STATUS_VISUAL['DRAFT'];
}

// ============================================================================
//  7. ACTIONS — couleur sémantique pour boutons et CTA
// ============================================================================

export type ActionTone = 'primary' | 'success' | 'warn' | 'danger' | 'info' | 'muted';

export interface ActionToneVisual {
  color: string;
  soft: string;
  icon: string;
  label: string;
}

export const ACTION_TONE_VISUAL: Record<ActionTone, ActionToneVisual> = {
  primary: { color: '#0ea5e9', soft: '#f0f9ff', icon: 'pi-bolt',       label: 'Principal' },
  success: { color: '#16a34a', soft: '#f0fdf4', icon: 'pi-check',      label: 'Confirmer' },
  warn:    { color: '#f59e0b', soft: '#fffbeb', icon: 'pi-clock',      label: 'En attente' },
  danger:  { color: '#dc2626', soft: '#fef2f2', icon: 'pi-times',      label: 'Refuser' },
  info:    { color: '#2563eb', soft: '#eff6ff', icon: 'pi-info-circle', label: 'Info' },
  muted:   { color: '#64748b', soft: '#f8fafc', icon: 'pi-minus',      label: 'Neutre' },
};

// ============================================================================
//  8. HEURISTIQUES — déduction d'icône pour dossiers (BTP)
// ============================================================================

/**
 * Heuristique métier BTP/hydraulique :
 *   "Plans" → carte, "Photos" → appareil, "PV" → vérifié, etc.
 */
export function folderIcon(name: string | null | undefined): string {
  const n = (name ?? '').toLowerCase();
  if (/(plan|dessin|drawing|dwg)/.test(n)) return 'pi-map';
  if (/(photo|image|cran)/.test(n)) return 'pi-camera';
  if (/\bpv\b|réception|reception|épreuve|epreuve|qualité|qualite/.test(n)) return 'pi-verified';
  if (/(facture|situation|paiement|finance|comptabilit)/.test(n)) return 'pi-euro';
  if (/(marché|marche|contract|contrat)/.test(n)) return 'pi-briefcase';
  if (/(hse|sécurité|securite|hygi|environnement)/.test(n)) return 'pi-shield';
  if (/(rapport|compte rendu|cr\b)/.test(n)) return 'pi-file-edit';
  if (/(correspondance|courrier|lettre|mail|administrat)/.test(n)) return 'pi-envelope';
  if (/(étude|etude|note de calcul|synthèse|technique)/.test(n)) return 'pi-book';
  if (/(livraison|approvisionnement|achat)/.test(n)) return 'pi-truck';
  if (/(exécution|execution|travaux|chantier)/.test(n)) return 'pi-wrench';
  return 'pi-folder';
}

// ============================================================================
//  9. AVATAR — dégradé déterministe depuis un UUID
// ============================================================================

/**
 * Génère un dégradé de couleur déterministe depuis un UUID.
 * Utile pour les avatars d'utilisateurs sans photo de profil.
 */
export function avatarGradient(seed: string | null | undefined): string {
  if (!seed) return 'linear-gradient(135deg, #94a3b8, #64748b)';
  const colors = [
    ['#0ea5e9', '#0284c7'],
    ['#8b5cf6', '#6d28d9'],
    ['#ec4899', '#be185d'],
    ['#f59e0b', '#b45309'],
    ['#10b981', '#047857'],
    ['#06b6d4', '#0e7490'],
    ['#ef4444', '#b91c1c'],
    ['#84cc16', '#4d7c0f'],
  ];
  // Hash simple du seed → index 0..7
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) & 0x7fffffff;
  }
  const [a, b] = colors[hash % colors.length];
  return `linear-gradient(135deg, ${a} 0%, ${b} 100%)`;
}

/** Initiales d'un nom (max 2 lettres). */
export function initials(name: string | null | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}
