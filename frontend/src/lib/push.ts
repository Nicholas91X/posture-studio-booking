// The push notifications of the panel, on the browser's side (step 13, D-032, D-054). The backend sends them and
// decides when; here only: is it possible on this phone, the system's permission, the subscription of this browser
// sent to the backend, and "later" remembered on this device.
import { api, unwrap } from "@/api/client";

export type PushState =
  // the browser has no service worker or no push (an old browser)
  | "unsupported"
  // an iPhone or iPad with the panel open in Safari: push only works once it is on the Home screen (iOS 16.4+)
  | "ios-not-installed"
  // the system has not asked yet
  | "ask"
  | "granted"
  // refused: only the settings of the phone or of the browser can change it now
  | "denied";

const LATER_KEY = "pannello.notifiche.piu-tardi";
// "later" means: not at every opening; after a week the popup comes back once
const LATER_DAYS = 7;

export function isIos(): boolean {
  const agent = navigator.userAgent;
  // iPadOS says it is a Mac: a Mac with a touch screen is an iPad
  return /iPhone|iPad|iPod/.test(agent) || (agent.includes("Macintosh") && navigator.maxTouchPoints > 1);
}

/** Opened from the Home screen, as an app, rather than in a tab of the browser. */
export function isInstalled(): boolean {
  const standalone = (navigator as Navigator & { standalone?: boolean }).standalone === true;
  return standalone || window.matchMedia("(display-mode: standalone)").matches;
}

export function pushState(): PushState {
  const capable = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  if (isIos() && !isInstalled()) {
    return "ios-not-installed";
  }
  if (!capable) {
    return "unsupported";
  }
  switch (Notification.permission) {
    case "granted":
      return "granted";
    case "denied":
      return "denied";
    default:
      return "ask";
  }
}

export function isLaterRemembered(): boolean {
  try {
    const saved = Number(localStorage.getItem(LATER_KEY));
    return Number.isFinite(saved) && saved > 0 && Date.now() - saved < LATER_DAYS * 86_400_000;
  } catch {
    return false;
  }
}

export function rememberLater(): void {
  try {
    localStorage.setItem(LATER_KEY, String(Date.now()));
  } catch {
    // private mode: the popup will come back next time, nothing worse
  }
}

function forgetLater(): void {
  try {
    localStorage.removeItem(LATER_KEY);
  } catch {
    // nothing to forget
  }
}

// The service worker at the root, so it controls every page of the site (it handles only pushes, sw.js)
async function registration(): Promise<ServiceWorkerRegistration> {
  await navigator.serviceWorker.register("/sw.js", { scope: "/", updateViaCache: "none" });
  return navigator.serviceWorker.ready;
}

/** The VAPID public key in Base64url (as the backend gives it) → the bytes subscribe() wants. */
function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const base64 = (base64url + "=".repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let index = 0; index < raw.length; index++) {
    bytes[index] = raw.charCodeAt(index);
  }
  return bytes;
}

function sameKey(subscription: PushSubscription, key: Uint8Array): boolean {
  const current = subscription.options.applicationServerKey;
  if (current === null) {
    return false;
  }
  const bytes = new Uint8Array(current);
  return bytes.length === key.length && bytes.every((byte, index) => byte === key[index]);
}

// The browser asks its push service (Google, Apple, Mozilla) for an address: a browser cut off from it may never
// answer (seen with a Chrome started by Playwright), and the button would say "Attivazione…" forever
const SUBSCRIBE_SECONDS = 20;

export class PushServiceUnreachable extends Error {
  constructor() {
    super("push service did not answer");
    this.name = "PushServiceUnreachable";
  }
}

function withinSeconds<T>(seconds: number, promise: Promise<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new PushServiceUnreachable()), seconds * 1000);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error(String(error)));
      },
    );
  });
}

// This browser's subscription, made with the backend's key, and given to the backend. Sent at every visit: the
// browser may renew it, and the backend keeps one row per address (D-054). A subscription made with another key
// (the VAPID keys of the server changed) is useless: replaced
async function subscribeAndSend(): Promise<void> {
  const worker = await registration();
  const { publicKey } = await unwrap(api.GET("/api/panel/push-subscriptions/vapid-key"));
  const key = keyBytes(publicKey);
  let subscription = await worker.pushManager.getSubscription();
  if (subscription !== null && !sameKey(subscription, key)) {
    await subscription.unsubscribe();
    subscription = null;
  }
  subscription ??= await withinSeconds(
    SUBSCRIBE_SECONDS,
    worker.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key }),
  );
  const json = subscription.toJSON();
  if (json.endpoint === undefined || json.keys?.p256dh === undefined || json.keys.auth === undefined) {
    throw new Error("subscription without keys");
  }
  await unwrap(
    api.POST("/api/panel/push-subscriptions", {
      body: { endpoint: json.endpoint, keys: { p256dh: json.keys.p256dh, auth: json.keys.auth } },
    }),
  );
}

/**
 * The "Attiva" button: the system's question first, straight from the tap (iPhone asks only from a tap), then the
 * subscription. Gives the state after the answer.
 */
export async function enablePush(): Promise<PushState> {
  const answer = await Notification.requestPermission();
  if (answer !== "granted") {
    return answer === "denied" ? "denied" : "ask";
  }
  forgetLater();
  await subscribeAndSend();
  return "granted";
}

/** At every opening of the panel, when the permission is already there: the subscription sent again. */
export async function syncPush(): Promise<void> {
  if (pushState() === "granted") {
    await subscribeAndSend();
  }
}

/** Whether this browser is subscribed now (for the settings page). */
export async function isSubscribed(): Promise<boolean> {
  if (!("serviceWorker" in navigator)) {
    return false;
  }
  const worker = await navigator.serviceWorker.getRegistration("/");
  return (await worker?.pushManager.getSubscription()) != null;
}

/** "Disattiva" in the settings, and the sign-out: this browser stops receiving the practitioner's pushes. */
export async function disablePush(): Promise<void> {
  if (!("serviceWorker" in navigator)) {
    return;
  }
  const worker = await navigator.serviceWorker.getRegistration("/");
  const subscription = await worker?.pushManager.getSubscription();
  if (subscription == null) {
    return;
  }
  const endpoint = subscription.endpoint;
  await subscription.unsubscribe();
  // the backend forgets it too; if this fails, the next push to it answers "gone" and the row is deleted (D-054)
  await unwrap(api.POST("/api/panel/push-subscriptions/unsubscribe", { body: { endpoint } })).catch(() => undefined);
}

/** The test push (D-088): true if at least one device of the practitioner took it. */
export async function sendTestPush(): Promise<boolean> {
  return (await unwrap(api.POST("/api/panel/push-subscriptions/test"))).delivered;
}
