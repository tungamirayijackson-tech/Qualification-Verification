/** One row that could not be imported (FR-02). */
export interface ImportFailure {
  /** The line in the uploaded file, counting the header as line 1. */
  readonly line: number;
  /** A stable code, so the console never matches on prose. */
  readonly reason: string;
  readonly detail: string;
}

/** What happened to a cohort import. */
export interface ImportReport {
  readonly totalRows: number;
  readonly imported: number;
  readonly failed: number;
  readonly elapsedMs: number;
  readonly failures: readonly ImportFailure[];
}
