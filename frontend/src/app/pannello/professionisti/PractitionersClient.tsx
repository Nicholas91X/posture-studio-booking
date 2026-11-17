"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, unwrap } from "@/api/client";
import { Dialog } from "@/components/Dialog";
import { ErrorNotice, Loading, Notice } from "@/components/Notice";
import { fullName } from "@/lib/format";
import { useAction, useApi } from "@/lib/panel";

// The admin's practitioners (D-021, D-052): the list, and a new one by email. The new practitioner signs in with a
// code sent to that email, like everyone (D-014)
export function PractitionersClient() {
  const practitioners = useApi(() => unwrap(api.GET("/api/admin/practitioners")), []);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<string | null>(null);
  const sorted = [...(practitioners.data ?? [])].sort((a, b) => Number(b.active) - Number(a.active) || a.lastName.localeCompare(b.lastName, "it"));

  return (
    <>
      <h1>Professionisti</h1>
      <button type="button" className="button button-block" onClick={() => setCreating(true)}>
        Nuovo professionista
      </button>
      {created !== null ? (
        <Notice tone="success">
          <p>{created}</p>
        </Notice>
      ) : null}
      <ErrorNotice error={practitioners.error} />
      {practitioners.loading ? <Loading /> : null}
      {practitioners.data?.length === 0 ? <p className="muted">Nessun professionista.</p> : null}
      {sorted.map((practitioner) => (
        <Link key={practitioner.id} href={`/pannello/professionisti/${practitioner.id}`} className="card card-link">
          <div className="spread">
            <div>
              <h2>{fullName(practitioner)}</h2>
              <p className="small muted">{practitioner.email}</p>
            </div>
            <span className={practitioner.active ? "badge badge-success" : "badge"}>{practitioner.active ? "Attivo" : "Disattivato"}</span>
          </div>
        </Link>
      ))}
      {creating ? (
        <NewPractitionerDialog
          onClose={() => setCreating(false)}
          onCreated={(name) => {
            setCreating(false);
            setCreated(`Fatto: ${name} ora è nel pannello e può accedere con la sua email; prima di ricevere pagamenti online va assegnato un conto di incasso.`);
            practitioners.reload();
          }}
        />
      ) : null}
    </>
  );
}

function NewPractitionerDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (name: string) => void }) {
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const action = useAction();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    void action.run(async () => {
      const practitioner = await unwrap(
        api.POST("/api/admin/practitioners", { body: { email: email.trim(), firstName: firstName.trim(), lastName: lastName.trim() } }),
      );
      onCreated(fullName(practitioner));
    });
  };
  return (
    <Dialog title="Nuovo professionista" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <div className="field">
          <label htmlFor="newEmail">Email</label>
          <input id="newEmail" type="email" className="input" required autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} />
        </div>
        <div className="form-pair">
          <div className="field">
            <label htmlFor="newFirstName">Nome</label>
            <input id="newFirstName" className="input" required maxLength={100} value={firstName} onChange={(event) => setFirstName(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="newLastName">Cognome</label>
            <input id="newLastName" className="input" required maxLength={100} value={lastName} onChange={(event) => setLastName(event.target.value)} />
          </div>
        </div>
        <ErrorNotice error={action.error} />
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={action.running}>
            {action.running ? "Invio…" : "Aggiungi"}
          </button>
          <button type="button" className="button button-secondary" onClick={onClose}>
            Annulla
          </button>
        </div>
      </form>
    </Dialog>
  );
}
