import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { Folder, mapFolder } from '@app/core/models/folder.model';

@Injectable({ providedIn: 'root' })
export class FolderService {
  private http = inject(HttpClient);

  /** GET /api/projects/{projectId}/folders — auto-created by backend. */
  listByProject(projectId: string): Observable<Folder[]> {
    return this.http.get<any[]>(`/api/projects/${projectId}/folders`).pipe(
      map(list => (list ?? []).map(mapFolder))
    );
  }

  /**
   * No GET /api/folders/{id} on the backend — resolve one folder by
   * listing its project then picking the matching id.
   */
  get(projectId: string, id: string): Observable<Folder> {
    return this.listByProject(projectId).pipe(
      map(list => {
        const found = (list ?? []).find(f => f.id === id);
        if (!found) { throw new Error('FOLDER_NOT_FOUND'); }
        return found;
      })
    );
  }
}
