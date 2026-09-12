import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Institution } from './institution';
import { InstitutionService } from './institution.service';

describe('InstitutionService', () => {
  let service: InstitutionService;
  let http: HttpTestingController;

  const example: Institution = {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Example University',
    country: 'ZW',
    providerNumber: 'PR-0142',
    accreditedUntil: '2030-12-31',
    activeKeyId: 'inst-0142-2026-a'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(InstitutionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests the register with the filter off by default', () => {
    service.list().subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/v1/institutions');
    expect(request.request.params.get('eligibleOnly')).toBe('false');
    request.flush([]);
  });

  it('passes the eligibility filter through to the API rather than filtering locally', () => {
    // Eligibility depends on accreditation dates and key configuration, both of which are
    // domain decisions. If the console ever filtered client-side, the two could disagree.
    service.list(true).subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/v1/institutions');
    expect(request.request.params.get('eligibleOnly')).toBe('true');
    request.flush([]);
  });

  it('returns what the API returned, unmodified', () => {
    let received: Institution[] | undefined;
    service.list().subscribe((found) => (received = found));

    http.expectOne((candidate) => candidate.url === '/api/v1/institutions').flush([example]);

    expect(received).toEqual([example]);
  });
});
