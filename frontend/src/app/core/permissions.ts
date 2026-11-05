export const PERM = {
  TICKET_READ: 'TICKET_READ',
  TICKET_WRITE: 'TICKET_WRITE',
  TICKET_ASSIGN: 'TICKET_ASSIGN',
  USER_CREATE: 'USER_CREATE',
  USER_UPDATE: 'USER_UPDATE',
  USER_DEACTIVATE: 'USER_DEACTIVATE',
  ROLE_ASSIGN: 'ROLE_ASSIGN',
  ROLE_MANAGE: 'ROLE_MANAGE',
  PROJECT_READ: 'PROJECT_READ',
  PROJECT_WRITE: 'PROJECT_WRITE',
  PROJECT_ASSIGN: 'PROJECT_ASSIGN',
  PROJECT_MANAGE: 'PROJECT_MANAGE',
  PROJECT_ADMIN: 'PROJECT_ADMIN',
  DOCUMENT_READ: 'DOCUMENT_READ',
  DOCUMENT_UPLOAD: 'DOCUMENT_UPLOAD',
  DOCUMENT_DOWNLOAD: 'DOCUMENT_DOWNLOAD',
  DOCUMENT_DELETE: 'DOCUMENT_DELETE',
  DOCUMENT_APPROVE: 'DOCUMENT_APPROVE',
  AUDIT_VIEW: 'AUDIT_VIEW',
  FOLDER_MANAGE: 'FOLDER_MANAGE',
  FOLDER_GRANT: 'FOLDER_GRANT',
  // Trésorerie (backend: V22__cash_permissions.sql)
  CASH_READ: 'CASH_READ',
  CASH_DRAFT: 'CASH_DRAFT',
  CASH_SUBMIT: 'CASH_SUBMIT',
  CASH_POST: 'CASH_POST',
  CASH_VOID: 'CASH_VOID',
  PARTY_MANAGE: 'PARTY_MANAGE',
  // Stock & matériel (backend: V30__stock_virtual_v1.sql)
  STOCK_VIEW: 'STOCK_VIEW',
  STOCK_MOVE: 'STOCK_MOVE',
  STOCK_ADMIN: 'STOCK_ADMIN',
  ORG_SETTINGS: 'ORG_SETTINGS'
} as const;

export const PERM_LABELS: Record<string, string> = {
  USER_CREATE: 'Ajouter des utilisateurs',
  USER_UPDATE: 'Modifier des utilisateurs',
  USER_DEACTIVATE: 'Désactiver des utilisateurs',
  ROLE_ASSIGN: 'Attribuer les rôles',
  ROLE_MANAGE: 'Créer des rôles',
  PROJECT_READ: 'Voir les projets',
  PROJECT_WRITE: 'Créer et modifier les projets',
  PROJECT_ASSIGN: 'Affecter des utilisateurs aux projets',
  PROJECT_MANAGE: 'Gérer les projets',
  PROJECT_ADMIN: 'Administrer les projets (membres + ACL)',
  DOCUMENT_READ: 'Voir les documents',
  DOCUMENT_UPLOAD: 'Ajouter des documents',
  DOCUMENT_DOWNLOAD: 'Télécharger les documents',
  DOCUMENT_DELETE: 'Supprimer les documents',
  DOCUMENT_APPROVE: 'Approuver les documents',
  AUDIT_VIEW: "Voir le rapport d'activité",
  TICKET_READ: 'Voir les tickets',
  TICKET_WRITE: 'Créer et fermer les tickets',
  TICKET_ASSIGN: 'Assigner les tickets',
  FOLDER_MANAGE: 'Configurer les accès aux dossiers',
  FOLDER_GRANT: "Attribuer/retirer l'accès à un dossier",
  CASH_READ: 'Consulter la trésorerie',
  CASH_DRAFT: 'Créer et modifier les brouillons',
  CASH_SUBMIT: 'Soumettre pour approbation',
  CASH_POST: 'Comptabiliser les opérations',
  CASH_VOID: 'Annuler les opérations',
  PARTY_MANAGE: 'Gérer les sous-traitants',
  STOCK_VIEW: 'Voir le stock',
  STOCK_MOVE: 'Saisir les mouvements de stock',
  STOCK_ADMIN: 'Administrer les articles et emplacements',
  ORG_SETTINGS: 'Profil de l\'entreprise',
};

/**
 * U5 — one French sentence per permission (full sentence, not just a label).
 * Used in the role detail page's permission cards.
 */
export const PERM_SENTENCES: Record<string, string> = {
  TICKET_READ: 'Peut voir les signalements',
  TICKET_WRITE: 'Peut créer et fermer les signalements',
  TICKET_ASSIGN: 'Peut assigner les signalements',
  USER_CREATE: 'Peut ajouter des utilisateurs',
  USER_UPDATE: 'Peut modifier les utilisateurs',
  USER_DEACTIVATE: 'Peut désactiver des utilisateurs',
  ROLE_ASSIGN: 'Peut attribuer les rôles',
  ROLE_MANAGE: 'Peut créer et gérer les rôles',
  PROJECT_READ: 'Peut voir les chantiers',
  PROJECT_WRITE: 'Peut créer et modifier les chantiers',
  PROJECT_ASSIGN: 'Peut affecter des utilisateurs aux chantiers',
  PROJECT_MANAGE: 'Peut gérer les chantiers',
  PROJECT_ADMIN: 'Peut administrer les chantiers (membres + dossiers)',
  DOCUMENT_READ: 'Peut voir les documents',
  DOCUMENT_UPLOAD: 'Peut ajouter des documents',
  DOCUMENT_DOWNLOAD: 'Peut télécharger les documents',
  DOCUMENT_DELETE: 'Peut supprimer les documents',
  DOCUMENT_APPROVE: 'Peut approuver les documents',
  AUDIT_VIEW: "Peut voir le rapport d'activité",
  FOLDER_MANAGE: 'Peut configurer les accès aux dossiers',
  FOLDER_GRANT: "Peut attribuer ou retirer l'accès à un dossier",
  CASH_READ: 'Peut consulter la trésorerie',
  CASH_DRAFT: 'Peut créer et modifier les brouillons',
  CASH_SUBMIT: 'Peut soumettre pour approbation',
  CASH_POST: 'Peut comptabiliser les opérations',
  CASH_VOID: 'Peut annuler les opérations',
  PARTY_MANAGE: 'Peut gérer les sous-traitants',
  STOCK_VIEW: 'Peut voir le stock',
  STOCK_MOVE: 'Peut saisir les mouvements de stock',
  STOCK_ADMIN: 'Peut administrer les articles et emplacements',
  ORG_SETTINGS: 'Peut modifier le profil de l\'entreprise et les groupements',
};

export const PERM_GROUPS: { key: string; icon: string; title: string; ids: string[] }[] = [
  { key: 'tickets',    icon: 'pi pi-exclamation-triangle', title: 'Signalements',
    ids: ['TICKET_READ','TICKET_WRITE','TICKET_ASSIGN'] },
  { key: 'projects',   icon: 'pi pi-briefcase', title: 'Chantiers',
    ids: ['PROJECT_READ','PROJECT_WRITE','PROJECT_ASSIGN','PROJECT_MANAGE','PROJECT_ADMIN'] },
  { key: 'documents',  icon: 'pi pi-file', title: 'Documents',
    ids: ['DOCUMENT_READ','DOCUMENT_UPLOAD','DOCUMENT_DOWNLOAD','DOCUMENT_DELETE','DOCUMENT_APPROVE','FOLDER_MANAGE','FOLDER_GRANT'] },
  { key: 'cash',       icon: 'pi pi-wallet', title: 'Trésorerie',
    ids: ['CASH_READ','CASH_DRAFT','CASH_SUBMIT','CASH_POST','CASH_VOID','PARTY_MANAGE'] },
  { key: 'stock',      icon: 'pi pi-box', title: 'Stock et achats',
    ids: ['STOCK_VIEW','STOCK_MOVE','STOCK_ADMIN'] },
  { key: 'admin',      icon: 'pi pi-shield', title: 'Administration',
    ids: ['USER_CREATE','USER_UPDATE','USER_DEACTIVATE','ROLE_ASSIGN','ROLE_MANAGE','AUDIT_VIEW','ORG_SETTINGS'] },
];

/**
 * Orphan codes reported: none after the fix. Every permission code in the DB
 * (30 codes) now belongs to exactly one PERM_GROUPS group.
 *
 * Codes that were missing from the PERM constant (now added):
 *   - PROJECT_ADMIN (V14)
 *   - FOLDER_GRANT (V14)
 *   - DOCUMENT_APPROVE (V11)
 *
 * Code that was in PERM but not in any group (now in 'documents'):
 *   - FOLDER_MANAGE
 */
