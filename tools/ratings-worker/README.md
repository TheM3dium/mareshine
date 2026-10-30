# Mod ratings (Cloudflare Worker)

The suns on a mod's page in the app: players' votes, one per mod and app install.
`worker.js` keeps them in a Cloudflare KV namespace; the app reads `GET /ratings` and sends `POST /rate` (`Ratings.java`).
Nothing about the player is stored: an install is a random id the app made.
Against spam, an install can change its vote on a mod once an hour, and one connection gives one vote a mod an hour and at most ten votes an hour in all (counted under salted hashes of its address that expire after the hour).

## The catalog

`GET /catalog` serves `catalog.json`: the mods the app offers (Search Mods) and their newest versions, which the app's Install updates compares with what is installed (`ModUpdates.java`).
A new version of a mod is a new entry there, with its download, checksums and patch; the app needs no update for it.

```sh
npx wrangler kv key put --binding RATINGS --remote catalog --path catalog.json
```

## Bug reports

`POST /report` takes a player's report from the app (Send to developer: after a freeze, or Settings → OTHER → LOGS; `ReportSend.java`).
The app removes file and folder names before sending; the report is the game's log, the newest crash, ANR and hang reports, the device and an optional note.
It is kept 30 days in KV as `report:<id>` and announced in the developer's Discord channel with a link, `GET /r/<id>`; the 32-character id is only in that message.
The player gets the first 8 characters, to quote in a GitHub issue.
Against spam: at most 512 KB, and three reports an hour from one connection (a salted hash of its address, as for votes).

The message goes through a Discord webhook (channel settings → Integrations → Webhooks).
Its address is a secret, not in this repository; anyone who has it can post to the channel:

```sh
npx wrangler secret put REPORT_WEBHOOK    # the webhook's URL
```

Without it the reports are still kept, only not announced.
The player's text sits in a code block with mentions switched off, so a note cannot ping anyone.

## Setting it up

```sh
npx wrangler login                          # once, in the browser
npx wrangler kv namespace create RATINGS    # prints the namespace's id: put it into wrangler.toml
npx wrangler deploy                         # prints the worker's URL: put it into Ratings.java (URL)
```

Free plan limits: 100,000 requests and 1,000 KV writes a day; each vote is four to five writes.
