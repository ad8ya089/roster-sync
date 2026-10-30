import { Check, Circle, Clock3, Copy, ShieldAlert, X } from 'lucide-react';
import type { Outcome } from '../api/types';

const outcomes = {
  APPLIED: ['Applied', 'applied', Check], STALE: ['Stale', 'stale', Clock3],
  DUPLICATE: ['Duplicate', 'neutral', Copy], NO_CHANGE: ['No change', 'neutral', Circle],
  REJECTED: ['Rejected', 'failed', X], CONFLICT: ['Conflict', 'failed', ShieldAlert],
  RETRYABLE_ERROR: ['Retryable error', 'failed', Clock3],
} as const;
export function OutcomeBadge({ outcome }: { outcome: Outcome }) {
  const [label, tone, Icon] = outcomes[outcome];
  return <span className={`outcome outcome-${tone}`}><Icon aria-hidden="true" />{label}</span>;
}
