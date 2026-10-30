import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useApiQuery } from "../api/useApiQuery";
import type { Employee, PageResponse } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";
import { StatusPill } from "../components/StatusPill";
import { Pagination } from "../components/Pagination";
import { EmployeeDetailDrawer } from "./EmployeeDetailDrawer";

export function EmployeesPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [office, setOffice] = useState("");
  const [officeInput, setOfficeInput] = useState("");
  const [id, setId] = useState("");
  const navigate = useNavigate();
  const { employeeId } = useParams();
  const params = new URLSearchParams({ size: "50", page: String(page) });
  if (status) params.set("status", status);
  if (office) params.set("officeCode", office);
  const query = useApiQuery<PageResponse<Employee>>(`/employees?${params}`);
  return (
    <article>
      <header className="page-header">
        <h1>Employees</h1>
      </header>
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          navigate(`/employees/${encodeURIComponent(id)}`);
        }}
      >
        <div>
          <label htmlFor="employee-search">Find by employee ID</label>
          <input
            id="employee-search"
            required
            pattern="[A-Za-z0-9_-]{1,64}"
            maxLength={64}
            value={id}
            onChange={(event) => setId(event.target.value)}
          />
        </div>
        <button className="button button-secondary">Find employee</button>
      </form>
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          setOffice(officeInput);
          setPage(0);
        }}
      >
        <div>
          <label htmlFor="employee-status">Status</label>
          <select
            id="employee-status"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </div>
        <div>
          <label htmlFor="office-filter">Office code</label>
          <input
            id="office-filter"
            value={officeInput}
            pattern="[A-Z0-9-]{1,32}"
            maxLength={32}
            onChange={(event) => setOfficeInput(event.target.value)}
          />
        </div>
        <button className="button button-secondary">Filter employees</button>
      </form>
      {query.error && <ErrorNotice error={query.error} />}
      <div
        className="table-scroll"
        role="region"
        aria-label="Employee records"
        tabIndex={0}
      >
        <table>
          <caption>Tenant employees</caption>
          <thead>
            <tr>
              <th>Employee ID</th>
              <th>Name</th>
              <th>Office</th>
              <th>Shift</th>
              <th>Status</th>
              <th className="number">HR version</th>
              <th className="number">Transport version</th>
            </tr>
          </thead>
          <tbody>
            {!query.data ? (
              <tr>
                <td colSpan={7}>Loading employees</td>
              </tr>
            ) : query.data.items.length === 0 ? (
              <tr>
                <td colSpan={7}>No employees match these filters.</td>
              </tr>
            ) : (
              query.data.items.map((employee) => (
                <tr key={employee.employeeId}>
                  <td>
                    <Link
                      className="code"
                      to={`/employees/${encodeURIComponent(employee.employeeId)}`}
                    >
                      {employee.employeeId}
                    </Link>
                  </td>
                  <td>{employee.name ?? "Not set"}</td>
                  <td>{employee.officeCode ?? "Not set"}</td>
                  <td>{employee.shiftCode ?? "Not set"}</td>
                  <td>
                    <StatusPill status={employee.status} />
                  </td>
                  <td className="number code">{employee.hrVersion}</td>
                  <td className="number code">{employee.transport.version}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {query.data && (
        <Pagination
          page={page}
          size={query.data.size}
          total={query.data.totalElements}
          onPage={setPage}
        />
      )}
      {employeeId && <EmployeeDetailDrawer key={employeeId} />}
    </article>
  );
}
