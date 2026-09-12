import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Institution } from '../../core/api/institution';
import { InstitutionListComponent } from './institution-list.component';

describe('InstitutionListComponent', () => {
  let fixture: ComponentFixture<InstitutionListComponent>;
  let http: HttpTestingController;

  const onboarded: Institution = {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Example University',
    country: 'ZW',
    providerNumber: 'PR-0142',
    accreditedUntil: '2030-12-31',
    activeKeyId: 'inst-0142-2026-a'
  };

  const lapsed: Institution = {
    id: '33333333-3333-4333-8333-333333333333',
    name: 'Lapsed College',
    country: 'ZW',
    providerNumber: 'PR-0999',
    accreditedUntil: '2024-01-31',
    activeKeyId: null
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InstitutionListComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(InstitutionListComponent);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function respondWith(rows: Institution[]): void {
    fixture.detectChanges();
    http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush(rows);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function rowCount(): number {
    return (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr').length;
  }

  it('shows a loading state before the register answers', () => {
    fixture.detectChanges();

    expect(text()).toContain('Loading the register');

    http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([]);
  });

  it('renders each institution as a row', () => {
    respondWith([onboarded, lapsed]);

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(text()).toContain('Example University');
    expect(text()).toContain('PR-0142');
  });

  it('marks a lapsed accreditation and an institution with no signing key', () => {
    respondWith([lapsed]);

    // Title case, and asserted case-insensitively. The badges used to be written in lower
    // case and uppercased by a `text-transform`, so the text in the DOM and the text on the
    // screen disagreed; they now match. Matching without regard to case means a later styling
    // decision about capitals cannot break a test that is about whether the badge is there.
    expect(text().toLowerCase()).toContain('lapsed');
    expect(text().toLowerCase()).toContain('not onboarded');
  });

  it('distinguishes an empty register from a still-loading one', () => {
    respondWith([]);

    expect(text()).toContain('No institutions match');
    expect(text()).not.toContain('Loading the register');
  });

  it('offers a retry rather than an empty table when the API fails', () => {
    fixture.detectChanges();
    http
      .expectOne((candidate) => candidate.url === '/api/v1/institutions')
      .flush('boom', { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(text()).toContain('Could not reach the register');
    expect((fixture.nativeElement as HTMLElement).querySelector('button')).not.toBeNull();
  });

  describe('the filter box', () => {
    function typeIntoFilter(term: string): void {
      const box: HTMLInputElement = (fixture.nativeElement as HTMLElement).querySelector(
        'input[type="search"]'
      )!;
      box.value = term;
      box.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    it('narrows the list as characters are typed, without a button', () => {
      respondWith([onboarded, lapsed]);
      expect(rowCount()).toBe(2);

      typeIntoFilter('lap');

      expect(rowCount()).toBe(1);
      expect(text()).toContain('Lapsed College');
      expect(text()).not.toContain('Example University');
    });

    it('ignores capitalisation', () => {
      respondWith([onboarded, lapsed]);

      typeIntoFilter('EXAMPLE');

      expect(rowCount()).toBe(1);
      expect(text()).toContain('Example University');
    });

    it('ignores accents', () => {
      const accented: Institution = { ...onboarded, name: 'Université de Pretoria' };
      respondWith([accented, lapsed]);

      typeIntoFilter('universite');

      expect(rowCount()).toBe(1);
      expect(text()).toContain('Université de Pretoria');
    });

    it('matches the provider number and the country as well as the name', () => {
      respondWith([onboarded, lapsed]);

      typeIntoFilter('pr-0999');
      expect(rowCount()).toBe(1);
      expect(text()).toContain('Lapsed College');
    });

    it('says the filter is what emptied the list, not the register', () => {
      // "No institutions match" on a register that holds two is a misleading thing to read.
      respondWith([onboarded, lapsed]);

      typeIntoFilter('nothing-like-this');

      expect(rowCount()).toBe(0);
      expect(text()).toContain('Nothing matches');
    });

    it('restores every row when the box is emptied', () => {
      respondWith([onboarded, lapsed]);
      typeIntoFilter('lap');
      expect(rowCount()).toBe(1);

      typeIntoFilter('');

      expect(rowCount()).toBe(2);
    });

    it('filters what is already loaded, asking the server nothing', () => {
      // The whole register is on the page. A round trip per keystroke would be slower and no
      // more correct -- and afterEach's http.verify() is what proves none was made.
      respondWith([onboarded, lapsed]);

      typeIntoFilter('e');
      typeIntoFilter('ex');
      typeIntoFilter('exa');

      expect(rowCount()).toBe(1);
    });
  });
});
