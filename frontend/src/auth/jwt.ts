import type { Claims } from '../api/types';

export function displayClaims(token: string): Claims {
  // Display/menu hints only. Every protected call is independently verified by the server.
  const part = token.split('.')[1];
  const bytes = Uint8Array.from(atob(part.replaceAll('-', '+').replaceAll('_', '/')), character => character.charCodeAt(0));
  const payload: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (typeof payload !== 'object' || payload === null) throw new Error('Invalid token claims');
  const claims = payload as Claims;
  if (typeof claims.sub !== 'string' || typeof claims.tid !== 'string' || typeof claims.tcode !== 'string'
    || !['HR_SYSTEM', 'TRANSPORT_ADMIN', 'OPERATOR', 'EMPLOYEE'].includes(claims.role)) throw new Error('Invalid token claims');
  return claims;
}
