import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@fontsource/overpass/latin-400.css";
import "@fontsource/overpass/latin-600.css";
import "@fontsource/overpass-mono/latin-400.css";
import "./styles/index.css";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { ApiError } from "./api/client";

const queries = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (count, error) =>
        count < 1 &&
        !(error instanceof ApiError && [401, 403, 404].includes(error.status)),
      staleTime: 1000,
    },
    mutations: { retry: false },
  },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queries}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
