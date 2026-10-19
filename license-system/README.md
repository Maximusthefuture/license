# License System

Offline, email-based licensing for a product that runs in the customer's
Kubernetes cluster with no network path back to the vendor. A license
request travels to the vendor by email as text, and a signed license key
travels back the same way. There is no revocation and no transfer.

```
Customer's cluster                                         Vendor
──────────────────                                         ──────
license-validator-service                                  license-generator-service
  admin page: "Request a license"  ── request text ──────▶   operator pastes it, picks
                                        (email)              customer/modules/users/term,
  admin page: "Activate"  ◀────────── license text ──────    service signs with the .p12 key
       │                                (email)
       │ stores signed text in Postgres
       ▼
  GET /api/v1/license/signed
       │
       ▼
product + license-client library
  verifies the signature with the public keys compiled into the product,
  checks product id and cluster id itself, then:
  isModuleEnabled() / maxUsers() / checkCanAddUser()
```

The product never trusts the validator's word. It fetches the signed text
and checks it itself. A validator replaced by a stub that answers "valid"
gains nothing, because it cannot produce a vendor signature.

## Modules

| Module | What it is | Where it runs |
|---|---|---|
| `license-common` | Wire format: models, armored text codec, Ed25519. JDK only. | everywhere |
| `license-client` | Validation, activation, status, request generation; `ProductLicensing` for the product side. JDK only. | in the validator service **and** in the product |
| `license-issuer` | Signing, `.p12` keystore handling, issuance + audit (`LicenseIssuingService`), CLI tools. JDK only. | vendor |
| `license-generator-service` | Spring Boot service around `license-issuer`: operator web page + API, Basic auth. | vendor |
| `license-validator-service` | Spring Boot service around `license-client`: admin page + API, Postgres. | customer's cluster |

Spring Boot is 4.1 (`spring-boot.version` in the root `pom.xml`). The 3.x
line (3.5) left free OSS support on 2026-06-30. The code uses only APIs that
exist in both, so switching back means changing that one property (and
`spring-boot-starter-webmvc` to `spring-boot-starter-web`).

## Building

```
mvn verify
docker build -t registry.example.com/license-generator:1.0.0 license-generator-service
docker build -t registry.example.com/license-validator:1.0.0 license-validator-service
```

**What was and wasn't verified.** These sources were written without access
to Maven Central, so `mvn` itself has not been run on them.
- The three JDK-only modules were compiled with `javac -Xlint:all`, and all
  their tests (79) were executed with a minimal stand-in for the
  JUnit/AssertJ API they use. All pass.
- The two Spring services were compiled against stand-in Spring/SLF4J API
  classes, which checks the Java but not Spring's behaviour. Bean wiring,
  configuration binding, security and JSON mapping have **not** been
  executed.
- The two web pages were exercised in headless Chromium against mock
  backends that return the same JSON shapes as the controllers.

The first run of `mvn verify` and a first start of each service in your
environment is therefore the real check for the Spring layer. If something
fails there, it will be in `*Configuration`, `*Controller` or
`application.yaml`.

## Generator service (vendor)

Manifest: `deploy/generator/license-generator.yaml`. One persistent volume
holds the keys (`/data/keys`: one `<kid>.p12` per key plus an `active-key`
file naming the key that signs) and the audit logs (`/data/audit`).

### First start

1. **Secret.** Create the keystore password and the operator logins (see
   the comment at the top of the manifest). Passwords are bcrypt hashes. At
   least one operator needs `ADMIN=true`: only admins see and use the
   "Ключи подписи" section.
2. **Apply** the manifest. The service starts without any key. It shows
   "Нет активного ключа" and refuses to issue licenses.
3. **Create the primary key.** In the "Ключи подписи" section, enter a name
   (e.g. `2026-01`) and click "Создать ключ". The key is written to the
   volume, encrypted with `LICENSE_KEYSTORE_PASSWORD`, but it does **not**
   sign anything yet.
4. **Create the reserve key** in the same section ("Запасной ключ"), with
   its own password. The `.p12` is only downloaded and never stored on the
   volume. Keep it offline, apart from the primary.
5. **Back up the primary key:** click "скачать копию" and store the file in
   at least two offline places.
6. Copy the lines from "Строки для TrustedKeys" plus the reserve key's line
   into `license-client/.../crypto/TrustedKeys.java`, replacing the
   placeholders. Run `KeyFingerprintTool`, put the result into
   `integrity/StatusMessages.java`, and rebuild the **validator service and
   the product**: both contain `license-client`.
7. **Activate the primary key:** click "Сделать активным". From now on it
   signs every new license, without a restart.

Every key action (created, activated, backup downloaded, reserve created)
is recorded with the admin's login, in `/data/audit/key-events.tsv` and in
the log table on the page.

### Rotation

1. Create the new key (e.g. `2027-01`) with the button. The old key keeps
   signing.
2. Add the new key's line to `TrustedKeys`, release, wait until customers
   have installed that release.
3. Click "Сделать активным" on the new key. Licenses issued earlier keep
   working, because the old key stays trusted and stays on the volume.
4. In a later release, remove the old key from `TrustedKeys`, once no
   license signed with it is still in use. The "Ключ" column of the
   license history shows which key signed what.

### Issuing licenses

Operators work in the web page at the service root (behind TLS: an ingress,
or `kubectl port-forward svc/license-generator 8080`):

1. Paste the request from the email and click "Разобрать запрос". The page
   shows product, organization, installation and cluster, and warns if a
   license was already issued for this exact request.
2. Choose customer, modules, user limit and term, then issue.
3. Copy the license text into the reply email. The table below the form is
   the audit log; each row links to the exact text that was sent.

API (HTTP Basic, role OPERATOR):

| Method | Path | |
|---|---|---|
| GET | `/api/v1/signing-key` | kid, public key, ready-made `TrustedKeys` line |
| POST | `/api/v1/requests/preview` | `{"requestText": "..."}` → parsed request + earlier licenses for it |
| POST | `/api/v1/licenses` | `{"requestText","customer","modules":[...],"maxUsers","validityDays"}` → 201 with `licenseText` |
| GET | `/api/v1/licenses?limit=50` | issuance history |
| GET | `/api/v1/licenses/{id}/text` | the exact license text, to resend |

Signing keys (role ADMIN):

| Method | Path | |
|---|---|---|
| GET | `/api/v1/keys` | keys on the volume, the active one, recent key events |
| POST | `/api/v1/keys` | `{"keyId": "2026-01"}` → 201, new key on the volume, not active |
| POST | `/api/v1/keys/{kid}/activate` | `{"confirm": true}` → new licenses are signed with this key |
| GET | `/api/v1/keys/{kid}/backup` | the encrypted `.p12`, for an offline backup |
| POST | `/api/v1/keys/reserve` | `{"keyId","password"}` → reserve `.p12` (base64) to download; not stored |

The CLI tools still work without the service. `KeyGenTool` creates a `.p12`
on a workstation. To use such a key in the service, it must be encrypted
with the same `LICENSE_KEYSTORE_PASSWORD`; copy it to `/data/keys` and
activate it on the page. `IssuerCli primary.p12 audit-dir` issues a license
from the command line.

## Validator service (customer's cluster)

Manifest: `deploy/validator/license-validator.yaml`. It needs:
- **PostgreSQL**: a dedicated database or schema. The tables are created at
  startup (`schema.sql`).
- **RBAC** `get` on `namespaces/kube-system`, for the validator **and** for
  the product's ServiceAccount. The UID of that namespace identifies the
  cluster, and a license bound to a cluster works only there. Without this
  RBAC, requests are not bound to the cluster (the validator logs a warning
  at startup), and the product rejects cluster-bound licenses.
- `LICENSE_PRODUCT_ID`: must match the product id in licenses.

The customer's administrator opens the admin page
(`kubectl port-forward svc/license-validator 8080`). There they see the
current status, create a request to email to you, and paste the key from
your reply to activate it.

| Method | Path | |
|---|---|---|
| POST | `/api/v1/license-request` | `{"organization": "..."}` → request text (text/plain) |
| PUT | `/api/v1/license` | license text (text/plain) → 200 activated / 422 with the reason |
| GET | `/api/v1/license/status` | status, reason if invalid, license details, installation and cluster id |
| GET | `/api/v1/license/signed` | the signed text (404 if none), for the product |
| GET | `/api/v1/installation` | installation id |

Activation refuses a key that is not newer than the active one, so an old
email can't roll the terms back. A renewal is simply the next license:
create a new request, issue it, activate it.

## Product side

```java
DefaultLicenseService licensing = ProductLicensing.connect(
        URI.create("http://license-validator:8080"), "acme-suite");

if (licensing.isModuleEnabled("reports")) { ... }
licensing.checkCanAddUser(currentUserCount);   // throws LicenseLimitExceededException
LicenseStatus status = licensing.status();     // VALID / EXPIRING / GRACE / EXPIRED / INVALID / NO_LICENSE / UNAVAILABLE
```

- Base every decision on this object, never on the validator's
  `/license/status`. That endpoint is for people and monitoring.
- The result is cached for a minute. If the validator becomes unreachable,
  the product keeps its last verified license, and the validity period
  still counts down against the clock. Only when nothing has been verified
  since startup is the status `UNAVAILABLE`.
- The product checks signature, product id and cluster id. It does not
  check the installation id: that would also have to come from the
  validator, so a fake validator would simply report a matching one. What
  this means: within **one** cluster, the product can't tell two
  installations apart independently of the validator.
- Expiry warning (30 days) and grace (14 days) are the shared defaults in
  `DefaultLicenseService`. Keep the validator's `expiring-warning`/`grace`
  equal to them, so the admin page and the product agree.
- Decide what the product restricts in each status. `isModuleEnabled` and
  `maxUsers` already treat VALID, EXPIRING and GRACE as usable, and
  everything else as not.

## Signing keys

Licenses are signed with an Ed25519 private key that exists only on the
vendor side, in a password-protected PKCS#12 (`.p12`) file. Every build of
the validator and the product embeds the matching public keys. **The
skeleton ships with no private key at all:** `TrustedKeys.java` holds two
placeholder public keys whose private halves were discarded. Until you
replace them, every activation fails with `UNKNOWN_KEY`.

**Keys are created by a person, never automatically.** A key that products
in the field don't trust is the one mistake here that can't be quietly
undone: every license signed with it is rejected at the customer. So:
- keys are only created by an admin clicking "Создать ключ";
- an existing key file is never overwritten;
- a new key does not sign until it is activated separately;
- if the keys directory is empty but the audit log shows issued licenses,
  creating a key is refused, because the volume is wrong or the keys were
  lost, and they must be restored instead;
- if the active key's file is missing, the service does not start.

**Primary and reserve.** The primary key signs every license. The reserve
key is generated at the same time but kept offline, apart from the primary,
and is never used while the primary exists.

- **Primary lost** (volume and all backups gone, or password lost): put the
  reserve key on the volume and activate it. Every installation already
  accepts it, so no product update is needed. The reserve has its own
  password, so re-encrypt it with the generator's password first:
  ```bash
  keytool -importkeystore -srckeystore 2026-01-reserve.p12 -srcstoretype PKCS12 \
          -destkeystore 2026-01-reserve.gen.p12 -deststoretype PKCS12
  # asks for the reserve password, then for LICENSE_KEYSTORE_PASSWORD
  kubectl cp 2026-01-reserve.gen.p12 <generator-pod>:/data/keys/2026-01-reserve.p12
  ```
  Then click "Сделать активным" on it, and plan a release with a new
  reserve key.
- **Primary compromised:** switching to the reserve is not enough, because
  the compromised key is still trusted. Release the validator and product
  with that kid removed (and a new reserve key added), then re-issue every
  customer's license with the reserve key.
- **Planned rotation:** see "Generator service → Rotation".

**Where the key lives.** Because the generator is a running service, the
key is readable by that process. Anyone who controls the generator's pod,
volume or secret can sign licenses. The same goes for an admin login: an
admin can download the (encrypted) key file. Keep the generator in your own
infrastructure, give a handful of people access to its namespace, and
expose it only through TLS. For more isolation, keep the key in Vault /
OpenBao Transit or an HSM. There the key never leaves the device and the
service only asks it to sign, so a compromised service can sign while
someone has access, but can't take the key away. Supporting that means
another implementation behind `LicenseSigner`.

**Practical rules:** two people must be able to reach the keystore
password; never put the `.p12` in git, an image, CI or a customer's
cluster; key ids are lowercase (the JDK lowercases PKCS#12 aliases);
`keytool -list -v -keystore primary.p12` shows what's inside.

## Design notes

**Authenticity and time validity are separate.** `LicenseValidator` checks
only that a license is genuine and bound to this product/installation/
cluster. `DefaultLicenseService` applies the validity period. A genuine but
expired license still says who the customer is and what to renew; a forged
one says nothing.

**`VerifiedLicense` has no public constructor**, so no code path can treat
unverified data as a license. The validator stores the raw signed text and
re-verifies it on every read. Editing the database row breaks the signature
instead of extending the license.

**Turning the clock back does not extend a license.** The validity period is
checked against an *effective* time (`ClockGuard`), which is the latest of:
- the system clock;
- the latest time the running process has seen;
- the latest time the validator has recorded in its database (table
  `license_clock`, which only moves forward and survives restarts);
- the license's `issuedAt`, which is signed by you and can't be forged.

A clock set back into the validity period therefore changes nothing: the
license expires when it would have. If the clock is behind those anchors by
more than `clock-tolerance` (1 hour), the status carries a `clockWarning`.
The validator page shows it as a yellow note, and both the validator and
the product log it once. The product does not use the validator's recorded
time, because a fake validator could report anything. It relies on
`issuedAt` and its own uptime.

This has limits. Someone who sets the clock back, also resets the
`license_clock` row and restarts everything can still keep the clock
*inside* the license period. They can never go back past `issuedAt`.
Keeping a cluster permanently in the past breaks certificates and much
else, so this is impractical, but it is not impossible. A clock that was
wrongly *ahead* and then corrected keeps the effective time ahead until
real time catches up, so the license may look up to that much older. That
is the safe direction to err in. A side effect worth having: a cluster
clock a few minutes behind yours no longer shows a just-issued license as
"not valid yet".

**The cluster id is read live from the Kubernetes API**, by the validator
and by the product, never from a database. A database backup restored into
another cluster does not carry over a matching cluster id.

**`KeyIntegrityGuard`** cross-checks the embedded public keys against an
independent fingerprint, so a patched build needs more than one change to
accept a different key. This raises the cost of a casual key swap. It does
not stop someone determined who controls the cluster and is willing to
patch and rebuild the product. Nothing running entirely on their hardware
can stop that. Put that limit in the contract as well as in the code.

## Still to decide or wire up

- The product's behaviour in each `LicenseStatus` (UI messages, what to
  block when).
- Real keys instead of the placeholders (see "Generator service", steps 3–6).
- Image registry names and namespaces in `deploy/`.
- If several generator instances or more than a handful of operators are
  needed: move the audit log from files to a database.
