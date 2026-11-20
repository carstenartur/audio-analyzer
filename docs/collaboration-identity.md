# Collaboration identity and participant privacy

Collaboration session authority comes from a server-issued client credential. The JSON actor id is
only a public selector. A browser cannot authorize an operation by copying an owner id or by sending
another `userId`, account handle, role or verification flag.

## Identity and sign-in

`POST /workflow/identity` accepts an optional display-name preference and an optional existing actor
id for reconnect. The server issues a random actor id and a 256-bit random credential in an HttpOnly,
SameSite=Strict cookie. HTTPS requests receive Secure cookies. The browser stores only the public
actor projection in tab-scoped sessionStorage. Credentials never appear in JSON responses, event
URLs, operation history or public participant projections.

When the servlet request has a security `Principal`, the identity is `AUTHENTICATED`. Its subject is
derived server-side from the principal name. The server checks that same principal on every use of
the actor cookie. A different principal, or a signed-out request, cannot reuse that actor credential.
The deployment supplies its normal servlet/Spring authentication integration; this feature does not
install a login provider or accept proxy identity headers. If no principal is present, the identity
is `ANONYMOUS` for a generated pseudonym or `SELF_ASSERTED` for a chosen pseudonym. Both are visibly
unverified.

The displayed name is independent of the account handle. A generated authenticated display name is
`Participant` plus a public short code, so hidden handles are not disclosed through a default name.
Actor display names and subjects are immutable once issued, preserving historical attribution. To
choose another pseudonym, leave the current session and use **Start a new client identity**. That
creates separate attribution and does not transfer ownership or personal-undo rights.

## Multiple tabs, reconnect and ownership

Each newly issued client has a distinct actor id and cookie name. Creating a second tab's identity
never replaces the first tab's credential, even when both tabs use the same authenticated account.
Tabs therefore have independent personal undo, and session ownership belongs to the creating client.
Reconnect and reload resume that same client and retain its attribution; repeated joins are
idempotent. Browsers supporting Web Locks also detect duplicated tabs that inherited sessionStorage
and issue a new actor for the second live tab. Without Web Locks, a duplicated tab using the same
stored selector represents the same client; ordinary fresh tabs still issue separate actors.

JSON calls select their client with `X-Workflow-Actor`. Native EventSource requests use the public
`actorId` query parameter while the matching HttpOnly cookie supplies the proof of possession. A
single-identity API client may omit the selector when exactly one collaboration cookie is present.
The server verifies credential and principal binding before session membership, history, undo/redo,
operations, presence, close or SSE access. Session details, projections and event streams require
joined membership. Session listing includes the client's joined sessions and sessions it owns.
Membership checks and workflow/metadata reads use one immutable capture under the session lock.
SSE authorization and subscription use that same lock, so closing and recreating a session id cannot
retarget an authorized read or stream to another client's private workspace.
An existing event stream ends when its client leaves, and no further frames are sent once that
client's credential expires. Streams are also bounded to 30 minutes; reconnect revalidates the
current principal and membership. Browser presence heartbeats revalidate the principal every five
seconds, so account changes end the active UI session and offer a new client identity.
The owner can close a session using its credential even after leaving it. Shared-session ids remain
joinable by another valid client; private workspaces remain limited to their owner client.

## Workflow-run access

A live-session workflow run uses the same server-bound identity boundary. Starting a run requires
joined membership. Authorization captures the exact immutable workflow and session metadata under
one lock. Run compilation consumes that capture, including its expected revision, without resolving
the session id again. Listing, inspecting, reading results and cancelling a session-derived run require
membership of that original session instance, or the valid credential of the run creator or original
session owner. Those two clients retain access after departure or closure, so they can retrieve a
completed result. Other departed participants lose access. Identity expiry and principal binding are
checked on every request.

The process-local run registry keeps matching process-local access records. Idempotent start retries
never transfer ownership. Recreating a session with the same id does not grant access to runs from
the earlier session. The server checks the stored run before accepting a repeated start-command id,
and unauthorized runs are omitted from run listings. Stored-commit runs keep their existing access
contract; collaboration identity does not introduce a new account/branch permission system for them.

## Durable attribution

In Hibernate mode, the existing shared SessionFactory persists immutable identity records in
`workflow_collaboration_identity`, introduced by collaboration migration V4. Only a SHA-256 hash of
the random credential is stored. Session owner attribution and operation actor ids continue to use
the existing durable collaboration schema, and history resolves those ids to the persisted public
identity projection. No bearer secret is stored in either identity rows or semantic history.

After a complete server restart, active transport connections and presence remain transient. Clients
resume their cookie-backed identities and rejoin the recovered session before opening SSE. Their
old operations and personal undo retain the same actor. Memory and filesystem collaboration modes
have process-local identity/session state; after losing that state, start a new client identity.
Existing collaboration data from before V4 retains its original self-asserted attribution. There is
no automatic claim of a legacy owner by submitting the old id: such a claim would restore the
impersonation vulnerability.

Credentials expire 30 days after issuance, enforced by both the server and the cookie lifetime.
Reconnect does not extend that deadline. Retain the browser's cookie and tab identity for the lifetime
of that client. Expiry or clearing credentials removes its proof of ownership; knowing the actor id
is not recovery proof, and a new client does not inherit previous ownership or personal-undo rights.
The expired identity record remains available for historical attribution. Deployment backup/retention
policies should include the identity table along with collaboration state.

## Participant privacy and activity

Account handles are hidden by default. A deployment may explicitly disclose verified handles with:

```properties
workbench.collaboration.identity.show-account-handles=true
```

The policy applies to participant responses, SSE and durable-history attribution. Internal subjects
and credential hashes are never returned. The primary participant row shows display name, short
public disambiguation code, **You**, owner/editor role, anonymous/unverified/verified indicator and
connection state. Full actor ids appear only in the expandable client-diagnostics section. Current
shared-session participants are editors; no client-provided viewer/editor/owner role changes authority.

Presence accepts only `selection`, `executingRun`, `cursor.x`, `cursor.y`, `viewport.x`, `viewport.y`
and `viewport.zoom`. Identifier fields are bounded to 128 ASCII identifier characters; numeric values
must be finite and within ±1,000,000. Unknown fields are discarded. The server timestamps presence;
client timestamps cannot keep a stale presence sample alive. The UI renders only the allow-listed
activity labels. Heartbeats maintain live status, activity becomes idle after 15 seconds and offline
after 60 seconds, and transient cursor/selection samples expire after 15 seconds. Last activity and
membership remain distinct from those samples and from durable semantic history.

## Verification

Focused server tests cover cookie proof, copied-id takeover, forged authority fields, immutable
names, duplicate display names, principal switching, handle privacy, idempotent rejoin, bounded
presence and complete Hibernate restart with history attribution. Frontend tests cover public
identity labels, same-name disambiguation, connection state and presence rendering. Browser scenarios
obtain identities through the real HTTP endpoint; the durable restart scenario carries the same
browser credential across the process boundary.
