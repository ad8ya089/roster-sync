import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";

// Tenant and path are always part of the key; login/logout also clears the cache.
export function useApiQuery<T>(
  path: string,
  enabled = true,
  interval: number | false = false,
) {
  const { request, session } = useAuth();
  return useQuery({
    queryKey: [session?.claims.tid, path],
    queryFn: ({ signal }) => request<T>(path, { signal }),
    enabled: Boolean(session) && enabled,
    refetchInterval: interval,
  });
}
