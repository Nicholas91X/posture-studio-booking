"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { loadCatalog } from "@/lib/directory";
import { creditsText, formatCalendarDay, formatDateTime, fullName } from "@/lib/format";
import { badgeClass, PAID_WITH } from "@/lib/labels";
import { PANEL_BOOKING_STATUS, useAction, useApi } from "@/lib/panel";

type Practitioner = Schemas["AdminPractitionerView"];
type AdminBooking = Schemas["AdminBookingView"];
type Outstanding = Schemas["OutstandingCredit"];

// One practitioner, for the admin: names, payment account (D-057), the sessions that stop a deactivation and their
// cancellation on behalf of the studio, the deactivation itself (D-077, D-081). The rules (409 with future sessions,
// what the customer keeps) are the backend's; the page shows them before and after
export function PractitionerAdminClient() {
  const id = Number(useParams<{ id: string }>().id);
  const practitioner = useApi(() => unwrap(api.GET("/api/admin/practitioners/{id}", { params: { path: { id } } })), [id]);
  const bookings = useApi(
    () => unwrap(api.GET("/api/admin/practitioners/{practitionerId}/bookings", { params: { path: { practitionerId: id } } })),
    [id],
  );
  // the names of the services come from the public catalog: one no longer on sale is called "Seduta" (D-085)
  const catalog = useApi(() => loadCatalog(id).catch(() => null), [id]);
  const serviceName = (serviceId: number) => catalog.data?.services.find((service) => service.id === serviceId)?.name ?? "Seduta";
  const [outstanding, setOutstanding] = useState<Outstanding[] | null>(null);

  if (practitioner.loading) {
    return <Loading />;
  }
  if (practitioner.data === null) {
    return (
      <>
        <ErrorNotice error={practitioner.error} />
        <Link href="/pannello/professionisti">‹ Professionisti</Link>
      </>
    );
  }
  const current = practitioner.data;

  return (
    <>
      <Link href="/pannello/professionisti" className="back">
        ‹ Professionisti
      </Link>
      <div className="spread">
        <h1>{fullName(current)}</h1>
        <span className={current.active ? "badge badge-success" : "badge"}>{current.active ? "Attivo" : "Disattivato"}</span>
      </div>
      <p className="muted">{current.email}</p>

      {outstanding !== null ? <OutstandingCredits credits={outstanding} /> : null}

      <NamesCard practitioner={current} onSaved={(next) => practitioner.set(next)} />
      <AccountCard practitionerId={id} />

      <h2>Sedute future</h2>
      <p className="small muted">Le sedute confermate e le richieste in attesa: finché ce ne sono, il professionista non si può disattivare.</p>
      <ErrorNotice error={bookings.error} />
      {bookings.loading ? <Loading /> : null}
      {bookings.data?.length === 0 ? <p className="muted">Nessuna seduta futura.</p> : null}
      {bookings.data?.map((booking) => (
        <AdminBookingCard key={booking.id} practitionerId={id} booking={booking} serviceName={serviceName(booking.serviceId)} onCancelled={bookings.reload} />
      ))}

      <ActiveCard
        practitioner={current}
        onChanged={(next, credits) => {
          practitioner.set(next);
          setOutstanding(credits);
          bookings.reload();
        }}
        onRefused={bookings.reload}
      />
    </>
  );
}

function NamesCard({ practitioner, onSaved }: { practitioner: Practitioner; onSaved: (next: Practitioner) => void }) {
  const [firstName, setFirstName] = useState(practitioner.firstName);
  const [lastName, setLastName] = useState(practitioner.lastName);
  const [saved, setSaved] = useState(false);
  const action = useAction();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSaved(false);
    void action.run(async () => {
      onSaved(
        await unwrap(
          api.PUT("/api/admin/practitioners/{id}", { params: { path: { id: practitioner.id } }, body: { firstName: firstName.trim(), lastName: lastName.trim() } }),
        ),
      );
      setSaved(true);
    });
  };
  return (
    <form className="card stack" onSubmit={submit}>
      <h2>Nome</h2>
      <div className="form-pair">
        <div className="field">
          <label htmlFor="firstName">Nome</label>
          <input id="firstName" className="input" required maxLength={100} value={firstName} onChange={(event) => setFirstName(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="lastName">Cognome</label>
          <input id="lastName" className="input" required maxLength={100} value={lastName} onChange={(event) => setLastName(event.target.value)} />
        </div>
      </div>
      <ErrorNotice error={action.error} />
      {saved ? (
        <Notice tone="success">
          <p>Salvato.</p>
        </Notice>
      ) : null}
      <button type="submit" className="button button-secondary" disabled={action.running}>
        Salva il nome
      </button>
    </form>
  );
}

// The payment account (D-057, D-060): one of those configured on the server. Changing it does not touch the
// payments in progress, which stay on the account they were made on
function AccountCard({ practitionerId }: { practitionerId: number }) {
  const accounts = useApi(() => unwrap(api.GET("/api/admin/payment-accounts")), []);
  const assigned = useApi(
    // wrapped: useApi reads a bare null as "not loaded yet", and "no account" is an answer
    () =>
      unwrap(api.GET("/api/admin/practitioners/{id}/payment-account", { params: { path: { id: practitionerId } } })).then(
        (found) => ({ account: found as Schemas["AssignedAccountView"] | null }),
        (error: unknown) => {
          // no account yet: a 404, which here is an answer
          if (error instanceof ApiError && error.status === 404) {
            return { account: null };
          }
          throw error;
        },
      ),
    [practitionerId],
  );
  const [chosen, setChosen] = useState("");
  const action = useAction();

  useEffect(() => {
    if (assigned.data?.account != null) {
      setChosen(assigned.data.account.account);
    } else if (accounts.data !== null && accounts.data.length > 0) {
      setChosen((value) => (value === "" ? (accounts.data?.[0]?.name ?? "") : value));
    }
  }, [assigned.data, accounts.data]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () => {
      assigned.set({ account: await unwrap(api.PUT("/api/admin/practitioners/{id}/payment-account", { params: { path: { id: practitionerId } }, body: { account: chosen } })) });
    });
  };

  return (
    <form className="card stack" onSubmit={submit}>
      <h2>Conto di incasso</h2>
      <ErrorNotice error={accounts.error ?? assigned.error} />
      {assigned.loading || accounts.loading ? <Loading /> : null}
      {assigned.data !== null && assigned.data.account === null ? (
        <p className="small">Nessun conto: i clienti non possono pagare online questo professionista, solo con un credito.</p>
      ) : null}
      {assigned.data?.account != null ? (
        <p className="small">
          Assegnato: <strong>{assigned.data.account.account}</strong>, dal {formatDateTime(assigned.data.account.assignedAt)}.
        </p>
      ) : null}
      {accounts.data?.length === 0 ? (
        <p className="small muted">Sul server non è configurato nessun conto (variabile PAYMENT_ACCOUNTS).</p>
      ) : null}
      {accounts.data !== null && accounts.data.length > 0 ? (
        <>
          <div className="field">
            <label htmlFor="account">Conto</label>
            <select id="account" className="input" value={chosen} onChange={(event) => setChosen(event.target.value)}>
              {accounts.data.map((account) => (
                <option key={account.name} value={account.name}>
                  {account.name}
                </option>
              ))}
            </select>
          </div>
          <p className="hint">Cambiare conto non tocca i pagamenti già avviati: restano sul conto su cui sono stati fatti.</p>
          <button type="submit" className="button button-secondary" disabled={action.running || chosen === "" || chosen === assigned.data?.account?.account}>
            Assegna il conto
          </button>
        </>
      ) : null}
    </form>
  );
}

function AdminBookingCard({
  practitionerId,
  booking,
  serviceName,
  onCancelled,
}: {
  practitionerId: number;
  booking: AdminBooking;
  serviceName: string;
  onCancelled: () => void;
}) {
  const status = PANEL_BOOKING_STATUS[booking.status];
  const [asking, setAsking] = useState(false);
  const action = useAction();
  const cancel = () =>
    void action.run(async () => {
      await unwrap(
        api.POST("/api/admin/practitioners/{practitionerId}/bookings/{bookingId}/cancel", {
          params: { path: { practitionerId, bookingId: booking.id } },
        }),
      );
      setAsking(false);
      onCancelled();
    });
  return (
    <div className="card stack">
      <div className="spread">
        <div>
          <p>
            <strong>{formatDateTime(booking.startsAt)}</strong>
          </p>
          <p className="small">
            {serviceName} · {PAID_WITH[booking.paidWith]}
          </p>
        </div>
        <span className={badgeClass(status.tone)}>{status.text}</span>
      </div>
      <button type="button" className="button button-secondary" onClick={() => setAsking(true)}>
        Cancella per conto dello studio
      </button>
      {asking ? (
        <Dialog title="Cancellare la seduta?" onClose={() => setAsking(false)}>
          <p>{formatDateTime(booking.startsAt)}</p>
          <Notice>
            <p>
              È una cancellazione dello studio: il cliente non perde niente (il credito torna, la carta si sblocca, o non aveva ancora
              pagato) e riceve un&apos;email. Resta registrato che l&apos;ha fatta l&apos;amministrazione.
            </p>
          </Notice>
          <ErrorNotice error={action.error} />
          <div className="dialog-actions">
            <button type="button" className="button button-danger" disabled={action.running} onClick={cancel}>
              {action.running ? "Invio…" : "Sì, cancella"}
            </button>
            <button type="button" className="button button-secondary" onClick={() => setAsking(false)}>
              No
            </button>
          </div>
        </Dialog>
      ) : null}
    </div>
  );
}

function ActiveCard({
  practitioner,
  onChanged,
  onRefused,
}: {
  practitioner: Practitioner;
  onChanged: (next: Practitioner, outstanding: Outstanding[] | null) => void;
  onRefused: () => void;
}) {
  const [asking, setAsking] = useState(false);
  const action = useAction();
  const path = { params: { path: { id: practitioner.id } } };

  const deactivate = () =>
    void action.run(async () => {
      try {
        const result = await unwrap(api.POST("/api/admin/practitioners/{id}/deactivate", path));
        setAsking(false);
        onChanged(result.practitioner, result.outstandingCredits);
      } catch (error) {
        // the sessions listed above may have changed meanwhile: shown again, with the reason
        if (error instanceof ApiError && error.is("PRACTITIONER_HAS_FUTURE_BOOKINGS")) {
          onRefused();
        }
        throw error;
      }
    });

  if (!practitioner.active) {
    return (
      <div className="card stack">
        <h2>Riattiva</h2>
        <p className="small muted">Torna nell&apos;elenco dei professionisti prenotabili e può accedere di nuovo al pannello.</p>
        <ErrorNotice error={action.error} />
        <button
          type="button"
          className="button button-secondary"
          disabled={action.running}
          onClick={() => void action.run(async () => onChanged(await unwrap(api.POST("/api/admin/practitioners/{id}/activate", path)), null))}
        >
          Riattiva il professionista
        </button>
      </div>
    );
  }

  return (
    <div className="card stack">
      <h2>Disattiva</h2>
      <p className="small muted">
        Non sarà più prenotabile e non potrà più accedere. Si può solo senza sedute future: prima vanno cancellate, qui sopra.
      </p>
      <ErrorNotice error={asking ? null : action.error} />
      <button type="button" className="button button-danger" onClick={() => setAsking(true)}>
        Disattiva il professionista
      </button>
      {asking ? (
        <Dialog title="Disattivare il professionista?" onClose={() => setAsking(false)}>
          <p>
            {fullName(practitioner)} non sarà più prenotabile e non potrà più accedere al pannello. I crediti dei suoi clienti restano,
            ma non si possono più usare: l&apos;elenco compare dopo la disattivazione.
          </p>
          <ErrorNotice error={action.error} />
          <div className="dialog-actions">
            <button type="button" className="button button-danger" disabled={action.running} onClick={deactivate}>
              {action.running ? "Invio…" : "Sì, disattiva"}
            </button>
            <button type="button" className="button button-secondary" onClick={() => setAsking(false)}>
              No
            </button>
          </div>
        </Dialog>
      ) : null}
    </div>
  );
}

// After a deactivation (D-077): the credits still valid of the practitioner's customers, as a warning for the admin.
// What to do with them (refunds by hand, agreements) is decided outside the system
function OutstandingCredits({ credits }: { credits: Outstanding[] }) {
  return (
    <Notice tone={credits.length === 0 ? "success" : "warning"}>
      <p>
        <strong>Professionista disattivato.</strong>{" "}
        {credits.length === 0
          ? "Nessun cliente aveva crediti ancora validi."
          : "Questi clienti hanno ancora crediti validi, che ora non possono usare. Cosa farne (un rimborso a mano, un accordo) si decide fuori dal sistema."}
      </p>
      {credits.length > 0 ? (
        <ul className="history">
          {credits.map((credit) => (
            <li key={credit.lotId}>
              <p className="small">
                <strong>
                  {credit.firstName} {credit.lastName}
                </strong>{" "}
                · {credit.email}
              </p>
              <p className="small">
                {creditsText(Number(credit.balance))}, validi fino al {formatCalendarDay(credit.validUntil)}
              </p>
            </li>
          ))}
        </ul>
      ) : null}
    </Notice>
  );
}
