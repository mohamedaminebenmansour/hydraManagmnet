import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.me()) {
    // U3: forced change — redirect to /premiere-connexion for any shell route
    if (auth.me()!.mustChangePassword) {
      return router.createUrlTree(['/premiere-connexion']);
    }
    return true;
  }
  return auth.check().pipe(map(ok => {
    if (!ok) return router.createUrlTree(['/login']);
    if (auth.me()?.mustChangePassword) return router.createUrlTree(['/premiere-connexion']);
    return true;
  }));
};