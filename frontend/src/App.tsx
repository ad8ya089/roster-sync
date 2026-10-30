import { HashRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { RequireRole } from './auth/RequireRole';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { ReviewEntryPage } from './pages/ReviewEntryPage';
function Console() {
  const { session } = useAuth(); const location = useLocation();
  if (!session) return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  return <Layout><RequireRole roles={['OPERATOR', 'HR_SYSTEM', 'TRANSPORT_ADMIN']}><ReviewEntryPage /></RequireRole></Layout>;
}
export function App() {
  return <HashRouter><Routes><Route path="/login" element={<LoginPage />} /><Route path="*" element={<Console />} /></Routes></HashRouter>;
}
