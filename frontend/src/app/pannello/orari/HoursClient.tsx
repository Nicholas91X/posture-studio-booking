"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { formatCalendarDay, formatDateTime, romeDateTime, todayInRome } from "@/lib/format";
import { useAction, useApi } from "@/lib/panel";

type Version = Schemas["ScheduleVersionView"];
type Band = Schemas["WorkingBand"];
type Weekday = Band["dayOfWeek"];
type Unavailability = Schemas["UnavailabilityView"];

const WEEKDAYS: readonly { day: Weekday; name: string }[] = [
  { day: "MONDAY", name: "Lunedì" },
  { day: "TUESDAY", name: "Martedì" },
  { day: "WEDNESDAY", name: "Mercoledì" },
  { day: "THURSDAY", name: "Giovedì" },
  { day: "FRIDAY", name: "Venerdì" },
  { day: "SATURDAY", name: "Sabato" },
  { day: "SUNDAY", name: "Domenica" },
];

const hhmm = (time: string) => time.slice(0, 5);

// Working hours in weekly versions, each from a day (D-034), and the absences (step 6). A version already started
// cannot change: a new one from today or later does. Which version is "current" and which can still be edited is
// the backend's answer (current, editable)
export function HoursClient() {
  const versions = useApi(() => unwrap(api.GET("/api/panel/schedules")), []);
  const [editing, setEditing] = useState<{ version: Version | null; bands: Band[]; validFrom: string } | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const sorted = [...(versions.data ?? [])].sort((a, b) => a.validFrom.localeCompare(b.validFrom));
  const current = sorted.find((version) => version.current) ?? null;

  return (
    <>
      <Link href="/pannello/altro" className="back">
        ‹ Altro
      </Link>
      <h1>Orari e assenze</h1>
      <h2>Orari di lavoro</h2>
      <p className="small muted">
        Ogni versione è una settimana tipo che vale da un giorno in poi, finché non ne inizia un&apos;altra. Le sedute già
        prenotate non cambiano: quelle rimaste fuori orario compaiono segnalate nell&apos;agenda.
      </p>
      {saved !== null ? (
        <Notice tone="success">
          <p>{saved}</p>
        </Notice>
      ) : null}
      <ErrorNotice error={versions.error} />
      {versions.loading ? <Loading /> : null}
      {versions.data?.length === 0 ? <p className="muted">Nessun orario: finché non ne aggiungi uno, i clienti non vedono orari liberi.</p> : null}
      {sorted.map((version) => (
        <VersionCard
          key={version.id}
          version={version}
          onEdit={() => setEditing({ version, bands: version.bands, validFrom: version.validFrom })}
          onDeleted={() => {
            versions.reload();
            setSaved("Versione eliminata.");
          }}
        />
      ))}
      <button
        type="button"
        className="button button-block"
        onClick={() => setEditing({ version: null, bands: current?.bands ?? [], validFrom: todayInRome() })}
      >
        Nuova versione degli orari
      </button>
      {editing !== null ? (
        <VersionEditor
          {...editing}
          onClose={() => setEditing(null)}
          onSaved={(version) => {
            setEditing(null);
            versions.reload();
            setSaved(`Orari salvati: valgono dal ${formatCalendarDay(version.validFrom)}.`);
          }}
        />
      ) : null}

      <Absences />
    </>
  );
}

function VersionCard({ version, onEdit, onDeleted }: { version: Version; onEdit: () => void; onDeleted: () => void }) {
  const action = useAction();
  return (
    <div className="card stack">
      <div className="spread">
        <h3>Dal {formatCalendarDay(version.validFrom)}</h3>
        {version.current ? <span className="badge badge-success">In vigore</span> : null}
      </div>
      <dl className="bands">
        {WEEKDAYS.map(({ day, name }) => {
          const bands = version.bands.filter((band) => band.dayOfWeek === day);
          return (
            <div key={day}>
              <dt>{name}</dt>
              <dd>{bands.length === 0 ? "—" : bands.map((band) => `${hhmm(band.startTime)}–${hhmm(band.endTime)}`).join(", ")}</dd>
            </div>
          );
        })}
      </dl>
      {version.editable ? (
        <>
          <ErrorNotice error={action.error} />
          <div className="row">
            <button type="button" className="button button-secondary" onClick={onEdit}>
              Modifica
            </button>
            <button
              type="button"
              className="button button-secondary"
              disabled={action.running}
              onClick={() =>
                void action.run(async () => {
                  await unwrap(api.DELETE("/api/panel/schedules/{id}", { params: { path: { id: version.id } } }));
                  onDeleted();
                })
              }
            >
              Elimina
            </button>
          </div>
        </>
      ) : (
        <p className="small muted">Già iniziata: non si modifica più. Per cambiare, crea una versione nuova.</p>
      )}
    </div>
  );
}

function VersionEditor({
  version,
  bands: startBands,
  validFrom: startFrom,
  onClose,
  onSaved,
}: {
  version: Version | null;
  bands: Band[];
  validFrom: string;
  onClose: () => void;
  onSaved: (version: Version) => void;
}) {
  const [validFrom, setValidFrom] = useState(startFrom);
  const [bands, setBands] = useState<Band[]>(startBands.map((band) => ({ ...band, startTime: hhmm(band.startTime), endTime: hhmm(band.endTime) })));
  const action = useAction();

  const change = (index: number, changes: Partial<Band>) =>
    setBands((list) => list.map((band, at) => (at === index ? { ...band, ...changes } : band)));

  const save = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () => {
      const body = { validFrom, bands };
      const answer =
        version === null
          ? await unwrap(api.POST("/api/panel/schedules", { body }))
          : await unwrap(api.PUT("/api/panel/schedules/{id}", { params: { path: { id: version.id } }, body }));
      onSaved(answer);
    });
  };

  return (
    <Dialog title={version === null ? "Nuova versione degli orari" : "Modifica gli orari"} onClose={onClose}>
      <form className="stack" onSubmit={save}>
        <div className="field">
          <label htmlFor="validFrom">Vale dal giorno</label>
          <input id="validFrom" type="date" className="input" required min={todayInRome()} value={validFrom} onChange={(event) => setValidFrom(event.target.value)} />
        </div>
        {WEEKDAYS.map(({ day, name }) => (
          <fieldset key={day} className="day-bands">
            <legend>{name}</legend>
            {bands.map((band, index) =>
              band.dayOfWeek === day ? (
                <div key={index} className="row">
                  <input
                    type="time"
                    className="input input-time"
                    aria-label={`${name}, inizio`}
                    required
                    step={300}
                    value={band.startTime}
                    onChange={(event) => change(index, { startTime: event.target.value })}
                  />
                  <span aria-hidden="true">–</span>
                  <input
                    type="time"
                    className="input input-time"
                    aria-label={`${name}, fine`}
                    required
                    step={300}
                    value={band.endTime}
                    onChange={(event) => change(index, { endTime: event.target.value })}
                  />
                  <button
                    type="button"
                    className="link-button"
                    aria-label={`Togli la fascia di ${name}`}
                    onClick={() => setBands((list) => list.filter((_, at) => at !== index))}
                  >
                    Togli
                  </button>
                </div>
              ) : null,
            )}
            <button
              type="button"
              className="link-button small"
              onClick={() => setBands((list) => [...list, { dayOfWeek: day, startTime: "09:00", endTime: "13:00" }])}
            >
              + Aggiungi una fascia
            </button>
          </fieldset>
        ))}
        <ErrorNotice error={action.error} />
        {action.error instanceof ApiError && action.error.status === 400 ? (
          <p className="small">Controlla che in ogni fascia l&apos;inizio venga prima della fine, e che le fasce dello stesso giorno non si sovrappongano.</p>
        ) : null}
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running}>
            {action.running ? "Salvataggio…" : "Salva"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}

// Holidays and absences: no free slots for the customers in between; the bookings already made stay (step 6)
function Absences() {
  const list = useApi(() => unwrap(api.GET("/api/panel/unavailabilities")), []);
  const [fromDay, setFromDay] = useState(todayInRome());
  const [fromTime, setFromTime] = useState("00:00");
  const [toDay, setToDay] = useState(todayInRome());
  const [toTime, setToTime] = useState("23:55");
  const [reason, setReason] = useState("");
  const action = useAction();
  const sorted: Unavailability[] = [...(list.data ?? [])].sort((a, b) => a.startsAt.localeCompare(b.startsAt));

  const add = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () => {
      await unwrap(
        api.POST("/api/panel/unavailabilities", {
          body: {
            startsAt: romeDateTime(fromDay, fromTime),
            endsAt: romeDateTime(toDay, toTime),
            ...(reason.trim() === "" ? {} : { reason: reason.trim() }),
          },
        }),
      );
      setReason("");
      list.reload();
    });
  };

  return (
    <>
      <h2>Assenze</h2>
      <ErrorNotice error={list.error} />
      {list.loading ? <Loading /> : null}
      {list.data?.length === 0 ? <p className="muted">Nessuna assenza.</p> : null}
      {sorted.map((absence) => (
        <AbsenceRow key={absence.id} absence={absence} onDeleted={list.reload} />
      ))}
      <form className="card stack" onSubmit={add}>
        <h3>Aggiungi un&apos;assenza</h3>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="fromDay">Dal giorno</label>
            <input id="fromDay" type="date" className="input" required value={fromDay} onChange={(event) => setFromDay(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="fromTime">dalle</label>
            <input id="fromTime" type="time" className="input" required step={300} value={fromTime} onChange={(event) => setFromTime(event.target.value)} />
          </div>
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="toDay">Al giorno</label>
            <input id="toDay" type="date" className="input" required value={toDay} onChange={(event) => setToDay(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="toTime">alle</label>
            <input id="toTime" type="time" className="input" required step={300} value={toTime} onChange={(event) => setToTime(event.target.value)} />
          </div>
        </div>
        <div className="field">
          <label htmlFor="reason">Motivo (facoltativo, lo vedi solo tu)</label>
          <input id="reason" className="input" maxLength={200} value={reason} onChange={(event) => setReason(event.target.value)} />
        </div>
        <ErrorNotice error={action.error} />
        <button type="submit" className="button button-block" disabled={action.running}>
          {action.running ? "Salvataggio…" : "Aggiungi l'assenza"}
        </button>
      </form>
    </>
  );
}

function AbsenceRow({ absence, onDeleted }: { absence: Unavailability; onDeleted: () => void }) {
  const action = useAction();
  return (
    <div className="card stack">
      <p>
        Dal <strong>{formatDateTime(absence.startsAt)}</strong>
        <br />
        al <strong>{formatDateTime(absence.endsAt)}</strong>
      </p>
      {absence.reason !== null ? <p className="small muted">{absence.reason}</p> : null}
      <ErrorNotice error={action.error} />
      <button
        type="button"
        className="button button-secondary"
        disabled={action.running}
        onClick={() =>
          void action.run(async () => {
            await unwrap(api.DELETE("/api/panel/unavailabilities/{id}", { params: { path: { id: absence.id } } }));
            onDeleted();
          })
        }
      >
        Elimina
      </button>
    </div>
  );
}
