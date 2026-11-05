import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface CompanyProfile {
  orgId: string;
  pays: string;
  raisonSociale: string;
  nomCommercial: string | null;
  formeJuridique: string | null;
  matriculeFiscal: string | null;
  mfFormate: string | null;
  rne: string | null;
  capital: string | null;
  assujettiTva: boolean;
  adresse: string | null;
  codePostal: string | null;
  ville: string | null;
  gouvernorat: string | null;
  telephone: string | null;
  email: string | null;
  siteWeb: string | null;
  banque: string | null;
  rib: string | null;
  ribFormate: string | null;
  iban: string | null;
  hasLogo: boolean;
  version: number;
  manquants: string[];
  complet: boolean;
}

export interface UpdateProfile {
  pays: string;
  raisonSociale: string;
  nomCommercial?: string;
  formeJuridique?: string;
  matriculeFiscal?: string;
  rne?: string;
  capital?: string;
  assujettiTva?: boolean;
  adresse?: string;
  codePostal?: string;
  ville?: string;
  gouvernorat?: string;
  telephone?: string;
  email?: string;
  siteWeb?: string;
  banque?: string;
  rib?: string;
  version: number;
}

export interface GroupementMember {
  id?: string;
  ordre?: number;
  isSelf: boolean;
  raisonSociale: string;
  matriculeFiscal?: string;
  pays: string;
  role: string;
  partPct: number;
  prestations?: string;
}

export interface Groupement {
  id: string;
  nom: string;
  type: string;
  projectId?: string;
  referenceMarche?: string;
  notes?: string;
  version: number;
  archived: boolean;
  membres: GroupementMember[];
}

@Injectable({ providedIn: 'root' })
export class CompanyService {
  private http = inject(HttpClient);

  getProfile(): Observable<CompanyProfile> {
    return this.http.get<CompanyProfile>('/api/company');
  }

  updateProfile(body: UpdateProfile): Observable<CompanyProfile> {
    return this.http.put<CompanyProfile>('/api/company', body);
  }

  uploadLogo(file: File): Observable<void> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    return this.http.post<void>('/api/company/logo', fd);
  }

  getLogoUrl(): string { return '/api/company/logo'; }

  deleteLogo(): Observable<void> {
    return this.http.delete<void>('/api/company/logo');
  }

  listGroupements(archives = false): Observable<Groupement[]> {
    return this.http.get<Groupement[]>('/api/company/groupements', { params: { archives } });
  }

  getGroupement(id: string): Observable<Groupement> {
    return this.http.get<Groupement>(`/api/company/groupements/${id}`);
  }

  createGroupement(body: any): Observable<Groupement> {
    return this.http.post<Groupement>('/api/company/groupements', body);
  }

  updateGroupement(id: string, body: any): Observable<Groupement> {
    return this.http.put<Groupement>(`/api/company/groupements/${id}`, body);
  }

  archiveGroupement(id: string): Observable<void> {
    return this.http.post<void>(`/api/company/groupements/${id}/archiver`, {});
  }
}
