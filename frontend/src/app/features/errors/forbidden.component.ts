import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SessionService } from '../../core/auth/session.service';
import { IconComponent } from '../../shared/icon.component';

/**
 * Shown when a signed-in user reaches a screen their role does not cover.
 *
 * It names the role they actually hold. "Access denied" leaves someone guessing whether they
 * mistyped a URL, need a different account, or have been given the wrong permissions — and the
 * answer is usually the third, which only an administrator can fix.
 */
@Component({
  selector: 'app-forbidden',
  standalone: true,
  imports: [RouterLink, IconComponent],
  template: `
    <div class="page">
      <div class="page-heading">
        <app-icon name="lock" [size]="26" />
        <h1 class="page-title">Not permitted</h1>
      </div>
      <p class="page-lede">
        You are signed in as
        <strong>{{ session.user()?.email }}</strong>
        with the role <strong>{{ session.role() }}</strong
        >, which does not cover that screen.
      </p>
      <p class="muted">
        Roles are deliberately narrow: an auditor cannot write to the register, and a registrar
        cannot read the ledger they write to. If you need a different one, an administrator has to
        grant it.
      </p>
      <div class="actions">
        <a routerLink="/verify">Check a qualification</a>
      </div>
    </div>
  `
})
export class ForbiddenComponent {
  protected readonly session = inject(SessionService);
}
