// Reading a state again until it changes, with a limit of time (step 12). Coming back from Stripe proves nothing:
// the payment is confirmed by the webhook, which arrives on its own (D-007). The page asks the backend again
// every few seconds, and stops after a while with a clear message.
export type PollOutcome<T> = { done: true; value: T } | { done: false; value: T | undefined };

export async function pollUntil<T>(
  read: () => Promise<T>,
  isDone: (value: T) => boolean,
  options: { everyMs?: number; forMs?: number; signal?: AbortSignal } = {},
): Promise<PollOutcome<T>> {
  const every = options.everyMs ?? 2000;
  const deadline = Date.now() + (options.forMs ?? 60_000);
  let last: T | undefined;
  while (!options.signal?.aborted) {
    try {
      last = await read();
      if (isDone(last)) {
        return { done: true, value: last };
      }
    } catch {
      // a moment without answer is not the end: try again until the deadline
    }
    if (Date.now() + every > deadline) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, every));
  }
  return { done: false, value: last };
}
