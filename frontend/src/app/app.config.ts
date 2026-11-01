import { ApplicationConfig } from '@angular/core';
import { NavigationError, provideRouter, withNavigationErrorHandler } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { providePrimeNG } from 'primeng/config';
import { MessageService } from 'primeng/api';
import Aura from '@primeuix/themes/aura';
import { routes } from './app.routes';
import { apiInterceptor } from './core/api.interceptor';

const CHUNK_RELOAD_KEY = 'hydra.chunkReloadAt';
const CHUNK_RELOAD_WINDOW_MS = 10_000;

// After a deploy, an old tab may ask for chunks that no longer exist: reload once on the target URL.
function reloadOnStaleChunk(event: NavigationError): void {
  const message = String(event.error?.message ?? event.error ?? '');
  if (!message.includes('Failed to fetch dynamically imported module')
      && !message.includes('Importing a module script failed')) {
    return;
  }
  try {
    const last = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) ?? 0);
    if (Date.now() - last < CHUNK_RELOAD_WINDOW_MS) {
      return;
    }
    sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now()));
  } catch {
    // sessionStorage unavailable: no loop guard possible, so do not reload
    return;
  }
  window.location.assign(event.url);
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withNavigationErrorHandler(reloadOnStaleChunk)),
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN' }),
      withFetch(),
      withInterceptors([apiInterceptor])
    ),
    provideAnimationsAsync(),
    providePrimeNG({ theme: { preset: Aura } }),
    MessageService
  ]
};
