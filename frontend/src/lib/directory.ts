// Names of practitioners and services (step 12). Bookings and credits carry ids only (D-074): the names come from
// the public list and catalogs, read once per visit and shared by every page.
import { useEffect, useState } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";

export type PublicPractitioner = Schemas["PublicPractitioner"];
export type PublicCatalog = Schemas["PublicCatalog"];

let practitioners: Promise<PublicPractitioner[]> | null = null;
const catalogs = new Map<number, Promise<PublicCatalog | null>>();

export function loadPractitioners(): Promise<PublicPractitioner[]> {
  practitioners ??= unwrap(api.GET("/api/public/practitioners")).catch((error: unknown) => {
    practitioners = null;
    throw error;
  });
  return practitioners;
}

/** The catalog of a practitioner; null when there is none to show (missing or deactivated: the same 404). */
export function loadCatalog(practitionerId: number): Promise<PublicCatalog | null> {
  let catalog = catalogs.get(practitionerId);
  if (catalog === undefined) {
    catalog = unwrap(api.GET("/api/public/practitioners/{id}/catalog", { params: { path: { id: practitionerId } } })).catch(
      (error: unknown) => {
        if (error instanceof ApiError && error.status === 404) {
          return null;
        }
        catalogs.delete(practitionerId);
        throw error;
      },
    );
    catalogs.set(practitionerId, catalog);
  }
  return catalog;
}

export type Directory = {
  ready: boolean;
  practitioner: (id: number) => PublicPractitioner | undefined;
  practitionerName: (id: number) => string;
  serviceName: (practitionerId: number, serviceId: number) => string;
};

/** Names for the practitioners of a list of bookings or credits. */
export function useDirectory(practitionerIds: readonly number[]): Directory {
  const [state, setState] = useState<{
    ready: boolean;
    practitioners: PublicPractitioner[];
    catalogs: Map<number, PublicCatalog | null>;
  }>({ ready: false, practitioners: [], catalogs: new Map() });
  const key = [...new Set(practitionerIds)].sort((a, b) => a - b).join(",");

  useEffect(() => {
    let current = true;
    const ids = key === "" ? [] : key.split(",").map(Number);
    Promise.all([loadPractitioners(), Promise.all(ids.map((id) => loadCatalog(id).catch(() => null)))])
      .then(([list, found]) => {
        if (current) {
          setState({ ready: true, practitioners: list, catalogs: new Map(ids.map((id, index) => [id, found[index] ?? null])) });
        }
      })
      .catch(() => {
        if (current) {
          setState((previous) => ({ ...previous, ready: true }));
        }
      });
    return () => {
      current = false;
    };
  }, [key]);

  const practitioner = (id: number) => state.practitioners.find((one) => one.id === id);
  return {
    ready: state.ready,
    practitioner,
    practitionerName: (id) => {
      const found = practitioner(id);
      // a practitioner who left the studio is no longer in the public list (D-077)
      return found === undefined ? "Professionista non più disponibile" : `${found.firstName} ${found.lastName}`;
    },
    serviceName: (practitionerId, serviceId) =>
      state.catalogs.get(practitionerId)?.services.find((service) => service.id === serviceId)?.name ?? "Seduta",
  };
}
