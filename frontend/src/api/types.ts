export type Role = "HR_SYSTEM" | "TRANSPORT_ADMIN" | "OPERATOR" | "EMPLOYEE";
export type Outcome =
  | "APPLIED"
  | "STALE"
  | "DUPLICATE"
  | "NO_CHANGE"
  | "REJECTED"
  | "CONFLICT"
  | "RETRYABLE_ERROR";
export type RunStatus =
  | "UPLOADED"
  | "DIFFING"
  | "PLANNED"
  | "AWAITING_APPROVAL"
  | "APPLYING"
  | "COMPLETED"
  | "REJECTED"
  | "FAILED";
export interface FieldError {
  field: string;
  code: string;
}
export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
}
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  role: Role;
  tenant: string;
}
export interface Claims {
  sub: string;
  tid: string;
  tcode: string;
  role: Role;
  emp?: string;
}
export interface Receipt {
  version: string;
  type: string;
  outcome: Outcome;
  receivedAt: string;
}
export interface Rejection {
  rowNumber: number;
  employeeId: string | null;
  errorCodes: string[];
}
export interface Plan {
  creates: number;
  updates: number;
  heals: number;
  advances: number;
  deactivations: number;
  unchanged: number;
  staleSkipped: number;
}
export interface Brake {
  tripped: boolean;
  reason: string | null;
  reasons: string[];
  activeBefore: number;
  missingActive: number;
  missingRatio: number;
  thresholdRatio: number | null;
  thresholdCount: number | null;
  message: string;
}
export interface Run {
  runId: string;
  status: RunStatus;
  dryRun: boolean;
  createdAt: string;
  asOf: string;
  rows: { total: number; valid: number; rejected: number };
  plan: Plan;
  brake: Brake;
  applied: number;
  superseded: number;
  timings: { diffMs: number | null; applyMs: number | null };
  failureReason: string | null;
}
export interface Employee {
  employeeId: string;
  status: "ACTIVE" | "INACTIVE";
  hrVersion: string;
  name: string | null;
  email: string | null;
  phone: string | null;
  managerId: string | null;
  officeCode: string | null;
  shiftCode: string | null;
  transport: {
    pickupPoint: string | null;
    specialAssistance: string | null;
    version: string;
  };
  hrUpdatedAt: string;
  deactivatedAt: string | null;
}
export interface Booking {
  id: number;
  employeeId: string;
  pickupAt: string;
  status: string;
  cancelReason: string | null;
}
export interface Settings {
  maxMissingRatio: number;
  maxMissingCount: number | null;
  source: "DEFAULT" | "TENANT";
}
export interface Alert {
  id: number;
  alertType: string;
  referenceId: string;
  message: string;
  createdAt: string;
  acknowledgedAt: string | null;
}
export interface OutboxRow {
  id: number;
  eventId: string;
  employeeId: string;
  eventType: string;
  status: string;
  attempts: number;
  nextAttemptAt: string;
  lastError: string | null;
  createdAt: string;
  publishedAt: string | null;
}
export interface OpsSummary {
  generatedAt: string;
  durable: {
    last15m: {
      applied: number;
      stale: number;
      noChange: number;
      rejected: number;
      conflict: number;
    };
    outbox: {
      pending: number;
      dead: number;
      oldestPendingAgeSeconds: number;
      currentlyRetrying: number;
    };
    reconciliation: {
      awaitingApproval: number;
      lastRun: {
        runId: string;
        status: RunStatus;
        brakeReason: string | null;
        missingActive: number | null;
      } | null;
    };
    openAlerts: number;
  };
  sinceRestart: {
    since: string;
    instanceWide: boolean;
    duplicates: number;
    lockRetries: number;
    outboxPublishFailures: number;
    p99ApplyMs: number | null;
    lagP99Seconds: number | null;
  };
}
export interface EventResult {
  index: number;
  employeeId: string | null;
  version: string;
  outcome: Outcome;
  currentVersion?: string;
  originalOutcome?: Outcome;
  warnings: FieldError[];
  errors?: FieldError[];
}
export interface IngestResponse {
  summary: Record<string, number>;
  results: EventResult[];
}
