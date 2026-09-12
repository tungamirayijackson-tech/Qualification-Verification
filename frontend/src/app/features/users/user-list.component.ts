import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Institution } from '../../core/api/institution';
import { InstitutionService } from '../../core/api/institution.service';
import { describeFailure } from '../../core/api/problem';
import { CreatedUser, Role, User } from '../../core/api/user';
import { UserService } from '../../core/api/user.service';
import { matchesFilter } from '../../shared/fold-text';
import { IconComponent } from '../../shared/icon.component';

type LoadState = 'loading' | 'ready' | 'failed';

/** The four things that can be done to an account once it exists. */
export type AccountAction = 'disable' | 'restore' | 'reset-password' | 'reset-mfa';

/** An action waiting to be confirmed, held rather than performed. */
interface PendingAction {
  readonly action: AccountAction;
  readonly user: User;
}

/**
 * Accounts, and the roles they hold.
 *
 * This is where every power in the system is handed out, which shapes two things on the screen.
 *
 * The initial password is **shown once**. It is generated rather than chosen, and stored only as
 * a hash, so the system genuinely cannot show it again — the screen says so plainly rather than
 * letting an administrator navigate away and come back for it.
 *
 * A registrar must be bound to an institution and an auditor must not be. The form follows the
 * chosen role rather than letting somebody submit a combination the register will refuse.
 *
 * The row actions all ask first. Three of them take somebody's access away or end their
 * sessions, and none is undoable by the person it happens to — a mis-click on the wrong row is
 * a colleague locked out of their work, so the screen says whose account and what will happen
 * before anything is sent.
 */
@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss'
})
export class UserListComponent {
  private readonly users = inject(UserService);
  private readonly institutionService = inject(InstitutionService);

  private readonly allUsers = signal<User[]>([]);
  protected readonly institutions = signal<Institution[]>([]);
  protected readonly state = signal<LoadState>('loading');

  protected readonly filterTerm = signal('');
  protected readonly rows = computed(() => {
    const term = this.filterTerm();
    return this.allUsers().filter((user) =>
      matchesFilter(term, user.email, user.displayName, user.role)
    );
  });
  protected readonly isFiltered = computed(() => this.filterTerm().trim().length > 0);
  protected readonly totalLoaded = computed(() => this.allUsers().length);

  protected readonly adding = signal(false);
  /** What is waiting on a yes, and for whom. Nothing is sent until it is confirmed. */
  protected readonly pending = signal<PendingAction | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The one and only sight of a new account's password. */
  protected readonly justCreated = signal<CreatedUser | null>(null);
  protected readonly copied = signal(false);

  protected email = '';
  protected displayName = '';
  protected role: Role = 'REGISTRAR';
  protected institutionId = '';

  protected readonly roles: Role[] = ['REGISTRAR', 'AUDITOR', 'ADMIN', 'VERIFIER'];

  constructor() {
    this.load();
    this.institutionService.list().subscribe({
      next: (found) => this.institutions.set(found),
      error: () => this.institutions.set([])
    });
  }

  protected load(): void {
    this.state.set('loading');
    this.users.list().subscribe({
      next: (found) => {
        this.allUsers.set(found);
        this.state.set('ready');
      },
      error: () => this.state.set('failed')
    });
  }

  protected onFilter(term: string): void {
    this.filterTerm.set(term);
  }

  /** Only a registrar is bound to an institution; the form follows the role. */
  protected needsInstitution(): boolean {
    return this.role === 'REGISTRAR';
  }

  protected toggleAdding(): void {
    this.adding.update((open) => !open);
    this.error.set(null);
    this.justCreated.set(null);
  }

  protected canSubmit(): boolean {
    if (this.busy() || !this.email.trim() || !this.displayName.trim()) {
      return false;
    }
    return !this.needsInstitution() || this.institutionId !== '';
  }

  protected create(): void {
    this.busy.set(true);
    this.error.set(null);
    this.justCreated.set(null);

    this.users
      .create({
        email: this.email.trim(),
        displayName: this.displayName.trim(),
        role: this.role,
        // Sent only when the role takes one: the register refuses an auditor with an
        // institution rather than quietly ignoring it.
        institutionId: this.needsInstitution() ? this.institutionId : undefined
      })
      .subscribe({
        next: (created) => {
          this.busy.set(false);
          this.adding.set(false);
          this.justCreated.set(created);
          this.email = '';
          this.displayName = '';
          this.load();
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.error.set(describeFailure(failure));
        }
      });
  }

  /** Copies the password, so it can be pasted somewhere before it is gone. */
  protected copyPassword(): void {
    const created = this.justCreated();
    if (!created) {
      return;
    }
    navigator.clipboard
      ?.writeText(created.initialPassword)
      .then(() => {
        this.copied.set(true);
        setTimeout(() => this.copied.set(false), 2000);
      })
      .catch(() => this.error.set('The browser would not give access to the clipboard.'));
  }

  protected dismissPassword(): void {
    this.justCreated.set(null);
  }

  // ---------------------------------------------------------------- row actions

  protected ask(action: AccountAction, user: User): void {
    this.error.set(null);
    this.justCreated.set(null);
    this.pending.set({ action, user });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  /** What the confirmation says, in the words of what will actually happen. */
  protected pendingDescription(pending: PendingAction): string {
    switch (pending.action) {
      case 'disable':
        return (
          `${pending.user.displayName} will not be able to sign in, and the sessions they ` +
          'have open will end. Their account is kept: the audit ledger names it in everything ' +
          'it did. This can be undone.'
        );
      case 'restore':
        return `${pending.user.displayName} will be able to sign in again with the password they already have.`;
      case 'reset-password':
        return (
          `A new password will be generated for ${pending.user.displayName} and shown to you ` +
          'once. The one they have now will stop working immediately, and their open sessions ' +
          'will end.'
        );
      case 'reset-mfa':
        return (
          `${pending.user.displayName}'s second factor will be cleared, and they will set up a ` +
          'new one on their next sign-in. Do this when they have lost the device holding it — ' +
          'until they enrol again, their password alone reaches the enrolment step.'
        );
    }
  }

  protected pendingTitle(pending: PendingAction): string {
    switch (pending.action) {
      case 'disable':
        return 'Suspend this account?';
      case 'restore':
        return 'Restore this account?';
      case 'reset-password':
        return 'Reset this password?';
      case 'reset-mfa':
        return 'Reset this second factor?';
    }
  }

  protected confirm(): void {
    const pending = this.pending();
    if (!pending || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);

    const id = pending.user.id;
    const done = (): void => {
      this.busy.set(false);
      this.pending.set(null);
      this.load();
    };
    const failed = (failure: unknown): void => {
      this.busy.set(false);
      this.pending.set(null);
      this.error.set(describeFailure(failure));
    };

    if (pending.action === 'reset-password') {
      // The one action that hands something back: the same once-only password card the
      // creation flow uses, because it is the same promise being made.
      this.users.resetPassword(id).subscribe({
        next: (reset) => {
          this.justCreated.set(reset);
          done();
        },
        error: failed
      });
      return;
    }

    const call =
      pending.action === 'disable'
        ? this.users.disable(id)
        : pending.action === 'restore'
          ? this.users.restore(id)
          : this.users.resetMfa(id);

    call.subscribe({ next: done, error: failed });
  }

  protected institutionName(id: string | null): string {
    if (!id) {
      return '—';
    }
    return this.institutions().find((institution) => institution.id === id)?.name ?? id;
  }
}
