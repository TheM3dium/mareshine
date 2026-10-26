# Mods for Mareshine (work in progress)

A mod is a folder in `mods/` in the app's data folder (`Sms/mods/<id>/` on Android).
You can also hand it out as a `.zip` of that folder, which players add with **Mods → Import mod**.
The app reads the folder's `mod.json` and builds the mod's page from it: a picture filling the screen, its rating, its description and its buttons.

An example to start from is in [`tools/mods/example-mod/`](../tools/mods/example-mod/).
Zip that folder, import the zip, and it shows up in the mod list.

No game data belongs in a mod you share: only the files your mod changes or adds.

## Your first mod in five steps

1. **Get the game's files.** In Dolphin, right-click Super Mario Sunshine (the North American disc, GMSE01) → *Properties* → *Filesystem* → right-click the disc → *Extract Entire Disc*.
   You now have the disc's folders: `data/`, `AudioRes/`, `movie/` and so on.
2. **Change a file.** Edit what you want with the usual Sunshine tools, for a stage's archive (`data/scene/*.szs`) its map, objects or messages.
3. **Put only that file into your mod**, at the same path below `files/`: a changed `data/scene/bianco0.szs` goes to `my-mod/files/data/scene/bianco0.szs`.
   For one file inside an archive, a folder named like the archive is enough: `my-mod/files/data/scene/bianco0.szs/map/message.bmg` replaces just that message file and leaves the rest of the archive as it is.
4. **Describe it in `mod.json`**: copy the one from [`tools/mods/example-mod/`](../tools/mods/example-mod/) and change `id`, `name`, `author` and `description` (all keys below).
   Add an `icon.png` and a `preview.png` if you like.
5. **Zip the folder and import it**: copy the zip to the phone, then in Mareshine *Settings → MODS → Open mods → Import mod*.
   It shows up in the list, switched off: hold it (or hold A) and switch it on, then play.

When something does not load, switch on the log (*Settings → OTHER*) and look for `[dvd] mod …` in `logs/game.log`: it says how many of your files replaced the disc's, were added, or went inside an archive.

## The folder

```
my-mod/
  mod.json        the description (below); only "id" is needed
  icon.png        the label on the mod's disc in the list (square, about 256 x 256)
  preview.png     the picture its page is filled with (16:9, about 1920 x 1080)
  files/          disc files: each replaces the disc's file at the same path,
                  or the file inside the archive named like its folder, or is added
  textures/       Dolphin-format textures (tex1_….png or .dds)
  hd/             a folder an option switches on: its own files/ and textures/
```

`overlay/` works as another name for `files/`, as in Dusklight's packages.

A zip of Dolphin textures with no `mod.json` (a texture pack as made for Dolphin) is imported as a texture-only mod named after the zip.

## mod.json

```json
{
  "id": "my-mod",
  "name": "My Mod",
  "version": "1.0",
  "author": "Me",
  "description": "What it does. Long texts scroll on the mod's page; lines starting with \"* \" become a list.",
  "author_color": "#46E06A",
  "icon": "icon.png",
  "preview": "preview.png",
  "requires": ["other-mod"],
  "options": [
    {"type": "toggle", "id": "hd", "label": "HD Textures", "folder": "hd", "default": true},
    {"type": "choice", "id": "music", "label": "Music", "values": [
      {"id": "new", "label": "New music", "folder": "music-new"},
      {"id": "old", "label": "Original"}
    ]},
    {"type": "page", "label": "Settings", "options": [
      {"type": "choice", "id": "difficulty", "label": "Difficulty", "values": ["Easy", "Normal", "Hard"], "default": "normal"},
      {"type": "toggle", "id": "timer", "label": "Speedrun timer", "text": "Shows a timer in the corner."}
    ]},
    {"type": "link", "label": "Website", "url": "https://example.com"}
  ]
}
```

| key | meaning |
| --- | --- |
| `id` | the mod's id and folder name: lower case letters, digits, `.`, `-`, `_` |
| `name`, `version`, `author`, `description` | shown on its page and in the list |
| `icon`, `preview` | pictures in the mod's folder (PNG or JPEG), or `https://` URLs. An icon with transparent corners (a whole disc's picture) is shown as it is; any other is put on a disc as its label |
| `requires` | ids of mods that must be switched on and loaded before this one |
| `incompatible` | ids of mods it does not work with: switching it on while one of them is on warns, and the player can switch those off or go on anyway |
| `image` | a whole disc image in the folder that is played instead of the player's own (only for mods too big for `files/`) |
| `code` | `true` when the mod needs code built into the app (Super Mario Eclipse) |
| `game`, `disc_id` | a game of its own kept as the disc files it changes (Super Mario Sunburn): `files/` is played over the player's disc with the game code and maker `disc_id` (such as `"GMSE03"`), so its saves stay apart from Sunshine's. One game is played at a time |
| `author_color` | the colour of "by …" at the foot of its page, as `#RRGGBB` |
| `rating` | its suns (0 to 5) until players have rated it; a tap on the suns rates it |
| `update` | an `https://` URL of a small JSON the app checks with **Install updates**: `{"version": "1.1", "url": "https://…/my-mod.zip", "page": "https://…"}`. A newer `version` is offered; `url` is downloaded and imported like the zip, else `page` is opened |
| `options` | the buttons on its page, in order |

### Other languages

Texts are shown as written, usually in English.
A mod can add its own translations next to them, with the language code after an underscore: `name_de`, `description_de`, and on buttons and values `label_de` and `text_de`.
The app shows the one for its language (`de`, `es`, `fr`, `it`) and else the plain key.

```json
"description": "New stages and challenges.",
"description_de": "Neue Level und Herausforderungen."
```

### Buttons (`options`)

A page shows up to four buttons, centred.
With more, **Back** and **Next** page through them.
The mod's ON/OFF is always at the top left, so it is not one of them.

| `type` | the button | keys |
| --- | --- | --- |
| `toggle` | on or off; lit when on | `id`, `label`, `default` (`true`/`false`), `folder` (switched on with it), `image` (a disc image played while on), `text` (a line about it on a settings page) |
| `choice` | shows the chosen value; a tap picks another | `id`, `label`, `values` (names, or objects with `id`, `label`, `folder`, `image`, `translator`: shown as "Translated by …" at the foot of the page while that value is picked), `default` (a value's id; else the first) |
| `page` | opens a page of settings | `label`, `options` (toggles and choices, shown as a list) |
| `link` | opens a web page | `label`, `url` |

The values the player picks are kept by the app, so an updated mod keeps them.
A folder an option switches on holds `files/` and `textures/` like the mod's own folder, and loads after them.

The game gets every switched-on mod's values in `SMS_MOD_OPTIONS`, as `mod.option=value;…`, for code built into the app that reads them.
