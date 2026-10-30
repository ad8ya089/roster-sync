import { createContext, useCallback, useContext, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { createClient } from '../api/client';
import type { Claims, LoginResponse } from '../api/types';
import { displayClaims } from './jwt';

interface Session { token: string; claims: Claims }
interface Auth {
  session: Session | null; notice: string; request: ReturnType<typeof createClient>;
  login: (username: string, password: string) => Promise<void>; logout: () => void;
}
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [notice, setNotice] = useState('');
  const activeToken = useRef<string | undefined>(undefined);
  const queries = useQueryClient();
  const clear = useCallback((message: string) => { activeToken.current = undefined; queries.clear(); setSession(null); setNotice(message); }, [queries]);
  const request = useCallback(async <T,>(path: string, init: RequestInit = {}) => {
    const client = createClient(session?.token, () => {
      // A previous tenant's cancelled query must not clear a newer login.
      if (activeToken.current === session?.token) clear('Session expired. Sign in again.');
    });
    return client<T>(path, init);
  }, [session?.token, clear]);
  const login = async (username: string, password: string) => {
    const result = await createClient()<LoginResponse>('/auth/token', { method: 'POST', body: JSON.stringify({ username, password }) });
    const claims = displayClaims(result.accessToken);
    queries.clear(); activeToken.current = result.accessToken; setSession({ token: result.accessToken, claims }); setNotice('');
  };
  return <Context.Provider value={{ session, notice, request, login, logout: () => clear('') }}>{children}</Context.Provider>;
}
export function useAuth() {
  const auth = useContext(Context);
  if (!auth) throw new Error('AuthProvider is required');
  return auth;
}
