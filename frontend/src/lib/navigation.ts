// Where to go back after the sign-in (step 12): only a path of this site. "?torna=https://elsewhere" or
// "//elsewhere" would turn the sign-in page into a door to another site (an open redirect).
export function safeReturnPath(value: string | null | undefined): string {
  if (value == null || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) {
    return "/";
  }
  return value;
}

/** The sign-in page, with the way back to where the customer was. */
export function signInPath(returnTo: string): string {
  return `/accedi?torna=${encodeURIComponent(safeReturnPath(returnTo))}`;
}
