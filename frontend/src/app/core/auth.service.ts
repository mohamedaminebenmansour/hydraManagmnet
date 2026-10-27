import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, map, of, tap } from 'rxjs';

export interface Me {
  // NOTE (optional backend gap): /api/auth/me currently returns no id, so
  // isAssignee() in tickets won't match until the backend includes it.
  id?: string;
  email: string;
  fullName: string;
  isOwner: boolean;
  permissions: string[];
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly meSignal = signal<Me | null>(null);
  readonly me = this.meSignal.asReadonly();
  // Backend sends 'PERM_TICKET_READ' (Spring authority format); we normalize once, here:
  readonly permissions = computed(() =>
    new Set((this.meSignal()?.permissions ?? []).map(p => p.replace('PERM_', ''))));

  constructor(private http: HttpClient, private router: Router) {}

  can(perm: string): boolean {
    return !perm || this.permissions().has(perm);
  }

  check(): Observable<boolean> {
    return this.http.get<Me>('/api/auth/me').pipe(
      tap(me => this.meSignal.set(me)),
      map(() => true),
      catchError(() => of(false))
    );
  }

  login(email: string, password: string): Observable<Me> {
    return this.http.post<Me>('/api/auth/login', { email, password })
      .pipe(tap(me => this.meSignal.set(me)));
  }

  logout(): void {
    this.http.post('/api/auth/logout', {}).subscribe({
      complete: () => { this.meSignal.set(null); this.router.navigate(['/login']); }
    });
  }
}