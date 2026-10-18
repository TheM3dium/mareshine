# Achievements: the shared format

Mareshine keeps its achievements in two plain JSON files, so that other
programs (another port, a launcher, a plugin that gathers achievements from
several games) can read them, write to them and merge them without anyone
losing what they unlocked.

## Ids

Every achievement has a fixed id: a lowercase dotted string starting with the
game, such as `sms.story.first_shine` or `sms.boss.petey.nohit`.

- An id is never renamed and never reused. Titles and descriptions may change; the id does not.
- An achievement that is taken out stays in the definitions, marked `"retired": true`, so old unlocks stay valid.
- The game part covers every region: Super Mario Sunshine is `sms` for GMSE01, GMSP01 and GMSJ01 alike.

## Definitions: `achievements/sms.json`

Shipped with the program (in Mareshine: `android/app/src/main/assets/achievements/sms.json`,
written by `tools/achievements_defs.py`). Read only.

```json
{
  "format": "achievement-definitions",
  "schema": 1,
  "game": "sms",
  "games": ["GMSE01", "GMSP01", "GMSJ01"],
  "categories": [
    { "id": "story", "icon": "🌟", "title": { "en": "Story", "de": "Story" } }
  ],
  "achievements": [
    {
      "id": "sms.shines.30",
      "category": "shines",
      "target": 30,
      "secret": false,
      "title": { "en": "Beaming", "de": "Strahlemann" },
      "description": { "en": "Collect 30 Shine Sprites.", "de": "Sammle 30 Shines." }
    }
  ]
}
```

- `target`: for achievements with a progress bar, the value that unlocks it; `0` for none.
- `secret`: shown as "???" until unlocked.
- Texts are per language code, `en` always present; a missing language falls back to `en`.

## What was unlocked: `<data folder>/achievements/unlocked.json`

```json
{
  "format": "achievements",
  "schema": 1,
  "game": "sms",
  "unlocked": {
    "sms.story.first_shine": {
      "at": 1792291570,
      "port": "mareshine",
      "version": "1.4.0",
      "cheats": false,
      "save_states": false
    }
  },
  "progress": {
    "sms.shines.30": 12
  }
}
```

- `at`: when it was unlocked, Unix time in seconds (UTC).
- `port`, `version`: the program that unlocked it.
- `cheats`: a cheat was on when it was unlocked. `save_states`: a save state had been loaded in that session.
  Programs decide themselves what to do with these; Mareshine only shows them.
- `progress`: how far the player is towards an achievement with a target.
- No personal data: no names, accounts, device ids or paths.

### Writing and merging

Any program may write the file. To keep everyone's unlocks:

1. Read the file again right before writing.
2. Merge: the union of both sides' `unlocked`; when both have an id, the **earliest** `at` wins
   (with its other fields). For `progress`, the higher value wins.
3. Keep every entry you do not know (other games, newer ids) unchanged.
4. Write to a temporary file in the same folder and rename it over `unlocked.json`.

So the same achievement unlocked in two programs is one entry, and nothing is ever lost or unlocked twice.

## Mareshine's own files

`<data folder>/achievements/runs.json` holds Mareshine's play-time timers for the timed achievements
(per save file A/B/C). It is not part of the shared format.
