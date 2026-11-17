"use client";

import { useEffect, useState } from "react";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Notice } from "@/components/Notice";
import { ApiError } from "@/api/client";
import { useAction } from "@/lib/panel";
import {
  disablePush,
  enablePush,
  isLaterRemembered,
  isSubscribed,
  pushState,
  rememberLater,
  sendTestPush,
  syncPush,
  type PushState,
} from "@/lib/push";

// A failure of the browser (no answer from its push service, a refused subscription) is not an error of our API:
// its own sentence, never a technical text
function PushError({ error }: { error: unknown }) {
  if (error == null) {
    return null;
  }
  if (error instanceof ApiError) {
    return <ErrorNotice error={error} />;
  }
  return (
    <Notice tone="error">
      <p>
        Il browser non è riuscito a registrarsi al servizio delle notifiche. Controlla la connessione e riprova; se continua,
        prova con Chrome, oppure con Safari dalla schermata Home su iPhone.
      </p>
    </Notice>
  );
}

const WHY =
  "Quando un cliente chiede una seduta da approvare, il telefono ti avvisa subito, anche con il pannello chiuso. " +
  "La notifica mostra solo il giorno e l'ora, mai il nome del cliente.";

/** How to give the permission back after a "no": only the phone's or the browser's settings can. */
export function DeniedHelp() {
  return (
    <div className="small stack">
      <p>Le notifiche sono bloccate per il pannello. Per riattivarle:</p>
      <ul className="steps">
        <li>
          <strong>iPhone</strong>: Impostazioni › Notifiche › Pannello › attiva &laquo;Consenti notifiche&raquo;.
        </li>
        <li>
          <strong>Android</strong>: tieni premuta l&apos;icona del pannello › Info app › Notifiche › consenti. Se lo usi dal
          browser: tocca il simbolo a sinistra dell&apos;indirizzo › Autorizzazioni › Notifiche › Consenti.
        </li>
        <li>
          <strong>Computer</strong>: clicca il simbolo a sinistra dell&apos;indirizzo › Notifiche › Consenti, poi ricarica la pagina.
        </li>
      </ul>
    </div>
  );
}

/** How to put the panel on the Home screen of an iPhone: the only way iOS gives it notifications (16.4 and later). */
export function InstallHelp() {
  return (
    <div className="small stack">
      <p>Su iPhone le notifiche arrivano solo se il pannello è sulla schermata Home (iOS 16.4 o successivo):</p>
      <ol className="steps">
        <li>
          In Safari tocca <strong>Condividi</strong> (il quadrato con la freccia in su).
        </li>
        <li>
          Scegli <strong>Aggiungi alla schermata Home</strong>, poi <strong>Aggiungi</strong>.
        </li>
        <li>Apri il pannello dalla nuova icona e attiva le notifiche.</li>
      </ol>
    </div>
  );
}

// The popup of the panel (step 13): it explains, and only its "Attiva" asks the system's question. "Più tardi" is
// remembered on this device for a week, so it does not come back at every opening. Already allowed: the subscription
// is sent again quietly, at every opening (D-054)
export function NotificationPrompt() {
  const [state, setState] = useState<PushState | null>(null);
  const [open, setOpen] = useState(false);
  const action = useAction();

  useEffect(() => {
    const now = pushState();
    setState(now);
    if (now === "granted") {
      void syncPush().catch(() => undefined);
    } else if ((now === "ask" || now === "ios-not-installed") && !isLaterRemembered()) {
      setOpen(true);
    }
  }, []);

  if (!open || state === null) {
    return null;
  }
  const later = () => {
    rememberLater();
    setOpen(false);
  };

  if (state === "ios-not-installed") {
    return (
      <Dialog title="Installa il pannello" onClose={later}>
        <p>{WHY}</p>
        <InstallHelp />
        <div className="dialog-actions">
          <button type="button" className="button button-secondary" onClick={later}>
            Più tardi
          </button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title="Attiva le notifiche" onClose={later}>
      {state === "granted" ? (
        <>
          <Notice tone="success">
            <p>Notifiche attive su questo dispositivo.</p>
          </Notice>
          <div className="dialog-actions">
            <button type="button" className="button" onClick={() => setOpen(false)}>
              Chiudi
            </button>
          </div>
        </>
      ) : state === "denied" ? (
        <>
          <DeniedHelp />
          <div className="dialog-actions">
            <button type="button" className="button button-secondary" onClick={later}>
              Chiudi
            </button>
          </div>
        </>
      ) : (
        <>
          <p>{WHY}</p>
          <p className="small muted">Toccando &laquo;Attiva&raquo; il telefono ti chiederà il permesso.</p>
          <PushError error={action.error} />
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={action.running}
              onClick={() => void action.run(async () => setState(await enablePush()))}
            >
              {action.running ? "Attivazione…" : "Attiva"}
            </button>
            <button type="button" className="button button-secondary" onClick={later}>
              Più tardi
            </button>
          </div>
        </>
      )}
    </Dialog>
  );
}

// The card of the settings: the state on this device, on and off, and the test push (D-088)
export function NotificationsCard() {
  const [state, setState] = useState<PushState | null>(null);
  const [subscribed, setSubscribed] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const action = useAction();

  useEffect(() => {
    setState(pushState());
    void isSubscribed().then(setSubscribed, () => setSubscribed(false));
  }, []);

  const on = state === "granted" && subscribed;

  return (
    <div className="card">
      <h2>Notifiche</h2>
      <p className="small muted">{WHY}</p>
      {state === "unsupported" ? (
        <Notice tone="warning">
          <p>Questo browser non riceve notifiche. Usa Chrome su Android, Safari su iPhone (dalla schermata Home) o un browser recente sul computer.</p>
        </Notice>
      ) : null}
      {state === "ios-not-installed" ? <InstallHelp /> : null}
      {state === "denied" ? <DeniedHelp /> : null}
      {state === "ask" || (state === "granted" && !subscribed) ? (
        <button
          type="button"
          className="button button-block"
          disabled={action.running}
          onClick={() =>
            void action.run(async () => {
              const next = await enablePush();
              setState(next);
              setSubscribed(next === "granted");
            })
          }
        >
          Attiva le notifiche
        </button>
      ) : null}
      {on ? (
        <div className="stack">
          <Notice tone="success">
            <p>Notifiche attive su questo dispositivo.</p>
          </Notice>
          <button
            type="button"
            className="button button-block"
            disabled={action.running}
            onClick={() =>
              void action.run(async () => {
                setResult(null);
                setResult(
                  (await sendTestPush())
                    ? "Notifica di prova inviata: dovrebbe comparire tra pochi secondi."
                    : "Nessun dispositivo ha ricevuto la notifica. Disattiva e riattiva le notifiche, poi riprova.",
                );
              })
            }
          >
            Manda una notifica di prova
          </button>
          <button
            type="button"
            className="button button-secondary button-block"
            disabled={action.running}
            onClick={() =>
              void action.run(async () => {
                await disablePush();
                setSubscribed(false);
                setResult(null);
              })
            }
          >
            Disattiva su questo dispositivo
          </button>
        </div>
      ) : null}
      {result !== null ? (
        <Notice>
          <p>{result}</p>
        </Notice>
      ) : null}
      <PushError error={action.error} />
    </div>
  );
}
