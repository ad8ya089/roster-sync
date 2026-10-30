import { useApiQuery } from "../api/useApiQuery";
import type { Booking } from "../api/types";
import { ErrorNotice } from "../components/ErrorNotice";
import { StatusPill } from "../components/StatusPill";
import { date } from "../components/format";
export function BookingsList({ employeeId }: { employeeId: string }) {
  const query = useApiQuery<Booking[]>(
    `/employees/${encodeURIComponent(employeeId)}/bookings`,
  );
  return (
    <section className="employee-fields">
      <h3>Bookings</h3>
      {query.error ? (
        <ErrorNotice error={query.error} />
      ) : !query.data ? (
        <p role="status">Loading bookings</p>
      ) : query.data.length === 0 ? (
        <p>No bookings.</p>
      ) : (
        <div
          className="table-scroll"
          role="region"
          aria-label="Transport bookings"
          tabIndex={0}
        >
          <table>
            <caption>Transport bookings</caption>
            <thead>
              <tr>
                <th>Pickup time</th>
                <th>Status</th>
                <th>Cancel reason</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((booking) => (
                <tr key={booking.id}>
                  <td>{date(booking.pickupAt)}</td>
                  <td>
                    <StatusPill status={booking.status} />
                  </td>
                  <td>{booking.cancelReason ?? "None"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
