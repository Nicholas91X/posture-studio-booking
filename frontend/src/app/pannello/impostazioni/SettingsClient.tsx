"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { NotificationsCard } from "@/components/panel/Notifications";
import { useAction, useApi } from "@/lib/panel";

type Settings = Schemas["PractitionerSettings"];
type NumberField = Exclude<keyof Settings, "bookingApproval">;

// The practitioner's settings (D-023, D-030, D-033, D-041): the numbers and the approval mode. Which values are allowed
// together (e.g. the time to answer within the notice) is checked by the backend, which answers 400 on the field
const FIELDS: readonly { key: NumberField; label: string; hint: string }[] = [
  { key: "slotStepMinutes", label: "Scatto della griglia (minuti)", hint: "30 o 60: ogni quanto può iniziare una seduta prenotata dai clienti." },
  { key: "minBookingNoticeHours", label: "Anticipo minimo per prenotare (ore)", hint: "I clienti prenotano solo orari che iniziano più di queste ore dopo." },
  { key: "bookingHorizonDays", label: "Orizzonte di prenotazione (giorni)", hint: "Quanto avanti nel tempo possono prenotare i clienti." },
  { key: "maxFutureBookings", label: "Sedute future per cliente", hint: "Quante sedute future può avere un cliente con te, prenotando da solo." },
  { key: "cancellationNoticeHours", label: "Preavviso di cancellazione (ore)", hint: "Fino a quante ore prima il cliente cancella senza perdere la seduta." },
  { key: "singleCreditValidityDays", label: "Validità di un credito singolo (giorni)", hint: "Per un credito restituito da una cancellazione o regalato senza data." },
  { key: "approvalWindowHours", label: "Tempo per approvare (ore)", hint: "Con l'approvazione manuale: entro quante ore rispondi a una richiesta." },
];

export function SettingsClient() {
  const loaded = useApi(() => unwrap(api.GET("/api/panel/settings")), []);
  const [values, setValues] = useState<Settings | null>(null);
  const [saved, setSaved] = useState(false);
  const action = useAction();

  useEffect(() => {
    if (loaded.data !== null) {
      setValues(loaded.data);
    }
  }, [loaded.data]);

  const fieldError = (key: string) =>
    action.error instanceof ApiError && action.error.fieldErrors.some((error) => error.field === key) ? "Valore non accettato." : null;

  const save = (event: FormEvent) => {
    event.preventDefault();
    if (values === null) {
      return;
    }
    setSaved(false);
    void action.run(async () => {
      setValues(await unwrap(api.PUT("/api/panel/settings", { body: values })));
      setSaved(true);
    });
  };

  return (
    <>
      <Link href="/pannello/altro" className="back">
        ‹ Altro
      </Link>
      <h1>Impostazioni</h1>
      <NotificationsCard />
      <ErrorNotice error={loaded.error} />
      {values === null ? (
        loaded.loading ? <Loading /> : null
      ) : (
        <form className="card stack" onSubmit={save}>
          <h2>Prenotazioni</h2>
          <fieldset className="stack">
            <legend>Approvazione delle richieste dei clienti</legend>
            <label className="check">
              <input
                type="radio"
                name="approval"
                checked={values.bookingApproval === "MANUAL"}
                onChange={() => setValues({ ...values, bookingApproval: "MANUAL" })}
              />
              <span>Manuale: approvi tu ogni richiesta</span>
            </label>
            <label className="check">
              <input
                type="radio"
                name="approval"
                checked={values.bookingApproval === "AUTOMATIC"}
                onChange={() => setValues({ ...values, bookingApproval: "AUTOMATIC" })}
              />
              <span>Automatica: confermate appena pagate</span>
            </label>
          </fieldset>
          {FIELDS.map((field) => (
            <div key={field.key} className="field">
              <label htmlFor={field.key}>{field.label}</label>
              <input
                id={field.key}
                type="number"
                inputMode="numeric"
                className="input"
                required
                min={1}
                value={values[field.key]}
                onChange={(event) => setValues({ ...values, [field.key]: Number(event.target.value) })}
                aria-invalid={fieldError(field.key) === null ? undefined : true}
              />
              <p className="hint">{fieldError(field.key) ?? field.hint}</p>
            </div>
          ))}
          <p className="small muted">Le prenotazioni già fatte non cambiano: per esempio, una richiesta in attesa tiene la sua scadenza.</p>
          <ErrorNotice error={action.error} />
          {saved ? (
            <Notice tone="success">
              <p>Impostazioni salvate.</p>
            </Notice>
          ) : null}
          <button type="submit" className="button button-block" disabled={action.running}>
            {action.running ? "Salvataggio…" : "Salva"}
          </button>
        </form>
      )}
    </>
  );
}
