/**
 * A qualification an institution offers, as the API returns it.
 *
 * Mirrors `QualificationResponse` on the backend.
 */
export interface Qualification {
  readonly id: string;
  readonly institutionId: string;
  readonly title: string;
  readonly nqfLevel: number;
  readonly credits: number;
  readonly saqaQualId: string | null;
  /** Phased out qualifications keep their credentials and accept no new awards. */
  readonly phasedOut: boolean;
}

/**
 * What a registrar or administrator sends to record one.
 *
 * There is deliberately no institution here. Recording a qualification is a registrar's act and
 * the API takes their institution from their token, so there is no field to send and nothing to
 * get wrong.
 */
export interface AddQualificationRequest {
  readonly title: string;
  readonly nqfLevel: number;
  readonly credits: number;
  readonly saqaQualId?: string;
}
