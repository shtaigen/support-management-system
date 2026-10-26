import { Routes } from '@angular/router';
import { clientGuard, guestGuard, staffGuard } from './core/auth/guards';
import { homeRedirect } from './core/auth/home-redirect';
import { Login } from './features/auth/login';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: homeRedirect },
  { path: 'login', canMatch: [guestGuard], component: Login, title: 'Login' },
  {
    path: 'client',
    canMatch: [clientGuard],
    loadChildren: () => import('./features/client/client.routes'),
  },
  {
    path: 'staff',
    canMatch: [staffGuard],
    loadChildren: () => import('./features/staff/staff.routes'),
  },
  { path: '**', redirectTo: homeRedirect },
];
