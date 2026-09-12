import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';

import { ImportReport } from '../../core/api/import';
import { ImportService } from '../../core/api/import.service';
import { describeFailure } from '../../core/api/problem';
import { IconComponent } from '../../shared/icon.component';

type State = 'idle' | 'uploading' | 'done' | 'failed';

/**
 * FR-02: upload a graduation cohort.
 *
 * <p>The screen is built around the fact that a partly successful import is the normal case,
 * not an error. A file with three bad rows out of a thousand has done its job — 997 graduates
 * are registered and signed — so the result is presented as a report to work through rather
 * than a failure to retry. Re-uploading the whole file would simply refuse the 997 as
 * duplicates.
 *
 * <p>The failures table exists to be acted on: it carries the line number the registrar can see
 * in their spreadsheet, and it can be downloaded so they can work through it away from the
 * browser.
 */
@Component({
  selector: 'app-import-cohort',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './import-cohort.component.html',
  styleUrl: './import-cohort.component.scss'
})
export class ImportCohortComponent {
  private readonly imports = inject(ImportService);

  protected readonly state = signal<State>('idle');
  protected readonly report = signal<ImportReport | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly selectedFile = signal<File | null>(null);

  /** The header the API expects, shown so a registrar can build the file correctly. */
  protected readonly exampleCsv =
    'nationalId,holderName,qualificationId,awardedOn,dateOfBirth\n' +
    '63-1234567K42,Thandeka N. Mahlangu,aaaaaaaa-0001-4111-8111-aaaaaaaaaaaa,2026-04-11,1998-01-15';

  protected choose(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files?.[0] ?? null);
    this.report.set(null);
    this.error.set(null);
    this.state.set('idle');
  }

  protected upload(): void {
    const file = this.selectedFile();
    if (!file) {
      return;
    }

    this.state.set('uploading');
    this.error.set(null);

    this.imports.upload(file).subscribe({
      next: (report) => {
        this.report.set(report);
        this.state.set('done');
      },
      error: (failure: unknown) => {
        this.error.set(describeFailure(failure));
        this.state.set('failed');
      }
    });
  }

  /** Hands the failure list back as a CSV the registrar can work through offline. */
  protected downloadFailures(): void {
    const report = this.report();
    if (!report || report.failures.length === 0) {
      return;
    }

    const csv = [
      'line,reason,detail',
      ...report.failures.map((f) => `${f.line},${f.reason},"${f.detail.replace(/"/g, '""')}"`)
    ].join('\n');

    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'import-failures.csv';
    anchor.click();
    // Without this the blob stays pinned for the life of the document.
    URL.revokeObjectURL(url);
  }

  protected rowsPerSecond(report: ImportReport): string {
    if (report.elapsedMs === 0) {
      return '—';
    }
    return ((report.totalRows / report.elapsedMs) * 1000).toFixed(0);
  }
}
