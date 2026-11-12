"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, unwrap, type Schemas } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { formatCalendarDay, romeDay } from "@/lib/format";
import { useSession } from "@/lib/session";

type Phone = Schemas["AccountPhoneView"];

export function ProfileClient() {
  const { session, refresh, signOut } = useSession();
  const profile = session.state === "signed-in" ? session.profile : null;

  return (
    <>
      <h1>Profilo</h1>
      {profile === null ? <Loading /> : <NamesCard profile={profile} onSaved={refresh} />}
      <PhoneCard />
      <div className="card">
        <h2>Esci</h2>
        <p className="small muted">Su questo dispositivo dovrai accedere di nuovo con un codice.</p>
        <button type="button" className="button button-secondary button-block" onClick={() => void signOut()}>
          Esci
        </button>
      </div>
    </>
  );
}

function NamesCard({ profile, onSaved }: { profile: Schemas["CustomerProfile"]; onSaved: () => Promise<unknown> }) {
  const [firstName, setFirstName] = useState(profile.firstName);
  const [lastName, setLastName] = useState(profile.lastName);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);
  const [sending, setSending] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setSending(true);
    setError(null);
    setSaved(false);
    try {
      await unwrap(api.PUT("/api/customer/profile", { body: { firstName, lastName } }));
      await onSaved();
      setSaved(true);
    } catch (failure) {
      setError(failure);
    } finally {
      setSending(false);
    }
  }

  return (
    <form className="card" onSubmit={(event) => void save(event)}>
      <h2>I tuoi dati</h2>
      <p className="small">
        Email: <strong>{profile.email}</strong>
        <br />
        <span className="muted">È l&apos;indirizzo con cui accedi. Per cambiarlo contatta lo studio.</span>
      </p>
      <div className="field">
        <label htmlFor="firstName">Nome</label>
        <input id="firstName" className="input" autoComplete="given-name" required maxLength={100} value={firstName} onChange={(event) => setFirstName(event.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="lastName">Cognome</label>
        <input id="lastName" className="input" autoComplete="family-name" required maxLength={100} value={lastName} onChange={(event) => setLastName(event.target.value)} />
      </div>
      <ErrorNotice error={error} />
      {saved ? (
        <Notice tone="success">
          <p>Salvato.</p>
        </Notice>
      ) : null}
      <button type="submit" className="button button-block" disabled={sending}>
        {sending ? "Salvataggio…" : "Salva"}
      </button>
    </form>
  );
}

// The mobile for the SMS sign-in (D-055): added only through a code sent to it. Italian mobiles only: the backend
// says whether the number is valid (400), this page only shows it
function PhoneCard() {
  const [phone, setPhone] = useState<Phone | null>(null);
  const [number, setNumber] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"show" | "number" | "code">("show");
  const [error, setError] = useState<unknown>(null);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    unwrap(api.GET("/api/account/phone")).then(setPhone, setError);
  }, []);

  async function run(action: () => Promise<void>) {
    setSending(true);
    setError(null);
    try {
      await action();
    } catch (failure) {
      setError(failure);
    } finally {
      setSending(false);
    }
  }

  const askCode = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      await unwrap(api.POST("/api/account/phone/code", { body: { phone: number } }));
      setCode("");
      setStep("code");
    });
  };

  const confirm = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      setPhone(await unwrap(api.POST("/api/account/phone", { body: { phone: number, code } })));
      setStep("show");
    });
  };

  const remove = () =>
    void run(async () => {
      await unwrap(api.DELETE("/api/account/phone"));
      setPhone({ phone: null, verifiedAt: null });
    });

  const invalidNumber = error instanceof ApiError && error.status === 400;

  return (
    <div className="card">
      <h2>Accesso con SMS</h2>
      {phone === null && error === null ? <Loading /> : null}
      {invalidNumber ? (
        <Notice tone="error">
          <p>Il numero non è valido: serve un cellulare italiano, per esempio 333 123 4567.</p>
        </Notice>
      ) : (
        <ErrorNotice error={error} />
      )}

      {step === "show" && phone !== null ? (
        phone.phone === null ? (
          <>
            <p className="small muted">Aggiungi il tuo cellulare per ricevere il codice di accesso anche via SMS.</p>
            <button type="button" className="button button-secondary button-block" onClick={() => setStep("number")}>
              Aggiungi un cellulare
            </button>
          </>
        ) : (
          <>
            <p>
              <strong>{phone.phone}</strong>
              {phone.verifiedAt !== null ? (
                <span className="small muted"> · verificato il {formatCalendarDay(romeDay(phone.verifiedAt))}</span>
              ) : null}
            </p>
            <div className="row">
              <button type="button" className="button button-secondary" onClick={() => setStep("number")}>
                Cambia numero
              </button>
              <button type="button" className="link-button" disabled={sending} onClick={remove}>
                Togli il numero
              </button>
            </div>
          </>
        )
      ) : null}

      {step === "number" ? (
        <form onSubmit={askCode}>
          <div className="field">
            <label htmlFor="phone">Cellulare</label>
            <input id="phone" className="input" type="tel" inputMode="tel" autoComplete="tel" placeholder="333 123 4567" required value={number} onChange={(event) => setNumber(event.target.value)} />
            <p className="hint">Ti mandiamo un codice via SMS per verificare che il numero sia tuo.</p>
          </div>
          <div className="row">
            <button type="submit" className="button" disabled={sending}>
              {sending ? "Invio…" : "Mandami il codice"}
            </button>
            <button type="button" className="link-button" onClick={() => setStep("show")}>
              Annulla
            </button>
          </div>
        </form>
      ) : null}

      {step === "code" ? (
        <form onSubmit={confirm}>
          <p className="small">Abbiamo mandato un codice al {number}.</p>
          <div className="field">
            <label htmlFor="phoneCode">Codice</label>
            <input id="phoneCode" className="input input-code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} required value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))} />
          </div>
          <div className="row">
            <button type="submit" className="button" disabled={sending || code.length !== 6}>
              {sending ? "Verifica…" : "Verifica"}
            </button>
            <button type="button" className="link-button" onClick={() => setStep("number")}>
              Cambia numero
            </button>
          </div>
        </form>
      ) : null}
    </div>
  );
}
