import { useApiQuery } from "../api/useApiQuery";
import type { Employee } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";
import { EmployeeFields } from "./EmployeeFields";
import { BookingsList } from "./BookingsList";
export function MePage() {
  const query = useApiQuery<Employee>("/me");
  return (
    <article className="profile-page">
      <header className="page-header">
        <h1>My profile</h1>
      </header>
      {query.error ? (
        <ErrorNotice error={query.error} />
      ) : !query.data ? (
        <p role="status">Loading your profile</p>
      ) : (
        <>
          <EmployeeFields employee={query.data} />
          <BookingsList employeeId={query.data.employeeId} />
        </>
      )}
    </article>
  );
}
