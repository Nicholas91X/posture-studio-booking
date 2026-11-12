// The name of the studio and how the site talks about it: one place, as the colours in tokens.css (step 12).
// When the studio sends its name, logo and contacts, only this file and tokens.css change.
export const brand = {
  name: "Studio di Posturologia",
  // the short name, for the browser tab and small screens
  shortName: "Posturologia",
  // what the site says when the customer must turn to the studio (e.g. credits of a practitioner who left, D-077)
  contactHint: "Contatta lo studio per sapere come procedere.",
} as const;
