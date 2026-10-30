import type { Plan } from '../api/types';
const categories = [ ['unchanged', 'Unchanged'], ['updates', 'Updates'], ['creates', 'Creates'], ['heals', 'Heals'],
  ['advances', 'Version advances'], ['deactivations', 'Deactivations'], ['staleSkipped', 'Stale skipped'] ] as const;
export function planTotal(plan: Plan) { return categories.reduce((sum, [key]) => sum + plan[key], 0); }
export function PlanBar({ plan }: { plan: Plan }) {
  const total = planTotal(plan);
  return <div className="plan"><div className="section-heading"><h2>Change plan</h2><span>{total.toLocaleString('en-US')} compared</span></div>
    <div className="plan-bar" role="img" aria-label={categories.map(([key, label]) => `${plan[key]} ${label.toLowerCase()}`).join(', ')}>
      {categories.map(([key]) => plan[key] > 0 && <span key={key} className={`plan-${key}`} style={{ flexGrow: plan[key] }} />)}
    </div><dl className="plan-legend">{categories.map(([key, label]) => <div key={key}><dt><span className={`plan-key plan-${key}`} aria-hidden="true" />{label}</dt><dd>{plan[key].toLocaleString('en-US')}</dd></div>)}</dl>
  </div>;
}
