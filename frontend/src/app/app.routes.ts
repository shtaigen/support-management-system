import { Routes } from '@angular/router';
import { clientGuard, staffGuard } from './core/auth/role.guards';
import { Login } from './features/auth/login';
import { NotFound } from './features/not-found/not-found';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  { path: 'login', component: Login, title: 'Login' },
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
  { path: '**', component: NotFound, title: 'Not found' },
];
