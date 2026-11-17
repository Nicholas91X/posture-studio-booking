"use client";

import Link from "next/link";
import { api, unwrap } from "@/api/client";
import { ErrorNotice, Loading } from "@/components/Notice";
import { PanelPage } from "@/components/panel/PanelPage";
import { fullName } from "@/lib/format";
import { useApi } from "@/lib/panel";

// The payment accounts configured on the server (D-057, D-060) and who is assigned to each. An account is added with
// a restart (its keys are environment variables); the assignment changes from each practitioner's page
export default function AccountsPage() {
  return (
    <PanelPage role="ADMIN">
      <Accounts />
    </PanelPage>
  );
}

function Accounts() {
  const accounts = useApi(() => unwrap(api.GET("/api/admin/payment-accounts")), []);
  const practitioners = useApi(() => unwrap(api.GET("/api/admin/practitioners")), []);
  const nameOf = (id: number) => {
    const found = practitioners.data?.find((practitioner) => practitioner.id === id);
    return found === undefined ? `Professionista ${id}` : fullName(found);
  };
  const assigned = new Set(accounts.data?.flatMap((account) => account.practitionerIds) ?? []);
  const without = practitioners.data?.filter((practitioner) => practitioner.active && !assigned.has(practitioner.id)) ?? [];

  return (
    <>
      <h1>Conti di incasso</h1>
      <p className="small muted">
        Ogni conto è un conto Stripe configurato sul server. Più professionisti possono usare lo stesso conto. Per assegnarlo, apri la
        pagina del professionista.
      </p>
      <ErrorNotice error={accounts.error ?? practitioners.error} />
      {accounts.loading ? <Loading /> : null}
      {accounts.data?.length === 0 ? <p className="muted">Nessun conto configurato sul server.</p> : null}
      {accounts.data?.map((account) => (
        <div key={account.name} className="card stack">
          <h2>{account.name}</h2>
          {account.practitionerIds.length === 0 ? (
            <p className="small muted">Nessun professionista assegnato.</p>
          ) : (
            <ul className="plain-list">
              {account.practitionerIds.map((id) => (
                <li key={id}>
                  <Link href={`/pannello/professionisti/${id}`}>{nameOf(id)}</Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
      {without.length > 0 ? (
        <div className="card stack">
          <h2>Senza conto</h2>
          <p className="small muted">I loro clienti non possono pagare online, solo con un credito.</p>
          <ul className="plain-list">
            {without.map((practitioner) => (
              <li key={practitioner.id}>
                <Link href={`/pannello/professionisti/${practitioner.id}`}>{fullName(practitioner)}</Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </>
  );
}
