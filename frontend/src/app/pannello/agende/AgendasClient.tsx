"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { api, unwrap, type Schemas } from "@/api/client";
import { PeriodNav } from "@/components/calendar/PeriodNav";
import { WeekGrid, type GridColumn, type GridEvent, type GridShade } from "@/components/calendar/WeekGrid";
import { ErrorNotice, Loading } from "@/components/Notice";
import { isDay, mondayOf, type Day } from "@/lib/calendar";
import { addDays, formatDayWithWeekday, formatTime, fullName, romeDay, todayInRome } from "@/lib/format";
import { useApi } from "@/lib/panel";

type Agenda = Schemas["AgendaView"];
type Span = Schemas["TimeSpan"];

const DURATIONS = [30, 60, 90, 120] as const;
const TOGETHER = "insieme";

// The agendas compared (D-091): every active practitioner only as busy and free, with working hours and absences,
// and the windows in which the chosen ones are free together. Day, practitioners and duration live in the address
// (D-087): back, reload and a saved link keep them. What is busy and what is free together is the backend's answer
export function AgendasClient() {
  const query = useSearchParams();
  const router = useRouter();
  const today = todayInRome();
  const dayParam = query.get("giorno");
  const day: Day = isDay(dayParam) ? dayParam : today;
  const monday = mondayOf(day);
  const sunday = addDays(monday, 6);
  const durationParam = Number(query.get("durata"));
  const duration = (DURATIONS as readonly number[]).includes(durationParam) ? durationParam : 60;

  const agendas = useApi(() => unwrap(api.GET("/api/panel/agendas", { params: { query: { from: monday, to: sunday } } })), [monday]);
  const all = agendas.data ?? [];
  const chosenParam = query.get("chi");
  const chosen = chosenParam === null ? all.map((agenda) => agenda.practitionerId) : chosenParam.split(",").map(Number).filter((id) => all.some((agenda) => agenda.practitionerId === id));
  const chosenKey = chosen.join(",");

  const together = useApi(
    () =>
      chosen.length === 0
        ? Promise.resolve([] as Span[])
        : unwrap(
            api.GET("/api/panel/agendas/free-together", {
              params: { query: { from: monday, to: sunday, durationMinutes: duration, practitionerIds: chosen } },
            }),
          ),
    [monday, duration, chosenKey, agendas.data !== null],
  );

  const link = (changes: { giorno?: Day; chi?: number[]; durata?: number }) => {
    const params = new URLSearchParams();
    const nextDay = changes.giorno ?? day;
    if (nextDay !== today) {
      params.set("giorno", nextDay);
    }
    const nextChosen = changes.chi ?? (chosenParam === null ? null : chosen);
    if (nextChosen !== null) {
      params.set("chi", nextChosen.join(","));
    }
    const nextDuration = changes.durata ?? duration;
    if (nextDuration !== 60) {
      params.set("durata", String(nextDuration));
    }
    const text = params.toString();
    return text === "" ? "/pannello/agende" : `/pannello/agende?${text}`;
  };

  const toggle = (id: number) => {
    const next = chosen.includes(id) ? chosen.filter((one) => one !== id) : [...chosen, id];
    router.replace(link({ chi: next }), { scroll: false });
  };

  const shown = all.filter((agenda) => chosen.includes(agenda.practitionerId));
  const windowsOfDay = (together.data ?? []).filter((span) => romeDay(span.start) === day);

  return (
    <>
      <Link href="/pannello" className="back">
        ‹ Agenda
      </Link>
      <h1>Agende a confronto</h1>
      <p className="small muted">
        Degli altri professionisti vedi solo quando sono occupati, i loro orari e le assenze: mai chi né che cosa. Le sedute di
        coppia si prenotano ognuna nella propria agenda, da &laquo;Prenota&raquo;.
      </p>

      <ErrorNotice error={agendas.error} />
      {agendas.loading ? <Loading /> : null}

      {agendas.data !== null ? (
        <>
          <fieldset className="card stack">
            <legend>Chi confrontare</legend>
            <div className="row">
              {all.map((agenda) => (
                <label key={agenda.practitionerId} className="check chip">
                  <input type="checkbox" checked={chosen.includes(agenda.practitionerId)} onChange={() => toggle(agenda.practitionerId)} />
                  <span>{fullName(agenda)}</span>
                </label>
              ))}
            </div>
            <div className="field">
              <label htmlFor="duration">Durata della seduta insieme</label>
              <select
                id="duration"
                className="input"
                value={duration}
                onChange={(event) => router.replace(link({ durata: Number(event.target.value) }), { scroll: false })}
              >
                {DURATIONS.map((minutes) => (
                  <option key={minutes} value={minutes}>
                    {minutes} minuti
                  </option>
                ))}
              </select>
            </div>
          </fieldset>

          <PeriodNav
            unit="giorno"
            title={formatDayWithWeekday(day)}
            previous={link({ giorno: addDays(day, -1) })}
            next={link({ giorno: addDays(day, 1) })}
            today={day === today ? null : link({ giorno: today })}
          />
          {shown.length === 0 ? (
            <p className="muted">Scegli almeno un professionista.</p>
          ) : (
            <DayCompared day={day} today={today} agendas={shown} together={windowsOfDay} />
          )}
          <p className="small muted legend">
            <span className="legend-swatch shade-work" /> al lavoro <span className="legend-swatch shade-away" /> assente{" "}
            <span className="legend-swatch event-neutral" /> occupato <span className="legend-swatch shade-together" /> liberi insieme
          </p>

          <h2>Liberi insieme questa settimana</h2>
          <ErrorNotice error={together.error} />
          {together.loading ? <Loading /> : null}
          {together.data?.length === 0 ? <p className="muted">Nessuna finestra di {duration} minuti in cui siano tutti liberi.</p> : null}
          <ul className="plain-list">
            {groupByDay(together.data ?? []).map(([windowDay, spans]) => (
              <li key={windowDay}>
                <Link href={link({ giorno: windowDay })}>{formatDayWithWeekday(windowDay)}</Link>:{" "}
                {spans.map((span) => `${formatTime(span.start)}–${formatTime(span.end)}`).join(", ")}
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </>
  );
}

// One column per practitioner on the day, and one more for the windows free together
function DayCompared({ day, today, agendas, together }: { day: Day; today: Day; agendas: Agenda[]; together: Span[] }) {
  const columns: GridColumn[] = [
    ...agendas.map((agenda) => ({
      key: String(agenda.practitionerId),
      day,
      label: fullName(agenda),
      head: <span className="column-name">{agenda.firstName}</span>,
    })),
    { key: TOGETHER, day, label: "Liberi insieme", head: <span className="column-name">Insieme</span> },
  ];
  const onDay = (span: Span) => romeDay(span.start) === day;
  const events: GridEvent[] = agendas.flatMap((agenda) =>
    agenda.busy.filter(onDay).map((span, index) => ({
      key: `${agenda.practitionerId}-busy-${index}`,
      startsAt: span.start,
      endsAt: span.end,
      tone: "neutral" as const,
      what: "Occupato",
      status: "occupato",
      column: String(agenda.practitionerId),
    })),
  );
  const shades: GridShade[] = [
    ...agendas.flatMap((agenda) => [
      ...agenda.working.filter(onDay).map((span, index) => ({
        key: `${agenda.practitionerId}-work-${index}`,
        column: String(agenda.practitionerId),
        startsAt: span.start,
        endsAt: span.end,
        kind: "work" as const,
      })),
      ...agenda.absences.filter(onDay).map((span, index) => ({
        key: `${agenda.practitionerId}-away-${index}`,
        column: String(agenda.practitionerId),
        startsAt: span.start,
        endsAt: span.end,
        kind: "away" as const,
      })),
    ]),
    ...together.map((span, index) => ({ key: `together-${index}`, column: TOGETHER, startsAt: span.start, endsAt: span.end, kind: "together" as const })),
  ];
  return (
    <>
      <WeekGrid columns={columns} today={today} events={events} shades={shades} />
      {together.length > 0 ? (
        <p className="small">
          <strong>Liberi insieme:</strong> {together.map((span) => `${formatTime(span.start)}–${formatTime(span.end)}`).join(", ")}
        </p>
      ) : null}
    </>
  );
}

function groupByDay(spans: Span[]): [Day, Span[]][] {
  const byDay = new Map<Day, Span[]>();
  for (const span of spans) {
    const key = romeDay(span.start);
    byDay.set(key, [...(byDay.get(key) ?? []), span]);
  }
  return [...byDay.entries()];
}
