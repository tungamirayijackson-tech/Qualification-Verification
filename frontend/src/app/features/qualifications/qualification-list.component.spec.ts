import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Role } from '../../core/api/user';
import { SessionService } from '../../core/auth/session.service';
import { QualificationListComponent } from './qualification-list.component';

/**
 * Who has to name an institution, and who must not be asked to.
 *
 * This screen used to ask "is this an administrator?" when the question it meant was "is this
 * account bound to one institution?". An auditor is neither — cross-institution by design, like
 * an administrator, and not an administrator — so it fell into the registrar's branch, sent no
 * institution, and the API refused every request with a 400. Every visit to this page by an
 * auditor was a broken screen.
 */
describe('QualificationListComponent', () => {
  let fixture: ComponentFixture<QualificationListComponent>;
  let http: HttpTestingController;

  const institution = {
    id: '99999999-9999-4999-8999-999999999999',
    name: 'Example University',
    country: 'ZW',
    providerNumber: 'PR-0142',
    accreditedUntil: '2030-12-31',
    activeKeyId: 'inst-0142-2026-a'
  };

  const qualification = {
    id: '77777777-7777-4777-8777-777777777777',
    institutionId: institution.id,
    title: 'BSc Computer Science',
    nqfLevel: 7,
    credits: 360,
    saqaQualId: 'SAQA-12345'
  };

  /** Signs the fixture in as one role, which is all this screen reads the session for. */
  function signedInAs(role: Role): void {
    TestBed.configureTestingModule({
      imports: [QualificationListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: SessionService,
          useValue: {
            hasRole: (...roles: readonly Role[]) => roles.includes(role)
          }
        }
      ]
    });

    fixture = TestBed.createComponent(QualificationListComponent);
    http = TestBed.inject(HttpTestingController);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => http.verify());

  describe('an auditor', () => {
    it('is asked which institution, rather than sending a request that cannot succeed', () => {
      signedInAs('AUDITOR');
      fixture.detectChanges();

      // The institutions come first; the qualifications request waits for an answer to "which".
      http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([institution]);
      fixture.detectChanges();

      const asked = http.expectOne((candidate) => candidate.url === '/api/v1/qualifications');
      expect(asked.request.params.get('institutionId'))
        .withContext('an auditor is not bound to one institution, so one has to be named')
        .toBe(institution.id);
      asked.flush([qualification]);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('select[name="institution"]')).not.toBeNull();
      expect(text()).toContain('BSc Computer Science');
    });

    it('is not offered a way to add one, because an auditor writes nothing', () => {
      signedInAs('AUDITOR');
      fixture.detectChanges();
      http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([institution]);
      fixture.detectChanges();
      http.expectOne((candidate) => candidate.url === '/api/v1/qualifications').flush([]);
      fixture.detectChanges();

      // Asserted on the control, not the prose: the empty state mentions adding one too, and
      // the thing that matters is that an auditor is not offered a button that would 403.
      const buttons = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('button')
      ).map((button) => button.textContent?.trim());
      expect(buttons).not.toContain('Add a qualification');
      expect(text())
        .withContext('nor told to do it')
        .not.toContain('Add a qualification before registering');
    });
  });

  describe('an administrator', () => {
    it('is asked which institution too, and reads what it offers', () => {
      signedInAs('ADMIN');
      fixture.detectChanges();
      http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([institution]);
      fixture.detectChanges();

      const asked = http.expectOne((candidate) => candidate.url === '/api/v1/qualifications');
      expect(asked.request.params.get('institutionId')).toBe(institution.id);
      asked.flush([qualification]);
      fixture.detectChanges();

      expect(text()).toContain('BSc Computer Science');
    });

    it("cannot record one, because that is the institution's own business", () => {
      // An administrator admits an institution and gives it a signing key. What it awards is
      // declared by the registrar who speaks for it, and the API refuses anybody else.
      signedInAs('ADMIN');
      fixture.detectChanges();
      http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([institution]);
      fixture.detectChanges();
      http.expectOne((candidate) => candidate.url === '/api/v1/qualifications').flush([]);
      fixture.detectChanges();

      const buttons = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('button')
      ).map((button) => button.textContent?.trim());
      expect(buttons).not.toContain('Add a qualification');
    });
  });

  describe('a registrar', () => {
    it('is never asked, because the register takes the institution from their token', () => {
      signedInAs('REGISTRAR');
      fixture.detectChanges();

      const asked = http.expectOne((candidate) => candidate.url === '/api/v1/qualifications');
      expect(asked.request.params.has('institutionId'))
        .withContext('sending one would be ignored anyway; scope wins over the parameter')
        .toBeFalse();
      asked.flush([qualification]);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('select[name="institution"]')).toBeNull();
      expect(text()).toContain("These are your own institution's");
      expect(text()).toContain('Add a qualification');
    });
  });
});
