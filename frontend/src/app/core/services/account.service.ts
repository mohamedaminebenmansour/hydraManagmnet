import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Account service (U3): calls the /api/auth/* and /api/me/* endpoints
 * that are not part of the main AuthService (login/logout/me).
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private http = inject(HttpClient);

  /** GET /api/auth/password-policy (public) → {minLength, maxLength, rules}. */
  passwordPolicy(): Observable<{ minLength: number; maxLength: number; rules: string[] }> {
    return this.http.get<{ minLength: number; maxLength: number; rules: string[] }>('/api/auth/password-policy');
  }

  /** GET /api/auth/mail-status (public) → {enabled}. */
  mailStatus(): Observable<{ enabled: boolean }> {
    return this.http.get<{ enabled: boolean }>('/api/auth/mail-status');
  }

  /** POST /api/auth/forgot-password {email}. */
  forgotPassword(email: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>('/api/auth/forgot-password', { email });
  }

  /** GET /api/auth/reset-password/check?token=... → {valid, purpose, firstName}. */
  checkToken(token: string): Observable<{ valid: boolean; purpose?: string; firstName?: string }> {
    return this.http.get<{ valid: boolean; purpose?: string; firstName?: string }>(
      '/api/auth/reset-password/check', { params: { token } });
  }

  /** POST /api/auth/reset-password {token, newPassword}. */
  resetPassword(token: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/auth/reset-password', { token, newPassword });
  }

  /** POST /api/auth/change-password {oldPassword, newPassword}. */
  changePassword(oldPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/auth/change-password', { oldPassword, newPassword });
  }

  /** GET /api/me/profile. */
  myProfile(): Observable<MyProfile> {
    return this.http.get<MyProfile>('/api/me/profile');
  }

  /** PUT /api/me/profile {fullName, phone, jobTitle}. */
  updateProfile(fullName: string, phone: string, jobTitle: string): Observable<MyProfile> {
    return this.http.put<MyProfile>('/api/me/profile', { fullName, phone, jobTitle });
  }

  /** PUT /api/me/absence {absentUntil}. */
  setAbsence(absentUntil: string | null): Observable<void> {
    return this.http.put<void>('/api/me/absence', { absentUntil });
  }

  /** POST /api/me/sessions/revoke-others. */
  revokeOthers(): Observable<void> {
    return this.http.post<void>('/api/me/sessions/revoke-others', {});
  }
}

export interface MyProfile {
  fullName: string;
  email: string;
  phone: string;
  jobTitle: string;
  lastLoginAt: string;
  passwordChangedAt: string;
  roles: { code: string; label: string }[];
  projects: { id: string; name: string; projectRole: string }[];
  absentUntil: string;
}
