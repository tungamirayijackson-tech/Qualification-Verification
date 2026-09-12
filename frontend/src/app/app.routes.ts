import { Routes } from '@angular/router';

import { roleGuard, signedInGuard } from './core/auth/role.guard';

/**
 * Route table.
 *
 * Two front doors (§04), kept visibly apart. The console tree sits behind guards; `/verify` and
 * `/sign-in` sit outside them and always will. Writing it this way means adding a screen to the
 * console cannot accidentally leave it unguarded, and adding something to the public side is a
 * conscious act rather than a default.
 *
 * The guards are a convenience for the user, not the security boundary — the API enforces every
 * one of these roles again, because an attacker calls the endpoint and never runs the guard.
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'verify'
  },

  // ---------------------------------------------------------------- public
  {
    path: 'verify',
    title: 'Check a qualification · QVS',
    loadComponent: () =>
      import('./features/verify/public-verify.component').then((m) => m.PublicVerifyComponent)
  },
  {
    // The link a holder shares. Same screen, token supplied by the URL.
    path: 'verify/:token',
    title: 'Check a qualification · QVS',
    loadComponent: () =>
      import('./features/verify/public-verify.component').then((m) => m.PublicVerifyComponent)
  },
  {
    path: 'sign-in',
    title: 'Sign in · QVS',
    loadComponent: () => import('./features/auth/sign-in.component').then((m) => m.SignInComponent)
  },

  // --------------------------------------------------------------- console
  {
    path: 'credentials',
    data: { shell: 'console' },
    pathMatch: 'full',
    title: 'Search the register · QVS',
    canActivate: [roleGuard('REGISTRAR', 'AUDITOR')],
    loadComponent: () =>
      import('./features/credentials/search-credentials.component').then(
        (m) => m.SearchCredentialsComponent
      )
  },
  {
    path: 'credentials/register',
    data: { shell: 'console' },
    title: 'Register a qualification · QVS',
    canActivate: [roleGuard('REGISTRAR')],
    loadComponent: () =>
      import('./features/credentials/register-credential.component').then(
        (m) => m.RegisterCredentialComponent
      )
  },
  {
    path: 'credentials/import',
    data: { shell: 'console' },
    title: 'Import a cohort · QVS',
    canActivate: [roleGuard('REGISTRAR')],
    loadComponent: () =>
      import('./features/credentials/import-cohort.component').then((m) => m.ImportCohortComponent)
  },
  {
    path: 'credentials/:serial',
    data: { shell: 'console' },
    title: 'Credential · QVS',
    canActivate: [roleGuard('REGISTRAR', 'AUDITOR')],
    loadComponent: () =>
      import('./features/credentials/credential-detail.component').then(
        (m) => m.CredentialDetailComponent
      )
  },
  {
    path: 'qualifications',
    data: { shell: 'console' },
    title: 'Qualifications · QVS',
    canActivate: [roleGuard('REGISTRAR', 'AUDITOR', 'ADMIN')],
    loadComponent: () =>
      import('./features/qualifications/qualification-list.component').then(
        (m) => m.QualificationListComponent
      )
  },
  {
    path: 'institutions',
    data: { shell: 'console' },
    title: 'Awarding bodies · QVS',
    canActivate: [signedInGuard],
    loadComponent: () =>
      import('./features/institutions/institution-list.component').then(
        (m) => m.InstitutionListComponent
      )
  },
  {
    path: 'users',
    data: { shell: 'console' },
    title: 'Accounts · QVS',
    canActivate: [roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/users/user-list.component').then((m) => m.UserListComponent)
  },
  {
    path: 'audit',
    data: { shell: 'console' },
    title: 'Audit ledger · QVS',
    canActivate: [roleGuard('AUDITOR')],
    loadComponent: () => import('./features/audit/audit.component').then((m) => m.AuditComponent)
  },

  // ---------------------------------------------------------------- errors
  {
    path: 'forbidden',
    title: 'Not permitted · QVS',
    loadComponent: () =>
      import('./features/errors/forbidden.component').then((m) => m.ForbiddenComponent)
  },
  { path: '**', redirectTo: 'verify' }
];
