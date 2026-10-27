import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const permGuard = (perm: string): CanActivateFn => () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.can(perm) ? true : router.createUrlTree(['/']);
};

/** Owner-only access (process builder, sensitive admin areas). */
export const ownerGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me()?.isOwner ? true : router.createUrlTree(['/']);
};