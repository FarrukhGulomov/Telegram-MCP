# Running the real TDLib adapter

> The default adapter is `MOCK` and needs none of this. Only follow these steps if you have
> explicit authorization to connect a real Telegram account, and have worked through
> [production-prerequisites.md](production-prerequisites.md) first.

## What the adapter uses

`io.tgmcp.telegram.tdlib` speaks TDLib's **JSON interface**, binding exactly the four current
entry points declared in `td/telegram/td_json_client.h`:

```c
int         td_create_client_id();
void        td_send(int client_id, const char *request);
const char *td_receive(double timeout);
const char *td_execute(const char *request);
```

(The older `td_json_client_*` functions are deprecated upstream and are not bound.)

Only read methods are used: `getChats`, `loadChats`, `getChat`, `getChatHistory`,
`getSupergroupMembers`, `getUser`, `getMessageLink`, plus the authorization methods
`setTdlibParameters`, `setAuthenticationPhoneNumber`, `requestQrCodeAuthentication`,
`checkAuthenticationCode`, `checkAuthenticationPassword` and `logOut`. The
`TelegramGateway` port has no write operation, so there is nowhere to add one without
changing the interface.

`use_secret_chats` is set to `false`: Secret Chats are out of scope.

## 1. Build libtdjson

There is no official Maven artifact for TDLib, so the native library is built or obtained
separately. Follow the current build instructions at <https://tdlib.github.io/td/build.html>
for your platform. Roughly, on Debian/Ubuntu:

```bash
sudo apt-get install -y make git zlib1g-dev libssl-dev gperf cmake g++
git clone https://github.com/tdlib/td.git && cd td
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX:PATH=/usr/local ..
cmake --build . --target install -j"$(nproc)"
```

This produces `libtdjson.so` (Linux), `libtdjson.dylib` (macOS) or `tdjson.dll` (Windows).
Building takes a while and needs several GB of RAM.

## 2. Put it on the loader path

JNA resolves the library by name (`tdjson`):

```bash
export LD_LIBRARY_PATH=/usr/local/lib:${LD_LIBRARY_PATH}      # Linux
export DYLD_LIBRARY_PATH=/usr/local/lib:${DYLD_LIBRARY_PATH}  # macOS
# or, explicitly:
java -Djna.library.path=/usr/local/lib -jar target/telegram-consent-mcp-*.jar
```

**On this branch only** (`tdlib-real-account-test`), the `Dockerfile` has a `tdlib-build`
stage that compiles `libtdjson.so` from a pinned upstream commit (TDLib does not tag releases
regularly, so a commit SHA is pinned for a reproducible build rather than following a moving
branch) and copies it into the runtime image at `/usr/local/lib`, with `LD_LIBRARY_PATH` set
accordingly. The branch this deploys to production from deliberately does not carry this
stage — the default adapter does not need it, and it turns every image build into a slow,
memory-hungry C++ compile. Do not merge this Dockerfile change back without deciding
deliberately that every future build should pay that cost.

## 3. Get an api_id and api_hash

Register an application at <https://my.telegram.org>. These identify *your application*, not
the account. Treat them as secrets.

## 4. Configure

```bash
TGMCP_TELEGRAM_ADAPTER=TDLIB
TGMCP_TELEGRAM_API_ID=1234567
TGMCP_TELEGRAM_API_HASH=your-api-hash
TGMCP_TDLIB_DIR=/var/lib/tgmcp/tdlib
# Safety interlock. The adapter refuses to start without this.
TGMCP_REAL_ACCOUNT_AUTHORIZED=true
# Do not run a real account with a derived development key.
TGMCP_SESSION_ENCRYPTION_KEY=$(openssl rand -base64 32)
```

`TGMCP_TDLIB_DIR` holds TDLib's own database, which contains session material. Put it on
encrypted storage with restrictive permissions, and back it up (or deliberately do not).

## 5. Sign in

Open the management UI → **Connection**, choose QR login, and scan the link from a Telegram
app that is already signed in. Phone-number login with a code is also supported, and both
paths handle the 2FA password step. Codes, passwords and QR tokens are handled only by those
protected endpoints; they are never persisted, logged, or reachable from an MCP tool.

## 6. Disconnecting and revoking

**Disconnect** in the UI ends the local session and destroys local session material. It does
not necessarily terminate the session inside Telegram. To do that, open Telegram →
**Settings → Devices → Active sessions** and terminate the session for this application. The
UI says this on the disconnect action.

## Known gaps

`fetchRevisions` returns an empty list for the TDLib adapter. TDLib delivers edits and
deletions as pushed `updateMessageContent` / `updateDeleteMessages` updates rather than as a
queryable history, and routing those into the sync worker is not finished. Until it is, the
TDLib adapter will not notice an upstream edit or deletion of a message it has already
stored. The mock adapter does implement revisions, and the sync worker's handling of them is
tested against it.
