// Downloads the OpenAPI contract from the running backend into openapi.json (D-083), which is committed: the types
// in src/api/schema.d.ts are generated from it (npm run api:types). The backend must run with OPENAPI_ENABLED=true.
import { writeFile } from "node:fs/promises";

const backend = process.env.BACKEND_URL ?? "http://localhost:8080";
const url = `${backend}/v3/api-docs`;

let response;
try {
  response = await fetch(url);
} catch {
  console.error(`The backend does not answer on ${backend}. Start it first (docs/stato.md, "Avvio del frontend").`);
  process.exit(1);
}
if (!response.ok) {
  console.error(`${url} answered ${response.status}: is OPENAPI_ENABLED=true in .env?`);
  process.exit(1);
}
const contract = await response.json();
// the address of the server is the one of whoever ran the script: not part of the contract
delete contract.servers;
await writeFile("openapi.json", JSON.stringify(contract, null, 2) + "\n");
console.log(`openapi.json written: ${Object.keys(contract.paths).length} paths`);
