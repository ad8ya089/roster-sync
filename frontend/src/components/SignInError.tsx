import { ApiError } from "../api/client";

export function SignInError({ error }: { error: unknown }) {
  const failure = error instanceof ApiError ? error : null;
  // Unknown, disabled and wrong-password accounts keep identical feedback.
  const message =
    failure?.status === 401
      ? "Username or password not recognised. Use the staff credentials provided for this demo."
      : failure?.code === "NETWORK_ERROR"
        ? "Cannot reach the application. Check your connection and try again."
        : "Sign-in is unavailable. Try again or contact the project owner.";
  return (
    <div className="signin-error">
      <p role="alert">{message}</p>
      {failure?.correlationId && (
        <details>
          <summary>Support reference</summary>
          <span className="code">{failure.correlationId}</span>
        </details>
      )}
    </div>
  );
}
