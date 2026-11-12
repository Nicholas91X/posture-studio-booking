// The only client of the backend's APIs (step 12, D-085). Everything that talks to /api goes through here:
// - the types come from the OpenAPI contract (schema.d.ts, generated: never written by hand, D-005, D-083);
// - same origin, so the browser sends the HttpOnly session cookie by itself (D-017, D-051);
// - every request that changes something carries the CSRF token as Spring Security wants it: the value of the
//   XSRF-TOKEN cookie in the X-XSRF-TOKEN header (D-051);
// - every error answer becomes one type, ApiError, with the readable code of the ProblemDetail (errors.ts).
import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./schema";

export type Schemas = components["schemas"];
export type ApiErrorCode = NonNullable<Schemas["ApiProblem"]["code"]>;

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const found = document.cookie.split("; ").find((cookie) => cookie.startsWith(prefix));
  return found === undefined ? undefined : decodeURIComponent(found.slice(prefix.length));
}

// The backend writes the XSRF-TOKEN cookie on any answer. Before the very first request that changes something
// (e.g. asking for an access code on a fresh visit) there may be none yet: one light GET brings it.
let warmUp: Promise<void> | null = null;
async function ensureCsrfCookie(): Promise<void> {
  if (readCookie(CSRF_COOKIE) !== undefined) {
    return;
  }
  warmUp ??= fetch("/api/public/practitioners", { credentials: "same-origin" })
    .then(() => undefined)
    .finally(() => {
      warmUp = null;
    });
  await warmUp;
}

const csrf: Middleware = {
  async onRequest({ request }) {
    if (SAFE_METHODS.has(request.method)) {
      return request;
    }
    await ensureCsrfCookie();
    const token = readCookie(CSRF_COOKIE);
    if (token !== undefined) {
      request.headers.set(CSRF_HEADER, token);
    }
    return request;
  },
};

export const api = createClient<paths>({ baseUrl: "", credentials: "same-origin" });
api.use(csrf);

export type FieldError = Schemas["ApiFieldError"];

/** Every failure of a call, whatever it was: an error answer of the backend, or no answer at all (status 0). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    // a string, not ApiErrorCode: a newer backend may send a code this build does not know yet (errors.ts)
    readonly code: string | undefined,
    readonly fieldErrors: FieldError[] = [],
    readonly retryAfterSeconds: number | undefined = undefined,
  ) {
    super(code ?? `HTTP ${status}`);
    this.name = "ApiError";
  }

  is(code: ApiErrorCode): boolean {
    return this.code === code;
  }
}

type CallResult<D> = { data?: D; error?: unknown; response: Response };

/**
 * Waits for a call and gives its data, or throws an ApiError. Pages never read a Response themselves:
 *   const booking = await unwrap(api.GET("/api/customer/bookings/{id}", { params: { path: { id } } }));
 */
export async function unwrap<D>(call: Promise<CallResult<D>>): Promise<D> {
  let result: CallResult<D>;
  try {
    result = await call;
  } catch {
    throw new ApiError(0, undefined);
  }
  if (result.response.ok) {
    return result.data as D;
  }
  const problem = isProblem(result.error) ? result.error : undefined;
  const retryAfter = Number(result.response.headers.get("Retry-After"));
  throw new ApiError(
    result.response.status,
    typeof problem?.code === "string" ? problem.code : undefined,
    Array.isArray(problem?.errors) ? problem.errors : [],
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  );
}

function isProblem(value: unknown): value is Partial<Schemas["ApiProblem"]> {
  return typeof value === "object" && value !== null;
}
