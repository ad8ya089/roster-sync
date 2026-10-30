import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useApiQuery } from "../api/useApiQuery";
import { useAuth } from "../auth/AuthContext";
import type { Employee, Receipt, PageResponse } from "../api/types";
import { Drawer } from "../components/overlay/Drawer";
import { ErrorNotice } from "../components/ErrorNotice";
import { VersionTimeline } from "../components/RouteLine";
import { Pagination } from "../components/Pagination";
import { EmployeeFields } from "./EmployeeFields";
import { TransportForm } from "./TransportForm";
import { BookingsList } from "./BookingsList";

export function EmployeeDetailDrawer() {
  const { employeeId = "" } = useParams();
  const navigate = useNavigate();
  const { session } = useAuth();
  const [page, setPage] = useState(0);
  const employee = useApiQuery<Employee>(
    `/employees/${encodeURIComponent(employeeId)}`,
  );
  const history = useApiQuery<PageResponse<Receipt>>(
    `/employees/${encodeURIComponent(employeeId)}/events?page=${page}`,
    !!employee.data,
  );
  return (
    <Drawer title="Employee detail" onClose={() => navigate("/employees")}>
      {employee.error ? (
        <ErrorNotice error={employee.error} />
      ) : !employee.data ? (
        <p role="status">Loading employee</p>
      ) : (
        <>
          <EmployeeFields employee={employee.data} />
          {history.error && <ErrorNotice error={history.error} />}
          {history.isPending && <p role="status">Loading receipt history</p>}
          {history.data && (
            <section className="employee-fields">
              <VersionTimeline
                receipts={history.data.items}
                currentVersion={employee.data.hrVersion}
              />
              <p className="help">
                Loaded receipt page {page + 1}: {history.data.items.length} of{" "}
                {history.data.totalElements}. Pages are selected by version
                descending, then drawn in arrival order. Replays do not add
                receipts.
              </p>
              <Pagination
                page={page}
                size={history.data.size}
                total={history.data.totalElements}
                onPage={setPage}
              />
            </section>
          )}
          {session?.claims.role === "TRANSPORT_ADMIN" && (
            <>
              <TransportForm key={employeeId} employee={employee.data} />
              <BookingsList employeeId={employeeId} />
            </>
          )}
        </>
      )}
    </Drawer>
  );
}
