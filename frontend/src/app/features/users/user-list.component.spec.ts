import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { CreatedUser, User } from '../../core/api/user';
import { UserListComponent } from './user-list.component';

/**
 * The screen that hands out every power in the system.
 *
 * Two of these tests are worth more than the rest. One is that the password appears once and
 * only in response to a creation — a screen that could show it again would make the "stored
 * only as a hash" promise a lie on the way out. The other is that the form follows the chosen
 * role, because a registrar without an institution and an auditor with one are both refused by
 * the register, and a form that lets somebody submit either is a form that wastes their time.
 */
describe('UserListComponent', () => {
  let fixture: ComponentFixture<UserListComponent>;
  let http: HttpTestingController;

  const registrar: User = {
    id: '11111111-1111-4111-8111-111111111111',
    email: 'Rita.Registrar@example.ac.zw',
    displayName: 'Rita Registrar',
    role: 'REGISTRAR',
    institutionId: '99999999-9999-4999-8999-999999999999',
    mfaEnrolled: true,
    disabled: false
  };

  const auditor: User = {
    id: '22222222-2222-4222-8222-222222222222',
    email: 'alan.auditor@qvs.ac.zw',
    displayName: 'Alan Auditor',
    role: 'AUDITOR',
    institutionId: null,
    mfaEnrolled: false,
    disabled: false
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserListComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(UserListComponent);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** The screen asks for accounts and institutions; both have to be answered. */
  function respondWith(accounts: User[]): void {
    fixture.detectChanges();
    http.expectOne((candidate) => candidate.url === '/api/v1/users').flush(accounts);
    http
      .expectOne((candidate) => candidate.url === '/api/v1/institutions')
      .flush([
        {
          id: '99999999-9999-4999-8999-999999999999',
          name: 'Example University',
          country: 'ZW',
          providerNumber: 'PR-0142',
          accreditedUntil: '2030-12-31',
          activeKeyId: 'inst-0142-2026-a'
        }
      ]);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function rowCount(): number {
    return (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr').length;
  }

  function typeIntoFilter(term: string): void {
    const box: HTMLInputElement = (fixture.nativeElement as HTMLElement).querySelector(
      'input[type="search"]'
    )!;
    box.value = term;
    box.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('lists each account with the role it holds', () => {
    respondWith([registrar, auditor]);

    expect(rowCount()).toBe(2);
    expect(text()).toContain('Rita Registrar');
    expect(text()).toContain('AUDITOR');
  });

  it('resolves a registrar’s institution to its name rather than showing a bare id', () => {
    respondWith([registrar]);

    expect(text()).toContain('Example University');
    expect(text()).not.toContain('99999999-9999-4999-8999-999999999999');
  });

  it('says which accounts still owe a second factor', () => {
    respondWith([auditor]);

    // Not enrolled yet: the secret is collected on first sign-in, on the user's own device.
    expect(text().toLowerCase()).toContain('first sign-in');
  });

  it('offers a retry rather than an empty table when the API fails', () => {
    fixture.detectChanges();
    http
      .expectOne((candidate) => candidate.url === '/api/v1/users')
      .flush('boom', { status: 503, statusText: 'Service Unavailable' });
    http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([]);
    fixture.detectChanges();

    expect(text()).toContain('Could not load the accounts');
  });

  describe('the filter box', () => {
    it('narrows the list as characters are typed, without a button', () => {
      respondWith([registrar, auditor]);
      expect(rowCount()).toBe(2);

      typeIntoFilter('aud');

      expect(rowCount()).toBe(1);
      expect(text()).toContain('Alan Auditor');
    });

    it('ignores case, so a name typed in lower case still finds it', () => {
      respondWith([registrar, auditor]);

      // The stored address is mixed case; nobody types it that way.
      typeIntoFilter('rita.registrar');

      expect(rowCount()).toBe(1);
      expect(text()).toContain('Rita Registrar');
    });

    it('matches on role, which is how an administrator asks "who can sign?"', () => {
      respondWith([registrar, auditor]);

      typeIntoFilter('registrar');

      expect(rowCount()).toBe(1);
    });

    it('distinguishes "nothing matches" from "there is nobody"', () => {
      respondWith([registrar, auditor]);

      typeIntoFilter('nobody-by-that-name');

      expect(rowCount()).toBe(0);
      expect(text()).toContain('Nothing matches');
      expect(text()).not.toContain('No accounts yet');
    });
  });

  describe('the row actions', () => {
    /** Clicks a button by the words on it, in the row for a given address. */
    function clickInRow(email: string, label: string): void {
      const row = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')
      ).find((candidate) => candidate.textContent?.includes(email))!;
      const button = Array.from(row.querySelectorAll('button')).find(
        (candidate) => candidate.textContent?.trim() === label
      )!;
      expect(button).withContext(`a "${label}" button in ${email}'s row`).toBeDefined();
      button.click();
      fixture.detectChanges();
    }

    function confirmButton(): HTMLButtonElement {
      return (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.confirm-card .primary, .confirm-card .danger'
      )!;
    }

    it('offers Suspend for an active account and Restore for a suspended one', () => {
      respondWith([registrar, { ...auditor, disabled: true }]);

      expect(text()).toContain('Suspend');
      expect(text()).toContain('Restore');
      expect(text()).toContain('Suspended');
    });

    it('asks before it does anything, and sends nothing until it is confirmed', () => {
      respondWith([registrar]);

      clickInRow(registrar.email, 'Suspend');

      // Nothing has been sent: http.verify() in afterEach fails if it has.
      expect(text()).toContain('Suspend this account?');
      expect(text())
        .withContext('the confirmation names who it is about')
        .toContain('Rita Registrar');
    });

    it('says what suspending will do, including that the account is kept', () => {
      respondWith([registrar]);

      clickInRow(registrar.email, 'Suspend');

      expect(text()).toContain('will not be able to sign in');
      expect(text()).toContain('account is kept');
    });

    it('sends nothing when the administrator backs out', () => {
      respondWith([registrar]);
      clickInRow(registrar.email, 'Suspend');

      (fixture.nativeElement as HTMLElement)
        .querySelectorAll<HTMLButtonElement>('.confirm-card button')[1]
        .click();
      fixture.detectChanges();

      expect(text()).not.toContain('Suspend this account?');
    });

    it('suspends on confirmation, and reloads the list afterwards', () => {
      respondWith([registrar]);
      clickInRow(registrar.email, 'Suspend');

      confirmButton().click();

      const posted = http.expectOne(
        (candidate) =>
          candidate.url === `/api/v1/users/${registrar.id}/disable` && candidate.method === 'POST'
      );
      posted.flush({ ...registrar, disabled: true });

      http
        .expectOne((candidate) => candidate.url === '/api/v1/users')
        .flush([{ ...registrar, disabled: true }]);
      fixture.detectChanges();

      expect(text()).toContain('Suspended');
      expect(text()).toContain('Restore');
    });

    it('shows the new password once when one is reset', () => {
      respondWith([auditor]);
      clickInRow(auditor.email, 'Reset password');

      expect(text()).toContain('stop working immediately');

      confirmButton().click();
      http
        .expectOne((candidate) => candidate.url === `/api/v1/users/${auditor.id}/reset-password`)
        .flush({ account: auditor, initialPassword: 'a-brand-new-password' });
      http.expectOne((candidate) => candidate.url === '/api/v1/users').flush([auditor]);
      fixture.detectChanges();

      expect(text()).toContain('a-brand-new-password');
      expect(text()).toContain('only time this password is shown');
    });

    it('offers a second-factor reset only where there is one to clear', () => {
      respondWith([registrar, auditor]);

      // The registrar is enrolled; the auditor has never been.
      const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr'));
      const enrolled = rows.find((row) => row.textContent?.includes(registrar.email))!;
      const notEnrolled = rows.find((row) => row.textContent?.includes(auditor.email))!;

      expect(enrolled.textContent).toContain('Reset 2FA');
      expect(notEnrolled.textContent).not.toContain('Reset 2FA');
    });

    it('reports a refusal from the register rather than pretending it worked', () => {
      respondWith([registrar]);
      clickInRow(registrar.email, 'Suspend');
      confirmButton().click();

      http
        .expectOne((candidate) => candidate.method === 'POST')
        .flush(
          {
            title: 'Account unchanged',
            detail: 'This is the only administrator who can still sign in.'
          },
          { status: 422, statusText: 'Unprocessable Entity' }
        );
      fixture.detectChanges();

      expect(text()).toContain('only administrator who can still sign in');
      expect(text())
        .withContext('and the confirmation is dismissed rather than left hanging')
        .not.toContain('Suspend this account?');
    });
  });

  describe('creating an account', () => {
    /** Opens the form, if it is not already open, and fills it in through the DOM. */
    function fillIn(email: string, name: string, role: string): void {
      const host = fixture.nativeElement as HTMLElement;
      if (!host.querySelector('form')) {
        // The button is a toggle: clicking it while the form is open closes it again.
        host.querySelector<HTMLButtonElement>('.head button')!.click();
        fixture.detectChanges();
        // Each ngModel inside a form registers its control on a microtask. Typing before that
        // has happened reaches the DOM and is dropped on the floor, so the form has to be
        // given that tick before anything is entered into it.
        tick();
      }

      const emailBox = host.querySelector<HTMLInputElement>('input[name="email"]')!;
      emailBox.value = email;
      emailBox.dispatchEvent(new Event('input'));

      const nameBox = host.querySelector<HTMLInputElement>('input[name="displayName"]')!;
      nameBox.value = name;
      nameBox.dispatchEvent(new Event('input'));

      chooseByLabel(host.querySelector<HTMLSelectElement>('select[name="role"]')!, role);

      tick();
      fixture.detectChanges();
    }

    /**
     * Picks an option by the text on it, rather than by assigning to `value`.
     *
     * Angular rewrites the `value` attribute of every option inside an `ngModel` select to its
     * own index token ("0: REGISTRAR"), so assigning the role string selects nothing at all and
     * the model silently keeps its previous value -- which is a test that passes for the wrong
     * reason waiting to happen.
     */
    function chooseByLabel(select: HTMLSelectElement, label: string): void {
      const index = Array.from(select.options).findIndex(
        (option) => option.textContent?.trim() === label
      );
      expect(index).withContext(`no option reading "${label}"`).toBeGreaterThan(-1);
      select.selectedIndex = index;
      select.dispatchEvent(new Event('change'));
    }

    it('asks for an institution for a registrar and for nobody else', fakeAsync(() => {
      respondWith([]);
      const host = fixture.nativeElement as HTMLElement;

      fillIn('new.registrar@example.ac.zw', 'New Registrar', 'REGISTRAR');
      expect(host.querySelector('select[name="institutionId"]')).not.toBeNull();

      fillIn('', '', 'AUDITOR');
      expect(host.querySelector('select[name="institutionId"]'))
        .withContext('an auditor reads across all institutions, so naming one is refused')
        .toBeNull();
    }));

    it('will not submit a registrar until an institution is chosen', fakeAsync(() => {
      respondWith([]);
      const host = fixture.nativeElement as HTMLElement;

      fillIn('new.registrar@example.ac.zw', 'New Registrar', 'REGISTRAR');

      const submit = host.querySelector<HTMLButtonElement>('button[type="submit"]')!;
      expect(submit.disabled).toBeTrue();
    }));

    it('shows the password once, and only after an account is created', fakeAsync(() => {
      respondWith([]);
      const created: CreatedUser = {
        account: { ...auditor, email: 'new.auditor@qvs.ac.zw' },
        initialPassword: 'a-generated-password'
      };

      expect(text()).not.toContain('a-generated-password');

      fillIn('new.auditor@qvs.ac.zw', 'New Auditor', 'AUDITOR');
      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLFormElement>('form')!
        .dispatchEvent(new Event('submit'));

      const posted = http.expectOne(
        (candidate) => candidate.url === '/api/v1/users' && candidate.method === 'POST'
      );
      expect(posted.request.body.institutionId)
        .withContext('an auditor is sent without one rather than with an ignored one')
        .toBeUndefined();
      posted.flush(created);

      // The list reloads once the account exists.
      http.expectOne((candidate) => candidate.url === '/api/v1/users').flush([created.account]);
      fixture.detectChanges();

      expect(text()).toContain('a-generated-password');
      expect(text()).toContain('only time this password is shown');
    }));

    it('reports a refusal from the register instead of pretending it worked', fakeAsync(() => {
      respondWith([]);

      fillIn('taken@qvs.ac.zw', 'Taken', 'AUDITOR');
      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLFormElement>('form')!
        .dispatchEvent(new Event('submit'));

      http
        .expectOne((candidate) => candidate.method === 'POST')
        .flush(
          { title: 'Unprocessable', detail: 'An account already exists for taken@qvs.ac.zw.' },
          { status: 422, statusText: 'Unprocessable Entity' }
        );
      fixture.detectChanges();

      expect(text()).toContain('An account already exists');
      expect(text())
        .withContext('nothing was created, so there is no password to show')
        .not.toContain('only time this password is shown');
    }));
  });
});
