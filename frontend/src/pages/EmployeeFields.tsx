import type { Employee } from "../api/types";
import { OwnerTag } from "../components/OwnerTag";
import { StatusPill } from "../components/StatusPill";
export function EmployeeFields({ employee }: { employee: Employee }) {
  return (
    <>
      <div className="section-heading">
        <span className="code">{employee.employeeId}</span>
        <StatusPill status={employee.status} />
      </div>
      <section className="employee-fields">
        <h3>
          <OwnerTag owner="HR" /> HR-owned fields
        </h3>
        <dl className="figure-list">
          {(
            [
              ["Name", employee.name],
              ["Email", employee.email],
              ["Phone", employee.phone],
              ["Manager ID", employee.managerId],
              ["Office", employee.officeCode],
              ["Shift", employee.shiftCode],
              ["HR version", employee.hrVersion],
            ] as const
          ).map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value ?? "Not set"}</dd>
            </div>
          ))}
        </dl>
      </section>
      <section className="employee-fields">
        <h3>
          <OwnerTag owner="TRANSPORT" /> Transport-owned fields
        </h3>
        <dl className="figure-list">
          <div>
            <dt>Pickup point</dt>
            <dd>{employee.transport.pickupPoint ?? "Not set"}</dd>
          </div>
          <div>
            <dt>Special assistance</dt>
            <dd>{employee.transport.specialAssistance ?? "Not set"}</dd>
          </div>
          <div>
            <dt>Transport version</dt>
            <dd>{employee.transport.version}</dd>
          </div>
        </dl>
      </section>
      <p className="help">HR updates never overwrite purple fields.</p>
    </>
  );
}
