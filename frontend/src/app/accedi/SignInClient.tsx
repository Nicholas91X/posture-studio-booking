"use client";

// The sign-in (step 12, D-014, D-050, D-055): an email or a verified mobile, a code of six digits, and the first time
// first and last name. No sentence here says whether an email or a number has an account: the backend answers the
// same to every one (202), and so does this page.
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, unwrap } from "@/api/client";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { safeReturnPath } from "@/lib/navigation";
import { useSession } from "@/lib/session";

type Channel = "email" | "sms";
type Step = "address" | "code";

export function SignInClient() {
  const router = useRouter();
  const returnTo = safeReturnPath(useSearchParams().get("torna"));
  const { session, refresh } = useSession();

  const [channel, setChannel] = useState<Channel>("email");
  const [step, setStep] = useState<Step>("address");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [sending, setSending] = useState(false);
  const [smsUnavailable, setSmsUnavailable] = useState(false);

  const complete = session.state === "signed-in" && !session.profileIncomplete;
  useEffect(() => {
    if (complete) {
      router.replace(returnTo);
    }
  }, [complete, returnTo, router]);

  if (session.state === "loading" || complete) {
    return <Loading />;
  }
  if (session.state === "signed-in" && session.profileIncomplete) {
    return <NamesForm onDone={async () => void (await refresh())} />;
  }

  async function askForCode(event: FormEvent) {
    event.preventDefault();
    setSending(true);
    setError(null);
    try {
      if (channel === "email") {
        await unwrap(api.POST("/api/public/auth/login-code", { body: { email } }));
      } else {
        await unwrap(api.POST("/api/public/auth/login-code/sms", { body: { phone } }));
      }
      setCode("");
      setStep("code");
    } catch (failure) {
      if (failure instanceof ApiError && failure.is("SMS_UNAVAILABLE")) {
        setSmsUnavailable(true);
        setChannel("email");
      }
      setError(failure);
    } finally {
      setSending(false);
    }
  }

  async function signIn(event: FormEvent) {
    event.preventDefault();
    setSending(true);
    setError(null);
    try {
      if (channel === "email") {
        await unwrap(api.POST("/api/public/auth/session", { body: { email, code } }));
      } else {
        await unwrap(api.POST("/api/public/auth/session/sms", { body: { phone, code } }));
      }
      // who is in now, and whether the first and last name are still to be given
      await refresh();
    } catch (failure) {
      setError(failure);
      if (failure instanceof ApiError && failure.is("CODE_EXPIRED")) {
        setStep("address");
      }
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      <h1>Accedi</h1>
      <p className="muted">Niente password: ti mandiamo un codice di 6 cifre.</p>

      {step === "address" ? (
        <>
          {smsUnavailable ? null : (
            <div className="tabs" role="tablist" aria-label="Come ricevere il codice">
              <button type="button" role="tab" className="tab" aria-selected={channel === "email"} onClick={() => setChannel("email")}>
                Email
              </button>
              <button type="button" role="tab" className="tab" aria-selected={channel === "sms"} onClick={() => setChannel("sms")}>
                SMS
              </button>
            </div>
          )}
          <ErrorNotice error={error} />
          <form onSubmit={(event) => void askForCode(event)} className="card">
            {channel === "email" ? (
              <div className="field">
                <label htmlFor="email">La tua email</label>
                <input
                  id="email"
                  className="input"
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  required
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                />
                <p className="hint">Se è la prima volta, l&apos;account nasce quando inserisci il codice.</p>
              </div>
            ) : (
              <div className="field">
                <label htmlFor="phone">Il tuo cellulare</label>
                <input
                  id="phone"
                  className="input"
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  placeholder="333 123 4567"
                  required
                  value={phone}
                  onChange={(event) => setPhone(event.target.value)}
                />
                <p className="hint">Funziona con un numero già verificato dal tuo profilo. Altrimenti usa l&apos;email.</p>
              </div>
            )}
            <button type="submit" className="button button-block" disabled={sending}>
              {sending ? "Invio…" : "Mandami il codice"}
            </button>
          </form>
        </>
      ) : (
        <>
          <Notice>
            <p>
              {channel === "email"
                ? `Se l'indirizzo ${email} è corretto, ti abbiamo appena mandato il codice. Controlla anche nello spam.`
                : `Se il numero ${phone} è verificato su un account, ti abbiamo appena mandato il codice via SMS.`}
            </p>
          </Notice>
          <ErrorNotice error={error} />
          <form onSubmit={(event) => void signIn(event)} className="card">
            <div className="field">
              <label htmlFor="code">Codice</label>
              <input
                id="code"
                className="input input-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="\d{6}"
                maxLength={6}
                required
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
              />
              <p className="hint">Vale 10 minuti.</p>
            </div>
            <button type="submit" className="button button-block" disabled={sending || code.length !== 6}>
              {sending ? "Verifica…" : "Accedi"}
            </button>
          </form>
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setStep("address");
              setError(null);
            }}
          >
            Non è arrivato? Chiedine un altro
          </button>
        </>
      )}
    </>
  );
}

function NamesForm({ onDone }: { onDone: () => Promise<void> }) {
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [sending, setSending] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setSending(true);
    setError(null);
    try {
      await unwrap(api.PUT("/api/customer/profile", { body: { firstName, lastName } }));
      await onDone();
    } catch (failure) {
      setError(failure);
      setSending(false);
    }
  }

  return (
    <>
      <h1>Ci siamo quasi</h1>
      <p className="muted">Un ultimo passo: come ti chiami? Lo vedrà il professionista che ti segue.</p>
      <ErrorNotice error={error} />
      <form onSubmit={(event) => void save(event)} className="card">
        <div className="field">
          <label htmlFor="firstName">Nome</label>
          <input
            id="firstName"
            className="input"
            autoComplete="given-name"
            required
            maxLength={100}
            value={firstName}
            onChange={(event) => setFirstName(event.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="lastName">Cognome</label>
          <input
            id="lastName"
            className="input"
            autoComplete="family-name"
            required
            maxLength={100}
            value={lastName}
            onChange={(event) => setLastName(event.target.value)}
          />
        </div>
        <button type="submit" className="button button-block" disabled={sending}>
          {sending ? "Salvataggio…" : "Continua"}
        </button>
      </form>
    </>
  );
}
