import { useState } from 'react';
import { AlertCircle, Copy } from 'lucide-react';
import { ApiError } from '../api/client';

export function ErrorNotice({ error }: { error: unknown }) {
  const [copied, setCopied] = useState(false);
  const failure = error instanceof ApiError ? error : new ApiError(0, 'UNEXPECTED_ERROR', 'Request unavailable', 'Try again or contact your operator.', '');
  return <div className="error-notice" role="alert"><AlertCircle aria-hidden="true" /><div>
    <strong>{failure.title}</strong><p>{failure.detail}</p><span className="code">{failure.code}</span>
    {failure.errors.length > 0 && <ul>{failure.errors.map((field, i) => <li key={i}>{field.field}: <span className="code">{field.code}</span></li>)}</ul>}
    {failure.correlationId && <button className="button-text correlation" onClick={async () => {
      try { await navigator.clipboard.writeText(failure.correlationId); setCopied(true); } catch { setCopied(false); }
    }}><Copy aria-hidden="true" /><span>{copied ? 'Copied' : 'Copy correlation ID'}</span><span className="code">{failure.correlationId}</span></button>}
  </div></div>;
}
