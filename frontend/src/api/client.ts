import type { FieldError } from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public title: string,
    public detail: string,
    public correlationId: string,
    public errors: FieldError[] = [],
  ) {
    super(title);
  }
}

const versionKeys = new Set([
  "version",
  "hrVersion",
  "transportVersion",
  "currentVersion",
  "aggregateVersion",
  "newVersion",
]);
export function parseApiJson<T>(text: string): T {
  // Numbers have already been rounded before the reviver. Read the original token instead.
  return JSON.parse(
    text,
    (key: string, value: unknown, context?: { source?: string }) => {
      if (versionKeys.has(key) && typeof value === "number") {
        if (!context?.source)
          throw new ApiError(
            0,
            "BROWSER_UNSUPPORTED",
            "Browser update needed",
            "Use a current browser to display versions without losing precision.",
            "",
          );
        return context.source;
      }
      return value;
    },
  ) as T;
}

export function createClient(token?: string, expired?: () => void) {
  return async function request<T>(
    path: string,
    init: RequestInit = {},
    responseType: "json" | "blob" = "json",
  ): Promise<T> {
    const headers = new Headers(init.headers);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    if (typeof init.body === "string")
      headers.set("Content-Type", "application/json");
    let response: Response;
    try {
      response = await fetch(`/api/v1${path}`, {
        ...init,
        headers,
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      throw new ApiError(
        0,
        "NETWORK_ERROR",
        "Connection unavailable",
        "Check your connection and try again.",
        "",
      );
    }
    if (response.status === 204) return undefined as T;
    if (response.ok && responseType === "blob")
      return (await response.blob()) as T;
    const text = await response.text();
    if (!response.ok) {
      let problem: Partial<ApiError> = {};
      try {
        problem = parseApiJson<Partial<ApiError>>(text);
      } catch {
        /* Proxy errors may not be JSON. */
      }
      if (response.status === 401 && token) expired?.();
      throw new ApiError(
        response.status,
        problem.code ?? "REQUEST_FAILED",
        problem.title ?? "Request failed",
        response.status === 403
          ? "You don't have access to this action."
          : (problem.detail ?? "Try again or contact your operator."),
        problem.correlationId ?? response.headers.get("X-Correlation-Id") ?? "",
        problem.errors ?? [],
      );
    }
    return parseApiJson<T>(text);
  };
}
