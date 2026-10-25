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

## Setting it up

```sh
npx wrangler login                          # once, in the browser
npx wrangler kv namespace create RATINGS    # prints the namespace's id: put it into wrangler.toml
npx wrangler deploy                         # prints the worker's URL: put it into Ratings.java (URL)
```

Free plan limits: 100,000 requests and 1,000 KV writes a day; each vote is four to five writes.
