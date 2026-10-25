# Mod ratings (Cloudflare Worker)

The suns on a mod's page in the app: players' votes, one per mod and app install.
`worker.js` keeps them in a Cloudflare KV namespace; the app reads `GET /ratings` and sends `POST /rate` (`Ratings.java`).
Nothing about the player is stored: an install is a random id the app made.
Against spam, an install can change its vote on a mod once an hour, and one connection gives at most ten votes an hour (counted under a salted hash of its address that expires after the hour).

## Setting it up

```sh
npx wrangler login                          # once, in the browser
npx wrangler kv namespace create RATINGS    # prints the namespace's id: put it into wrangler.toml
npx wrangler deploy                         # prints the worker's URL: put it into Ratings.java (URL)
```

Free plan limits: 100,000 requests and 1,000 KV writes a day; each vote is three to four writes.
