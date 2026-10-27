/**
 * ============================================================================
 *  LANGAGE VISUEL PROCESS — source unique de vérité (icônes + couleurs)
 * ============================================================================
 *  Cohérent avec chantier-visual.ts : l'utilisateur COMPREND SANS LIRE.
 *  - Chaque type de processus métier = 1 couleur + 1 icône (paiement, achat,
 *    sous-traitance, réception, HSE, etc.) — déduite de la clé template.
 *  - Chaque rôle métier = 1 couleur + 1 icône (avatar assigné).
 *  - Chaque statut d'étape = 1 feu tricolore.
 *  - Une étape = une carte connectée à la suivante par une flèche.
 * ============================================================================
 */

export interface ProcessTypeVisual {
  icon: string;       // classe PrimeIcons
  color: string;      // couleur principale (bandes, icônes, chips)
  soft: string;       // fond très clair (disques, tags doux)
  gradient: string;   // dégradé pour les en-têtes / héros
  label: string;       // libellé FR court
  description: string; // description FR courte
}

/**
 * Identité visuelle par type de processus métier.
 * La clé est le préfixe du templateKey (avant le _).
 */
export const PROCESS_TYPE_VISUAL: Record<string, ProcessTypeVisual> = {
  PAYMENT: {
    icon: 'pi-wallet',
    color: '#0891b2',
    soft: '#ecfeff',
    gradient: 'linear-gradient(135deg, #22d3ee 0%, #0891b2 100%)',
    label: 'Paiement',
    description: 'Approbation et décaissement (sous-traitant, fournisseur, etc.)'
  },
  MATERIAL: {
    icon: 'pi-shopping-cart',
    color: '#b45309',
    soft: '#fffbeb',
    gradient: 'linear-gradient(135deg, #fbbf24 0%, #b45309 100%)',
    label: 'Achat matière',
    description: 'Besoin stock → BC → BL → facture'
  },
  SUBCONTRACT: {
    icon: 'pi-users',
    color: '#7c3aed',
    soft: '#f5f3ff',
    gradient: 'linear-gradient(135deg, #a78bfa 0%, #7c3aed 100%)',
    label: 'Sous-traitance',
    description: 'Commande et réception d\'un lot sous-traitant'
  },
  SITE: {
    icon: 'pi-briefcase',
    color: '#0d9488',
    soft: '#f0fdfa',
    gradient: 'linear-gradient(135deg, #2dd4bf 0%, #0d9488 100%)',
    label: 'Réception chantier',
    description: 'Réception d\'ouvrage / PV de réception'
  },
  HSE: {
    icon: 'pi-shield',
    color: '#ea580c',
    soft: '#fff7ed',
    gradient: 'linear-gradient(135deg, #fb923c 0%, #ea580c 100%)',
    label: 'HSE',
    description: 'Inspection sécurité / environnement'
  },
  DOC: {
    icon: 'pi-file-edit',
    color: '#2563eb',
    soft: '#eff6ff',
    gradient: 'linear-gradient(135deg, #60a5fa 0%, #2563eb 100%)',
    label: 'Documentaire',
    description: 'Cycle de vie documentaire (revue / approbation)'
  },
  OTHER: {
    icon: 'pi-sitemap',
    color: '#64748b',
    soft: '#f8fafc',
    gradient: 'linear-gradient(135deg, #94a3b8 0%, #64748b 100%)',
    label: 'Autre',
    description: 'Processus métier personnalisé'
  }
};

/**
 * Déduit la catégorie visuelle à partir d'un templateKey.
 * PAYMENT_APPROVAL → PAYMENT ; MATERIAL_PURCHASE → MATERIAL ; etc.
 */
export function processTypeVisual(templateKey: string | null | undefined): ProcessTypeVisual {
  if (!templateKey) return PROCESS_TYPE_VISUAL['OTHER'];
  const prefix = templateKey.split('_')[0].toUpperCase();
  return PROCESS_TYPE_VISUAL[prefix] || PROCESS_TYPE_VISUAL['OTHER'];
}

export interface RoleVisual {
  icon: string;
  color: string;   // couleur de l'avatar / du chip
  soft: string;    // fond doux
  label: string;   // libellé FR
}

/**
 * Icône + couleur par rôle métier — dérive du système de rôles Hydra.
 * Calé sur l'identité visuelle des chantiers (financier bleu, HSE orange,
 * qualité vert, ingénieur indigo, etc.).
 */
export const ROLE_VISUAL: Record<string, RoleVisual> = {
  OWNER:        { icon: 'pi-crown',      color: '#b45309', soft: '#fffbeb', label: 'Gérant' },
  DIRECTION:    { icon: 'pi-briefcase',  color: '#7c3aed', soft: '#f5f3ff', label: 'Direction' },
  CHEF_PROJET:  { icon: 'pi-briefcase',  color: '#0d9488', soft: '#f0fdfa', label: 'Chef de projet' },
  CONDUCTEUR:   { icon: 'pi-compass',    color: '#0d9488', soft: '#f0fdfa', label: 'Conducteur' },
  INGENIEUR:    { icon: 'pi-calculator', color: '#4f46e5', soft: '#eef2ff', label: 'Ingénieur' },
  BUREAU:       { icon: 'pi-pencil',     color: '#4f46e5', soft: '#eef2ff', label: 'Bureau d\'études' },
  QA:           { icon: 'pi-verified',   color: '#16a34a', soft: '#f0fdf4', label: 'Qualité' },
  DOC_CTRL:     { icon: 'pi-file-edit',  color: '#2563eb', soft: '#eff6ff', label: 'Contrôle doc.' },
  ACHAT:        { icon: 'pi-shopping-cart', color: '#b45309', soft: '#fffbeb', label: 'Achat' },
  FINANCE:      { icon: 'pi-wallet',     color: '#0891b2', soft: '#ecfeff', label: 'Finance' },
  CHEF_CHANTIER:{ icon: 'pi-hammer',     color: '#dc2626', soft: '#fef2f2', label: 'Chef chantier' },
  HSE:          { icon: 'pi-shield',     color: '#ea580c', soft: '#fff7ed', label: 'HSE' },
  SOUS_TRAITANT:{ icon: 'pi-truck',      color: '#ca8a04', soft: '#fefce8', label: 'Sous-traitant' }
};

export function roleVisual(role: string | null | undefined): RoleVisual {
  if (!role) return { icon: 'pi-user', color: '#64748b', soft: '#f8fafc', label: role || '—' };
  const v = ROLE_VISUAL[role.toUpperCase()];
  return v || { icon: 'pi-user', color: '#64748b', soft: '#f8fafc', label: role };
}

/** Tous les rôles actifs (pour les selects du builder). */
export const ROLE_OPTIONS = Object.keys(ROLE_VISUAL).map(code => ({
  code,
  label: ROLE_VISUAL[code].label,
  icon: ROLE_VISUAL[code].icon,
  color: ROLE_VISUAL[code].color
}));

export interface StepStatusVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

export const STEP_STATUS_VISUAL: Record<string, StepStatusVisual> = {
  PENDING:  { icon: 'pi-clock',         color: '#ea580c', soft: '#fff7ed', label: 'En attente' },
  DONE:     { icon: 'pi-check-circle',  color: '#16a34a', soft: '#f0fdf4', label: 'Terminé' },
  REJECTED: { icon: 'pi-times-circle',  color: '#dc2626', soft: '#fef2f2', label: 'Refusé' },
  SKIPPED:  { icon: 'pi-minus-circle',  color: '#94a3b8', soft: '#f8fafc', label: 'Ignoré' },
  RUNNING:  { icon: 'pi-play',          color: '#0891b2', soft: '#ecfeff', label: 'En cours' },
  COMPLETED:{ icon: 'pi-check-circle',  color: '#16a34a', soft: '#f0fdf4', label: 'Clôturé' },
  CANCELLED:{ icon: 'pi-ban',           color: '#64748b', soft: '#f1f5f9', label: 'Annulé' }
};

export function stepStatusVisual(s: string | null | undefined): StepStatusVisual {
  return (s && STEP_STATUS_VISUAL[s.toUpperCase()]) || STEP_STATUS_VISUAL['PENDING'];
}
