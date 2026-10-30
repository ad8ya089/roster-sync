export type Role = 'HR_SYSTEM' | 'TRANSPORT_ADMIN' | 'OPERATOR' | 'EMPLOYEE';
export type Outcome = 'APPLIED' | 'STALE' | 'DUPLICATE' | 'NO_CHANGE' | 'REJECTED' | 'CONFLICT' | 'RETRYABLE_ERROR';
export type RunStatus = 'UPLOADED' | 'DIFFING' | 'PLANNED' | 'AWAITING_APPROVAL' | 'APPLYING' | 'COMPLETED' | 'REJECTED' | 'FAILED';
export interface FieldError { field: string; code: string }
export interface PageResponse<T> { items: T[]; page: number; size: number; totalElements: number }
export interface LoginResponse { accessToken: string; tokenType: string; expiresIn: number; role: Role; tenant: string }
export interface Claims { sub: string; tid: string; tcode: string; role: Role; emp?: string }
export interface Receipt { version: string; type: string; outcome: Outcome; receivedAt: string }
export interface Rejection { rowNumber: number; employeeId: string | null; errorCodes: string[] }
export interface Plan {
  creates: number; updates: number; heals: number; advances: number;
  deactivations: number; unchanged: number; staleSkipped: number;
}
export interface Brake {
  tripped: boolean; reason: string | null; reasons: string[];
  activeBefore: number; missingActive: number; missingRatio: number;
  thresholdRatio: number | null; thresholdCount: number | null; message: string;
}
export interface Run {
  runId: string; status: RunStatus; dryRun: boolean; createdAt: string; asOf: string;
  rows: { total: number; valid: number; rejected: number }; plan: Plan; brake: Brake;
  applied: number; superseded: number; timings: { diffMs: number | null; applyMs: number | null };
  failureReason: string | null;
}
