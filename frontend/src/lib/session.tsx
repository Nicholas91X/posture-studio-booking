"use client";

// Who is signed in, for every page (step 12). The session itself is the HttpOnly SESSION cookie, which the browser
// keeps and this code never sees (D-051): here only what the backend answers about it.
// A customer who signed in for the first time has no first and last name yet: the profile answers 409
// PROFILE_INCOMPLETE, and the sign-in page asks for them (D-050).
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";

export type Role = Schemas["SessionView"]["role"];
export type Profile = Schemas["CustomerProfile"];

export type Session =
  | { state: "loading" }
  | { state: "anonymous" }
  | { state: "signed-in"; role: Role; profile: Profile | null; profileIncomplete: boolean };

type SessionContextValue = {
  session: Session;
  refresh: () => Promise<Session>;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<SessionContextValue | null>(null);

async function readSession(): Promise<Session> {
  let role: Role;
  try {
    role = (await unwrap(api.GET("/api/public/auth/session"))).role;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      return { state: "anonymous" };
    }
    throw error;
  }
  if (role !== "CUSTOMER") {
    return { state: "signed-in", role, profile: null, profileIncomplete: false };
  }
  try {
    const profile = await unwrap(api.GET("/api/customer/profile"));
    return { state: "signed-in", role, profile, profileIncomplete: false };
  } catch (error) {
    if (error instanceof ApiError && error.is("PROFILE_INCOMPLETE")) {
      return { state: "signed-in", role, profile: null, profileIncomplete: true };
    }
    throw error;
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>({ state: "loading" });

  const refresh = useCallback(async () => {
    let next: Session;
    try {
      next = await readSession();
    } catch {
      // the backend does not answer: the pages that need a session show their own error when they call it
      next = { state: "anonymous" };
    }
    setSession(next);
    return next;
  }, []);

  // Out, and the site loaded again from the home page: nothing of the customer stays in memory, and no page for
  // customers only is left open to send to the sign-in (found by the end-to-end test of the sign-out)
  const signOut = useCallback(async () => {
    try {
      await unwrap(api.DELETE("/api/public/auth/session"));
    } finally {
      window.location.assign("/");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo(() => ({ session, refresh, signOut }), [session, refresh, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (value === null) {
    throw new Error("useSession outside SessionProvider");
  }
  return value;
}

/** A customer with a complete profile: the pages of "my sessions", credits, profile. */
export function isCustomer(session: Session): session is Extract<Session, { state: "signed-in" }> & { profile: Profile } {
  return session.state === "signed-in" && session.role === "CUSTOMER" && session.profile !== null;
}
