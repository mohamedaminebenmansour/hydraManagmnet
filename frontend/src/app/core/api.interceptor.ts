import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';

export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const msgs = inject(MessageService);
  return next(req).pipe(
    catchError(err => {
      if (err.status === 401) router.navigate(['/login']);
      // Unexpected server error (INTERNAL_ERROR): show its reference so the user can send it to support.
      const reference = typeof err?.error === 'object' && err.error ? err.error.reference : null;
      if (typeof reference === 'string' && reference) {
        msgs.add({
          severity: 'error', summary: 'Erreur technique', life: 10000,
          detail: `Référence : ${reference} — communiquez-la au support.`,
        });
      }
      return throwError(() => err);
    })
  );
};
