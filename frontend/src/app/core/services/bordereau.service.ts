import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  BordereauVersion, BordereauLine, ImportPreview, BordereauDiff,
  mapVersion, mapLine, mapImportPreview, mapDiff
} from '@app/core/models/bordereau.model';

@Injectable({ providedIn: 'root' })
export class BordereauService {
  private http = inject(HttpClient);

  listVersions(projectId: string): Observable<BordereauVersion[]> {
    return this.http.get<any[]>(`/api/projects/${projectId}/bordereau/versions`).pipe(
      map(list => (list ?? []).map(mapVersion)));
  }

  createVersion(projectId: string, body: { label: string; reason?: string }): Observable<BordereauVersion> {
    return this.http.post<any>(`/api/projects/${projectId}/bordereau/versions`, body).pipe(map(mapVersion));
  }

  activateVersion(projectId: string, versionId: string): Observable<BordereauVersion> {
    return this.http.post<any>(`/api/projects/${projectId}/bordereau/versions/${versionId}/activate`, {}).pipe(map(mapVersion));
  }

  listLines(projectId: string, versionId: string): Observable<BordereauLine[]> {
    return this.http.get<any[]>(`/api/projects/${projectId}/bordereau/versions/${versionId}/lines`).pipe(
      map(list => (list ?? []).map(mapLine)));
  }

  updateLine(projectId: string, versionId: string, lineId: string, body: Partial<{
    articleNo: string; designation: string; unitRaw: string; unit: string;
    quantity: number; unitPrice: number; ouvrageId: string | null;
  }>): Observable<BordereauLine> {
    return this.http.put<any>(`/api/projects/${projectId}/bordereau/versions/${versionId}/lines/${lineId}`, body).pipe(map(mapLine));
  }

  /** Set one ouvrage (or none) on several lines of a DRAFT version, in one call. */
  assignOuvrage(projectId: string, versionId: string, lineIds: string[], ouvrageId: string | null): Observable<BordereauLine[]> {
    return this.http.put<any[]>(`/api/projects/${projectId}/bordereau/versions/${versionId}/lines/ouvrage`,
      { lineIds, ouvrageId }).pipe(map(list => (list ?? []).map(mapLine)));
  }

  importExcel(projectId: string, file: File): Observable<ImportPreview> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<any>(`/api/projects/${projectId}/bordereau/import`, form).pipe(map(mapImportPreview));
  }

  compareVersions(projectId: string, oldVersionId: string, newVersionId: string): Observable<BordereauDiff[]> {
    return this.http.get<any[]>(`/api/projects/${projectId}/bordereau/compare`, {
      params: { old: oldVersionId, neu: newVersionId }
    }).pipe(map(list => (list ?? []).map(mapDiff)));
  }
}
