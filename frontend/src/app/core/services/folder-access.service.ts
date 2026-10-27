/** Angular injectable mapped to the folder ACL REST endpoints. */

import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, map } from 'rxjs';

import {
  FolderAccess,
  FolderAccessGrant,
  mapFolderAccess
} from '@app/core/models/folder.model';

@Injectable({ providedIn: 'root' })
export class FolderAccessService {
  private readonly http = inject(HttpClient);

  /** GET /api/projects/{projectId}/folders/{folderId}/access */
  list(projectId: string, folderId: string): Observable<FolderAccess[]> {
    return this.http
      .get<any[]>(
        `/api/projects/${projectId}/folders/${folderId}/access`
      )
      .pipe(map(list => (list ?? []).map(mapFolderAccess)));
  }

  /**
   * POST /api/projects/{projectId}/folders/{folderId}/access
   * Upserts a grant. Exactly one target must be supplied: roleCode XOR userId.
   */
  grant(
    projectId: string,
    folderId: string,
    body: FolderAccessGrant
  ): Observable<FolderAccess> {
    return this.http
      .post<any>(
        `/api/projects/${projectId}/folders/${folderId}/access`,
        body
      )
      .pipe(map(mapFolderAccess));
  }

  /**
   * DELETE /api/projects/{projectId}/folders/{folderId}/access/{accessId}
   * 204 on success.
   */
  revoke(projectId: string, folderId: string, accessId: string): Observable<void> {
    return this.http.delete<void>(
      `/api/projects/${projectId}/folders/${folderId}/access/${accessId}`
    );
  }
}
