import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import type { PageResponse, Rejection, Run } from "./types";

export const terminal = new Set(["PLANNED", "COMPLETED", "REJECTED", "FAILED"]);
export function useRun(runId: string) {
  const { request, session } = useAuth();
  return useQuery({
    queryKey: [session?.claims.tid, "run", runId],
    queryFn: ({ signal }) =>
      request<Run>(`/reconciliation/runs/${encodeURIComponent(runId)}`, {
        signal,
      }),
    enabled: Boolean(session && runId),
    refetchInterval: (query) =>
      query.state.data && !terminal.has(query.state.data.status) ? 2000 : false,
  });
}
export function useRejections(runId: string, enabled: boolean, page: number) {
  const { request, session } = useAuth();
  return useQuery({
    queryKey: [session?.claims.tid, "rejections", runId, page],
    queryFn: ({ signal }) =>
      request<PageResponse<Rejection>>(
        `/reconciliation/runs/${encodeURIComponent(runId)}/rejections?page=${page}`,
        { signal },
      ),
    enabled,
  });
}
