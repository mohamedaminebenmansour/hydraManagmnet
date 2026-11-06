import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

// ── Types matching GET /api/controle/data EXACTLY ────────────────────────────

export interface CtlMeta {
  source: string;
  generated: string;       // yyyy-MM-dd
  lines: number;
  stLines: number;
}

export interface CtlAccount {
  id: string;
  name: string;
  kind: 'banque' | 'especes' | 'chantier' | 'partenaire';
  owner: string;
  project: string;
  note: string | null;
}

export interface CtlStAccount {
  id: string;
  sheet: string;
  person: string;
  proj: string;
  supplier: boolean;
}

export interface CtlLine {
  id: string;
  sheet: string;
  row: number;
  date: string | null;     // yyyy-MM-dd
  acct: string;
  proj: string;
  e: number;               // entrée
  d: number;               // sortie
  mode: string;
  bon: string;
  fct: string;
  cat: string;
  desig: string;
  party: string;
  poste: string;
  kind: 'entree' | 'sortie' | 'transfert' | 'partenaire';
  obs: string;
  flags: string[];
  stLink: string | null;
}

export interface CtlStLine {
  id: string;
  st: string;
  sheet: string;
  row: number;
  date: string | null;
  travaux: number;
  paye: number;
  mode: string;
  desig: string;
  link: string | null;
  flags: string[];
}

export type CtlJustifStatut = 'none' | 'justifie' | 'partiel' | 'non-justifie' | 'conteste';

export interface CtlJustif {
  lineKey: string;
  statut: CtlJustifStatut;
  docType: string | null;
  reference: string | null;
  docDate: string | null;
  docAmount: number | null;
  note: string | null;
  version: number;
  updatedBy: string | null;    // user id
  updatedAt: string | null;    // ISO timestamp
}

export type CtlValidStatut = 'none' | 'valide' | 'a-revoir' | 'conteste';

export interface CtlValid {
  key: string;
  statut: CtlValidStatut;
  agreedBalance: number | null;
  note: string | null;
  version: number;
  updatedBy: string | null;
  updatedByName: string | null;
  updatedAt: string | null;
}

export interface CtlImport {
  id: string;
  sourceName: string;
  generatedAt: string | null;
  importedAt: string;       // ISO timestamp
  importedBy: string | null;
  importerName: string | null;
  lineCount: number;
  stLineCount: number;
}

export interface CtlData {
  meta: CtlMeta | null;
  accounts: CtlAccount[];
  stAccounts: CtlStAccount[];
  lines: CtlLine[];
  stLines: CtlStLine[];
  justif: CtlJustif[];
  valid: CtlValid[];
  imported: CtlImport | null;
}

// ── Request / response bodies ────────────────────────────────────────────────

export interface ImportResult {
  importId: string;
  lines: number;
  stLines: number;
  accounts: number;
  orphanJustifications: number;
}

export interface JustifBody {
  statut: CtlJustifStatut;
  docType: string | null;
  reference: string | null;
  docDate: string | null;
  docAmount: number | null;
  note: string | null;
  version: number;
}

export interface ValidBody {
  statut: CtlValidStatut;
  agreedBalance: number | null;
  note: string | null;
  version: number;
}

// ── Service ──────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class ControleService {
  private http = inject(HttpClient);

  /** GET /api/controle/data — all accounts, lines, st lines, justifications, validations. */
  getData(): Observable<CtlData> {
    return this.http.get<CtlData>('/api/controle/data');
  }

  /** POST /api/controle/import — multipart upload of the convert.py JSON. */
  importFile(file: File): Observable<ImportResult> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    return this.http.post<ImportResult>('/api/controle/import', fd);
  }

  /** GET /api/controle/imports — last 10 imports of my org. */
  imports(): Observable<CtlImport[]> {
    return this.http.get<CtlImport[]>('/api/controle/imports');
  }

  /** PUT /api/controle/justifications/{lineKey} — create or update a justification. */
  putJustification(lineKey: string, body: JustifBody): Observable<CtlJustif> {
    return this.http.put<CtlJustif>(`/api/controle/justifications/${encodeURIComponent(lineKey)}`, body);
  }

  /** PUT /api/controle/validations/{key} — create or update a validation. */
  putValidation(key: string, body: ValidBody): Observable<CtlValid> {
    return this.http.put<CtlValid>(`/api/controle/validations/${encodeURIComponent(key)}`, body);
  }
}
