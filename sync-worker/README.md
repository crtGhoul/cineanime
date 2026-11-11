# CineAnime 100% Free Live Cross-Device Sync Setup

CineAnime encrypts your **Continue Watching**, **Watched Episode checkmarks**, **My Watchlist**, **Stremio Addons**, **Server/SUB-DUB preferences**, and (optionally) **4K Debrid key** locally with **AES-256-GCM** before uploading.

Pick whichever **$0 / forever-free** option is easiest for you—once connected on your PC, simply scan the **Pairing QR Code** on your phone/TV and every device stays synced automatically.

---

## Option A: Private GitHub Gist ($0 Forever, Zero Extra Accounts)

1. Go to GitHub -> **Settings -> Developer settings -> Personal access tokens -> Fine-grained tokens -> Generate new token** (or Classic token with only the **`gist`** checkbox).
   - For a fine-grained token: under **Permissions -> Account permissions**, set **Gists** to **Read and write**.
2. Open CineAnime on your PC -> click **`🔗 Sync`** in the top bar -> select **Private GitHub Gist**.
3. Paste the token and click **Generate Link Code & Connect**.
4. Tap **Show Phone Pairing QR / Link**, scan it with your phone camera, and confirm **Link Device**. Done!

---

## Option B: Free Cloudflare Worker + KV ($0 Plan, Best for Short Room Codes)

1. Sign into the free Cloudflare Dashboard (**Workers & Pages**) -> **Create -> Create Worker** (e.g. `cineanime-sync`) -> paste [`worker.js`](./worker.js) and **Deploy**.
   - Or run locally via Wrangler:
     ```bash
     npx wrangler kv namespace create SYNC_KV
     # paste the returned id into wrangler.toml
     npx wrangler deploy
     ```
2. In your Worker settings -> **Bindings -> Add -> KV Namespace**, set Variable name **`SYNC_KV`** to a new free KV namespace (e.g. `CINEANIME_SYNC_KV`), then re-save/deploy.
3. Open CineAnime on your PC -> click **`🔗 Sync`** -> select **Cloudflare Worker**, paste your `https://cineanime-sync.<your-subdomain>.workers.dev` URL, and click **Create Link Code** (`CINE-XXXX-XXXX`).
4. Scan the QR code (or type the URL + Link Code) on your Phone, AppMaka, or Google TV!

---

## Option C: Free Firebase Realtime Database ($0 Spark Plan, Zero Code)

1. Create a free Firebase project (`Spark` plan — $0, no credit card) -> **Build -> Realtime Database -> Create Database**.
2. Paste these security rules under **Realtime Database -> Rules**:
   ```json
   {
     "rules": {
       "cineanime_sync": {
         "$roomId": {
           ".read": "$roomId.matches(/^[a-f0-9]{32,64}$/)",
           ".write": "$roomId.matches(/^[a-f0-9]{32,64}$/) && newData.hasChildren(['iv', 'ciphertext'])"
         }
       }
     }
   }
   ```
3. Copy your database URL (`https://<your-project>-default-rtdb.firebaseio.com`), paste it in CineAnime -> **`🔗 Sync`** -> **Firebase RTDB**, create a Link Code, and scan the QR on your phone/TV.
