import { lazy, Suspense } from "react";
import {
  HashRouter,
  Navigate,
  Route,
  Routes,
  useLocation,
} from "react-router-dom";
import { RequireRole } from "./auth/RequireRole";
import { useAuth } from "./auth/AuthContext";
import { Layout } from "./components/Layout";
import { LoginPage } from "./pages/LoginPage";
import { OpsDashboardPage } from "./pages/OpsDashboardPage";
import { ReconciliationPage } from "./pages/ReconciliationPage";
import { DemoToolsPage } from "./pages/DemoToolsPage";
import { EventConsolePage } from "./pages/EventConsolePage";
import { EmployeesPage } from "./pages/EmployeesPage";
import { MePage } from "./pages/MePage";
import { RunDetailPage } from "./pages/RunDetailPage";
const StyleguidePage =
  import.meta.env.VITE_STYLEGUIDE === "true"
    ? lazy(() =>
        import("./pages/StyleguidePage").then((module) => ({
          default: module.StyleguidePage,
        })),
      )
    : null;
function Console() {
  const { session } = useAuth();
  const location = useLocation();
  if (!session)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  return (
    <Layout>
      <Suspense fallback={<p role="status">Loading screen</p>}>
        <Routes key={location.pathname}>
          <Route
            path="/"
            element={
              <Navigate
                to={
                  session.claims.role === "OPERATOR"
                    ? "/ops"
                    : session.claims.role === "HR_SYSTEM"
                      ? "/events"
                      : session.claims.role === "EMPLOYEE"
                        ? "/me"
                        : "/employees"
                }
                replace
              />
            }
          />
          <Route
            path="/ops"
            element={
              <RequireRole roles={["OPERATOR"]}>
                <OpsDashboardPage />
              </RequireRole>
            }
          />
          <Route
            path="/demo"
            element={
              <RequireRole roles={["OPERATOR"]}>
                <DemoToolsPage />
              </RequireRole>
            }
          />
          <Route
            path="/events"
            element={
              <RequireRole roles={["HR_SYSTEM"]}>
                <EventConsolePage />
              </RequireRole>
            }
          />
          <Route
            path="/employees/:employeeId?"
            element={
              <RequireRole roles={["OPERATOR", "TRANSPORT_ADMIN"]}>
                <EmployeesPage />
              </RequireRole>
            }
          />
          <Route
            path="/me"
            element={
              <RequireRole roles={["EMPLOYEE"]}>
                <MePage />
              </RequireRole>
            }
          />
          <Route
            path="/runs"
            element={
              <RequireRole roles={["OPERATOR", "HR_SYSTEM", "TRANSPORT_ADMIN"]}>
                <ReconciliationPage />
              </RequireRole>
            }
          />
          <Route
            path="/runs/:runId"
            element={
              <RequireRole roles={["OPERATOR", "HR_SYSTEM", "TRANSPORT_ADMIN"]}>
                <RunDetailPage />
              </RequireRole>
            }
          />
          {StyleguidePage && (
            <Route path="/styleguide" element={<StyleguidePage />} />
          )}
          <Route
            path="*"
            element={
              <section className="empty-state">
                <h1>Screen not found</h1>
                <p>Use the navigation to open an available screen.</p>
              </section>
            }
          />
        </Routes>
      </Suspense>
    </Layout>
  );
}
export function App() {
  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Console />} />
      </Routes>
    </HashRouter>
  );
}
