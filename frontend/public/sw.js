// The service worker of the panel (step 13, D-032): only the push notifications. It has no "fetch" listener: it never
// sees a request, never stores a page, an API answer or any data of a customer. The text of a push is the backend's
// (PushMessages: day, time and deadline, never the customer's name), and it only goes on the screen.

self.addEventListener("install", () => {
  // a new version takes over at once: there is nothing cached that an old page could still need
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

// The JSON of the backend: { title, body, url, tag }. Same tag: the new notification replaces the old one
self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = {};
  }
  const title = typeof data.title === "string" ? data.title : "Centro Posturale Apuano";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: typeof data.body === "string" ? data.body : "",
      tag: typeof data.tag === "string" ? data.tag : undefined,
      icon: "/icons/panel-192.png",
      badge: "/icons/panel-badge.png",
      data: { url: typeof data.url === "string" ? data.url : "/" },
    }),
  );
});

// A tap opens the panel: in a window of this site already open if there is one, otherwise a new one. Only an address
// of this site: anything else becomes the home page, which sends a practitioner to the panel
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  let target = new URL("/", self.location.origin);
  try {
    const asked = new URL(event.notification.data?.url ?? "/", self.location.origin);
    if (asked.origin === self.location.origin) {
      target = asked;
    }
  } catch {
    // a malformed address: the home page
  }
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(async (windows) => {
      for (const client of windows) {
        if (new URL(client.url).origin === self.location.origin) {
          try {
            await client.navigate(target.href);
          } catch {
            // a window this worker does not control yet: focusing it is enough
          }
          return client.focus();
        }
      }
      return self.clients.openWindow(target.href);
    }),
  );
});
