import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '');
const batches = new Counter('ingest_batches');
const events = new Counter('events_sent');
const badOutcomes = new Rate('outcome_failures');
const requestLatency = new Trend('ingest_request_latency', true);
const amortizedEventLatency = new Trend('amortized_event_latency', true);
const batchSize = Number(__ENV.BATCH_SIZE || 50);
export const options = {
  vus: Number(__ENV.LOAD_VUS || 20), duration: __ENV.LOAD_DURATION || '60s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: { http_req_failed: ['rate<0.01'], outcome_failures: ['rate<0.01'] },
};

export function setup() {
  if (![1,50].includes(batchSize)) fail('BATCH_SIZE must be 1 or 50');
  if (!__ENV.DEMO_PASSWORD) fail('Set DEMO_PASSWORD in .env or the environment');
  const response = http.post(`${base}/api/v1/auth/token`, JSON.stringify({username:'acme.hr', password:__ENV.DEMO_PASSWORD}),
    {headers:{'Content-Type':'application/json'}, tags:{endpoint:'login'}});
  if (response.status !== 200) fail(`Login failed: HTTP ${response.status}`);
  return {token:response.json('accessToken'), run:__ENV.RUN_ID || String(Date.now())};
}

export default function(data) {
  const version = __ITER * 2 + 2;
  const payload = [];
  const fixture = (slot,iteration) => ({
    type:iteration === 0 ? 'CREATE' : 'UPDATE', employeeId:`K6_${data.run}_${__VU}_${slot}`,
    version:iteration * 2 + 2, name:'Load Fixture', email:'load@example.test', officeCode:'BLR-1',
    shiftCode:iteration % 2 ? 'S2' : 'S1', status:'ACTIVE', occurredAt:new Date().toISOString(),
  });
  let expected;
  if (batchSize === 1) {
    // Eighteen fresh, one replay and one stale request per 20 iterations per VU.
    const position=__ITER % 20;
    const item=fixture(0,position >= 18 ? __ITER-position+17 : __ITER);
    if(position===19)item.version--;
    payload.push(item);
    expected={applied:position<18?1:0,duplicate:position===18?1:0,stale:position===19?1:0};
  } else {
  for (let slot = 0; slot < 45; slot++) payload.push(fixture(slot,__ITER));
  // Alternate 2/3 duplicates and 3/2 stale records: 90% fresh, 5% each over two batches.
  // Stale versions remain positive, even in the first batch; each VU owns separate IDs.
  const duplicateCount = __ITER % 2 ? 3 : 2;
  for (let slot = 0; slot < duplicateCount; slot++) payload.push({...payload[slot]});
  for (let slot = 0; slot < 5 - duplicateCount; slot++) payload.push({...payload[slot + 5], version:version - 1});
  expected={applied:45,duplicate:duplicateCount,stale:5-duplicateCount};
  }
  const response = http.post(`${base}/api/v1/hr/events`, JSON.stringify({events:payload}),
    {headers:{'Content-Type':'application/json', Authorization:`Bearer ${data.token}`}, tags:{endpoint:'ingest'}, timeout:'60s'});
  batches.add(1);events.add(payload.length);
  requestLatency.add(response.timings.duration);
  // Amortized batch cost is not an individual employee's response latency.
  amortizedEventLatency.add(response.timings.duration / batchSize);
  let correct = false;
  if (response.status === 200) {
    const summary = response.json('summary');
    correct = summary.applied === expected.applied && summary.duplicate === expected.duplicate && summary.stale === expected.stale
      && summary.rejected === 0 && summary.conflict === 0 && summary.retryableError === 0;
  }
  check(response, {'HTTP 200 and exact outcome mix':() => correct});badOutcomes.add(!correct);
}

export function handleSummary(data) {
  const values = name => data.metrics[name]?.values || {};
  const duration = values('ingest_request_latency');
  const perEvent = values('amortized_event_latency');
  const result = {
    baseUrl:base, vus:options.vus, duration:options.duration, batchSize,
    batches:values('ingest_batches'), events:values('events_sent'),
    p50Ms:duration.med, p99Ms:duration['p(99)'], maxMs:duration.max,
    perEventP50Ms:perEvent.med, perEventP99Ms:perEvent['p(99)'],
    httpFailureRate:values('http_req_failed').rate, outcomeFailureRate:values('outcome_failures').rate,
    k6:data,
  };
  return {
    '/scripts/results/summary.json':JSON.stringify(result, null, 2),
    stdout:`\nIngest: ${result.batches.rate?.toFixed(2)} batches/s, ${result.events.rate?.toFixed(2)} events/s\n`
      + `HTTP p50 ${result.p50Ms?.toFixed(2)} ms, p99 ${result.p99Ms?.toFixed(2)} ms\n`
      + `${batchSize===1?'Single-event':'Amortized per-event'} p50 ${result.perEventP50Ms?.toFixed(2)} ms, p99 ${result.perEventP99Ms?.toFixed(2)} ms\n`
      + `HTTP failures ${result.httpFailureRate}, outcome failures ${result.outcomeFailureRate}\n`,
  };
}
