import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AuditEntry, ChainVerification } from '../../core/api/audit';
import { AuditService } from '../../core/api/audit.service';
import { describeFailure } from '../../core/api/problem';
import { IconComponent } from '../../shared/icon.component';

type State = 'loading' | 'ready' | 'failed';

/**
 * The auditor's view of the ledger (FR-08, FR-09).
 *
 * Chain verification is a button rather than something the page does on load, and that is
 * deliberate on two counts. It reads and re-hashes the whole history, so it is not free. And it
 * is itself recorded in the ledger — an auditor should be choosing to run a check that will be
 * attributed to them, not having one attributed to them by a page they happened to open.
 *
 * The same reasoning applies to the CSV export.
 */
@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './audit.component.html',
  styleUrl: './audit.component.scss'
})
export class AuditComponent {
  private readonly audit = inject(AuditService);

  protected readonly state = signal<State>('loading');
  protected readonly entries = signal<AuditEntry[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly chain = signal<ChainVerification | null>(null);

  protected subject = '';

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.error.set(null);

    this.audit.query(this.subject.trim() || undefined).subscribe({
      next: (entries) => {
        this.entries.set(entries);
        this.state.set('ready');
      },
      error: (failure: unknown) => {
        this.error.set(describeFailure(failure));
        this.state.set('failed');
      }
    });
  }

  protected verifyChain(): void {
    this.busy.set(true);
    this.error.set(null);

    this.audit.verifyChain().subscribe({
      next: (result) => {
        this.busy.set(false);
        this.chain.set(result);
        // A verification appends an entry of its own, so the list on screen is now one row out
        // of date. Reloading keeps what the auditor sees honest.
        this.load();
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        this.error.set(describeFailure(failure));
      }
    });
  }

  protected exportCsv(): void {
    this.busy.set(true);
    this.error.set(null);

    this.audit.exportCsv(this.subject.trim() || undefined).subscribe({
      next: (csv) => {
        this.busy.set(false);
        this.download(csv);
        this.load();
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        this.error.set(describeFailure(failure));
      }
    });
  }

  /** Hands the CSV to the browser as a file. */
  private download(csv: string): void {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'qvs-audit-' + new Date().toISOString().slice(0, 10) + '.csv';
    anchor.click();
    // Revoking the object URL matters: without it the blob is pinned in memory for the life of
    // the document, and an auditor exporting repeatedly would slowly leak.
    URL.revokeObjectURL(url);
  }
}
