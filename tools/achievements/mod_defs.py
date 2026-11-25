#!/usr/bin/env python3
"""The achievements of the mods with a game of their own (Super Mario Sunburn,
Super Mario Eclipse): writes their definitions for the app
(android/app/src/main/assets/achievements/<game>.json, the shared format of
docs/ACHIEVEMENTS-FORMAT.md) and their list for the game
(platform/achievements/achievements_list_<game>.h).

An entry whose id ends like one of Sunshine's (sunburn.boss.petey.nohit and
sms.boss.petey.nohit) is checked by Sunshine's code (ach_game.cpp): the
platform side hands it on to the running game's achievement of the same name.
Such an entry takes Sunshine's texts in every language unless it says
otherwise here. The others are checked in ach_mods.cpp.

Run it after changing anything here:  python3 tools/achievements/mod_defs.py
"""
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
ASSETS = os.path.join(ROOT, "android", "app", "src", "main", "assets", "achievements")
NATIVE = os.path.join(ROOT, "platform", "achievements")

SMS = json.load(open(os.path.join(ASSETS, "sms.json"), encoding="utf-8"))
SMS_BY_SUFFIX = {a["id"].split(".", 1)[1]: a for a in SMS["achievements"]}
SMS_CATS = {c["id"]: c for c in SMS["categories"]}


def T(en, de):
    return {"en": en, "de": de}


def shared(suffix, category=None, title=None, description=None, target=None):
    """One of Sunshine's, checked by Sunshine's code; its texts unless given."""
    a = SMS_BY_SUFFIX[suffix]
    return {"suffix": suffix, "category": category or a["category"],
            "target": a.get("target", 0) if target is None else target, "unit": a.get("unit"),
            "secret": a.get("secret", False), "title": title or a["title"],
            "description": description or a["description"]}


def own(suffix, category, title, description, target=0, secret=False, unit=None):
    return {"suffix": suffix, "category": category, "target": target, "unit": unit, "secret": secret,
            "title": title, "description": description}


def cat(cid, icon, title):
    return {"id": cid, "icon": icon, "title": title}


WORLDS = ["bianco", "ricco", "gelato", "pinna", "sirena", "noki", "pianta"]
BOSSES = ["petey", "gooper", "wiggler", "mecha_bowser", "phantamanta", "king_boo", "eely_mouth", "bowser"]
NOHIT_DE = "ohne im Kampf Schaden zu nehmen"

# --- Super Mario Sunburn ---------------------------------------------------------------
# Sunshine's structure (the same 120 Shines and flags): Sunshine's checks, plus
# its own: the win condition, Corona opened, New Game Plus and the Z exit.
sunburn_cats = [SMS_CATS["story"], SMS_CATS["boss"], SMS_CATS["shines"], SMS_CATS["worlds"], SMS_CATS["plaza"],
                cat("ngplus", "🔥", T("New Game Plus", "Neues Spiel+")), SMS_CATS["live"], SMS_CATS["fun"],
                SMS_CATS["pro"], SMS_CATS["glitch"]]
sunburn = [
    shared("story.first_shine", description=T("Get your first Shine Sprite.", "Hol deinen ersten Shine.")),
    shared("story.yoshi"), shared("story.rocket_nozzle"), shared("story.turbo_nozzle"),
    own("story.corona_open", "story", T("The Mountain Calls", "Der Berg ruft"),
        T("Enter Corona Mountain.", "Betritt den Corona-Berg.")),
    own("story.win_70", "story", T("Seventy Will Do", "Siebzig reichen"),
        T("Defeat Bowser with the win condition \"70 Shines\".", "Besiege Bowser mit der Siegbedingung „70 Shines“.")),
    own("story.win_shadow", "story", T("Shadow Hunt", "Schattenjagd"),
        T("Defeat Bowser with the win condition \"All Shadow Mario\".",
          "Besiege Bowser mit der Siegbedingung „Alle Shadow Mario“.")),
    shared("story.shadow_mario_all"), shared("story.true_ending"),
] + [shared(f"boss.{b}.{k}") for b in BOSSES for k in ("defeat", "nohit")] + [
    shared(f"shines.{n}") for n in (3, 10, 30, 50, 70, 100, 120)] + [
    shared("blue_coins.50"), shared("blue_coins.100"), shared("blue_coins.240"), shared("blue_coins.trade_all"),
] + [shared(f"world.{w}.{k}") for w in WORLDS for k in ("episodes", "secrets", "coins100", "blue_coins")] + [
    shared(s) for s in ("plaza.shines", "plaza.blue_coins", "corona.blue_coins", "bonus.super_slide", "bonus.pachinko",
                        "bonus.lily_pad", "bonus.turbo_track", "bonus.red_coin_field", "airstrip.after_corona",
                        "nozzle_boxes")] + [
    own("ngplus.unlocked", "ngplus", T("Once More from the Top", "Nochmal von vorn"),
        T("Unlock New Game Plus.", "Schalte Neues Spiel+ frei.")),
    own("ngplus.bowser", "ngplus", T("Half a Sun", "Halbe Sonne"),
        T("Defeat Bowser in New Game Plus.", "Besiege Bowser im Neuen Spiel+.")),
    own("ngplus.shines_30", "ngplus", T("Turbo Only", "Nur mit Turbo"),
        T("Collect 30 Shine Sprites in New Game Plus.", "Sammle 30 Shines im Neuen Spiel+."), target=30),
    own("ngplus.boss_nohit", "ngplus", T("Glass House", "Glashaus"),
        T("Defeat a boss in New Game Plus without taking damage.",
          "Besiege einen Boss im Neuen Spiel+, ohne Schaden zu nehmen.")),
    own("fun.z_exit", "fun", T("Emergency Exit", "Notausgang"),
        T("Leave a level with Z.", "Verlass ein Level mit Z.")),
] + [shared(s) for s in ("live.sunglasses", "live.shirt", "live.flagpole", "fun.stuck_in_goop", "fun.on_fire",
                         "fun.electrocuted", "fun.thrown", "fun.lives_99", "fun.coins_300", "fun.yoshi_colors",
                         "fun.all_movies", "fun.playtime_10h", "fun.game_over", "fun.yoshi_juice",
                         "pro.speedrun", "pro.speedrun_120", "pro.hacker",
                         "glitch.out_of_bounds", "glitch.under_floor", "glitch.too_fast", "glitch.too_high",
                         "glitch.hunter")]

# --- Super Mario Eclipse ---------------------------------------------------------------
# Sunshine's worlds and 120 Shines plus its own areas (Shine ids 120 to 239,
# eclipse/src/stage/tracker.cpp), 420 blue coins, its own bosses and characters.
eclipse_cats = [SMS_CATS["story"], SMS_CATS["boss"], cat("char", "🎭", T("Characters", "Charaktere")),
                SMS_CATS["shines"], SMS_CATS["worlds"], cat("areas", "🗺️", T("New areas", "Neue Gebiete")),
                SMS_CATS["plaza"], SMS_CATS["fun"], SMS_CATS["pro"], SMS_CATS["glitch"]]
AREAS = [  # suffix, English, German, blue coins on their own (count) or None, secret level
    ("erto", "Erto", "Erto", 20, True), ("lighthouse", "Lighthouse", "Leuchtturm", 20, True),
    ("warship", "Warship", "Kriegsschiff", 20, True), ("lacrima", "Lacrima", "Lacrima", 20, True),
    ("cruiser", "Cruiser", "Kreuzer", 10, True), ("pianta_pit", "Pianta Pit", "Pianta-Grube", 20, False),
    ("mario_dream", "Mario's Dream", "Marios Traum", None, False), ("lancia", "Lancia", "Lancia", None, False),
    ("vaporwave", "Vaporwave", "Vaporwave", None, False), ("yoshi_village", "Yoshi Village", "Yoshi-Dorf", None, False),
    ("red_lily", "Red Lily", "Red Lily", None, False), ("peach_beach", "Peach Beach", "Peach Beach", None, False),
    ("spettro", "Spettro Casino", "Spettro-Casino", None, False),
]
BLUE_TITLES = {"erto": T("Erto in Blue", "Erto in Blau"), "lighthouse": T("Blue Beacon", "Blaues Leuchtfeuer"),
               "warship": T("Blue Fleet", "Blaue Flotte"), "lacrima": T("Blue Tears", "Blaue Tränen"),
               "cruiser": T("Blue Deck", "Blaues Deck"), "pianta_pit": T("Blue Depths", "Blaue Tiefe")}
areas = []
for k, en, de, blue, secret in AREAS:
    if blue:
        areas.append(own(f"area.{k}", "areas", T(f"{en} Complete", f"{de} komplett"),
                         T(f"Collect every Shine Sprite in {en}, its 100-coin Shine included.",
                           f"Sammle alle Shines in {de}, den 100-Münzen-Shine eingeschlossen.")))
        areas.append(own(f"area.{k}.blue_coins", "areas", BLUE_TITLES[k],
                         T(f"Collect all {blue} blue coins in {en}.", f"Sammle alle {blue} blauen Münzen in {de}."),
                         target=blue))
    else:
        areas.append(own(f"area.{k}", "areas", T(f"{en} Complete", f"{de} komplett"),
                         T(f"Collect every Shine Sprite and every blue coin in {en}.",
                           f"Sammle alle Shines und alle blauen Münzen in {de}.")))
    if secret:
        areas.append(own(f"area.{k}.secret", "areas", T(f"{en}: Secret Level", f"{de}: Geheimlevel"),
                         T(f"Get the Shine Sprite of {en}'s secret level.", f"Hol den Shine im Geheimlevel von {de}."),
                         target=2 if k == "lacrima" else 0))
areas += [
    own("area.isle_delfino", "areas", T("Island Tour", "Inselrundfahrt"),
        T("Collect all 8 Shine Sprites of Isle Delfino and the one in the train car.",
          "Sammle alle 8 Shines von Isle Delfino und den im Zugwaggon.")),
    own("area.all_secrets", "areas", T("Around Every Corner", "Hinter jeder Ecke"),
        T("Get the Shine Sprites of all 12 secret levels.", "Hol die Shines aller 12 Geheimlevel."), target=12),
    own("area.all_complete", "areas", T("Globetrotter", "Weltenbummler"),
        T("Complete all 14 new areas.", "Schließ alle 14 neuen Gebiete ab."), target=14),
]
ECL_BOSS = [("dark_zhine", T("Dark Zhine", "Dark Zhine"), T("Dark Sun", "Dunkle Sonne"), T("Silver Lining", "Lichtblick")),
            ("fiery_petey", T("Fiery Petey", "Feuer-Petey"), T("Hot Veggies", "Heißes Gemüse"), T("Fireproof", "Feuerfest")),
            ("bowser_car", T("Bowser's Kart", "Bowsers Kart"), T("Kart Duel", "Kart-Duell"), T("Pole Position", "Pole-Position"))]
WORLD_EN = {"bianco": "Bianco Hills", "ricco": "Ricco Harbor", "gelato": "Gelato Beach", "pinna": "Pinna Park",
            "sirena": "Sirena Beach", "noki": "Noki Bay", "pianta": "Pianta Village"}
eclipse = [
    shared("story.first_shine", title=T("Old Island, New Luck", "Alte Insel, neues Glück")),
    shared("story.yoshi"), shared("story.rocket_nozzle"), shared("story.turbo_nozzle"), shared("story.shadow_mario_all"),
] + [shared(f"boss.{b}.{k}") for b in BOSSES for k in ("defeat", "nohit")] + [
    x for k, n, a, b in ECL_BOSS for x in (
        own(f"boss.{k}.defeat", "boss", a, T(f"Defeat {n['en']}.", f"Besiege {n['de']}.")),
        own(f"boss.{k}.nohit", "boss", b, T(f"Defeat {n['en']} without taking damage in the fight.",
                                            f"Besiege {n['de']}, {NOHIT_DE}.")))] + [
    own("char.luigi", "char", T("The Green Brother", "Der grüne Bruder"), T("Unlock Luigi.", "Schalte Luigi frei.")),
    own("char.piantissimo", "char", T("The Champion", "Der Champion"),
        T("Unlock Piantissimo.", "Schalte Piantissimo frei.")),
    own("char.all_played", "char", T("Role Play", "Rollenspiel"),
        T("Play as all four characters.", "Spiel mit allen vier Figuren."), target=4),
] + [
    (shared(f"shines.{n}", description=T(f"Collect {n} Shine Sprites.", f"Sammle {n} Shines.")) if n in (10, 30, 120)
     else own(f"shines.{n}", "shines", t, T(f"Collect {n} Shine Sprites.", f"Sammle {n} Shines."), target=n))
    for n, t in [(10, None), (30, None), (60, T("Daybreak", "Morgengrauen")), (90, T("Sunrise", "Sonnenaufgang")),
                 (120, None), (150, T("Midday Sun", "Mittagssonne")), (180, T("Heatwave", "Hitzewelle")),
                 (210, T("Dazzling", "Gleißend")), (240, T("Everlasting Light", "Ewiges Licht"))]] + [
    shared("blue_coins.50"), shared("blue_coins.100"),
    own("blue_coins.200", "shines", T("Blue Hour", "Blaue Stunde"),
        T("Collect 200 blue coins.", "Sammle 200 blaue Münzen."), target=200),
    own("blue_coins.300", "shines", T("Deep Blue", "Tiefblau"),
        T("Collect 300 blue coins.", "Sammle 300 blaue Münzen."), target=300),
    own("blue_coins.420", "shines", T("All the Blues", "Alle Blauen"),
        T("Collect all 420 blue coins.", "Sammle alle 420 blauen Münzen."), target=420),
    shared("blue_coins.trade_all", target=42,
           description=T("Trade blue coins for all 42 Shine Sprites.", "Tausche blaue Münzen gegen alle 42 Shines.")),
] + [x for w in WORLDS for x in (
    own(f"world.{w}.shines", "worlds", SMS_BY_SUFFIX[f"world.{w}.episodes"]["title"],
        T(f"Collect all 10 Shine Sprites in {WORLD_EN[w]}.", f"Sammle alle 10 Shines in {WORLD_EN[w]}."), target=10),
    shared(f"world.{w}.coins100"), shared(f"world.{w}.blue_coins"))] + areas + [
    shared("plaza.shines"),
    own("plaza.coins100", "plaza", T("Plaza Millionaire", "Plaza-Millionär"),
        T("Get both 100-coin Shines of Delfino Plaza.", "Hol beide 100-Münzen-Shines der Delfino-Plaza."), target=2),
    shared("plaza.blue_coins", target=20, description=T("Collect all 20 blue coins in Delfino Plaza.",
                                                        "Sammle alle 20 blauen Münzen in Delfino Plaza.")),
    shared("corona.blue_coins"),
    own("fun.frozen", "fun", T("Icicle", "Eiszapfen"), T("Freeze in the cold.", "Frier in der Kälte ein.")),
    own("fun.pizza_box", "fun", T("Delivery Service", "Lieferservice"),
        T("Bump into a pizza box.", "Stoß eine Pizzaschachtel an.")),
    own("fun.star_bits_100", "fun", T("Stardust", "Sternenstaub"),
        T("Collect 100 Star Bits.", "Sammle 100 Sternenteile."), target=100),
    own("fun.launch_star", "fun", T("Star Flight", "Sternenflug"),
        T("Fly with a Launch Star.", "Flieg mit einem Startstern.")),
    own("fun.green_yoshi", "fun", T("Green Light", "Grünes Licht"),
        T("Ride the green Yoshi.", "Reite den grünen Yoshi.")),
    own("fun.flat_2d", "fun", T("Thinking Flat", "Flach gedacht"),
        T("Play through a 2D section.", "Spiel einen 2D-Abschnitt durch.")),
    own("fun.portal", "fun", T("Where Am I?", "Wo bin ich?"),
        T("Travel through a portal.", "Reise durch ein Portal.")),
    own("fun.cruise", "fun", T("Cruise", "Kreuzfahrt"),
        T("Ride the mini cruiser.", "Fahr mit dem Mini-Kreuzer.")),
    own("fun.luigi_boss", "fun", T("Sibling Rivalry", "Bruderduell"),
        T("Defeat a boss as Luigi.", "Besiege als Luigi einen Boss.")),
    own("fun.shadow_shine", "fun", T("Evil Twin", "Böser Zwilling"),
        T("Get a Shine Sprite as Shadow Mario.", "Hol als Shadow Mario einen Shine."), secret=True),
] + [shared(s) for s in ("fun.stuck_in_goop", "fun.on_fire", "fun.electrocuted", "fun.lives_99", "fun.coins_300",
                         "fun.game_over", "fun.yoshi_juice", "pro.hacker")] + [
    own("pro.speedrun_240", "pro", T("Eclipse Sprint", "Finsternis-Sprint"),
        T("Have all 240 Shine Sprites within 12 hours of play time in one save file.",
          "Hab alle 240 Shines innerhalb von 12 Stunden Spielzeit in einem Spielstand.")),
] + [shared(s) for s in ("glitch.out_of_bounds", "glitch.under_floor", "glitch.too_fast", "glitch.too_high",
                         "glitch.hunter")]

# --- Super Mario Tropical Freeze ------------------------------------------------
# Sunshine in winter: the same levels, Shines and texts. Sunshine's list (but
# its app setting, the Fine-Tuner), winter titles here and there, and ice.
TF_WINTER = {
    "story.first_shine": T("Holiday with Frostbite", "Urlaub mit Frostbeulen"),
    "story.shadow_mario_all": T("Shadows in the Snow", "Schatten im Schnee"),
    "story.true_ending": T("Winter Holiday at Last", "Endlich Winterurlaub"),
    "boss.petey.defeat": T("Frost Flower Picked", "Eisblume gepflückt"),
    "boss.gooper.defeat": T("Frozen Sushi", "Tiefkühl-Sushi"),
    "boss.wiggler.nohit": T("Barefoot in the Snow", "Barfuß über den Schnee"),
    "boss.mecha_bowser.defeat": T("Bobsleigh Duel", "Bobbahn-Duell"),
    "boss.bowser.nohit": T("Ice Bath Master", "Eisbad-Meister"),
    "shines.3": T("First Flakes", "Erste Flocken"),
    "shines.30": T("Snowman", "Schneemann"),
    "shines.50": T("Winter Solstice", "Wintersonnenwende"),
    "shines.70": T("Sun on the Snow", "Sonne im Schnee"),
    "shines.120": T("Thaw", "Tauwetter"),
    "blue_coins.50": T("Frozen Blue", "Blau gefroren"),
    "world.gelato.episodes": T("Ski Patrol", "Pistenwärter"),
    "world.gelato.secrets": T("Snow Sculptor", "Schneekünstler"),
    "world.gelato.blue_coins": T("Every Snowflake", "Jede Schneeflocke"),
    "world.noki.episodes": T("Ice Diver", "Eistaucher"),
    "world.noki.blue_coins": T("Every Icefall", "Jeder Eisfall"),
    "world.pinna.coins100": T("Winter Market Till", "Wintermarkt-Kasse"),
    "world.bianco.blue_coins": T("Slopes Covered", "Hügel abgefahren"),
    "plaza.shines": T("Snow Shoveller", "Schneeschipper"),
    "plaza.blue_coins": T("Black Ice", "Pflaster-Glatteis"),
    "bonus.super_slide": T("Sledge Ride", "Schlittenpartie"),
    "live.sunglasses": T("Snow Goggles", "Schneebrille"),
    "live.shirt": T("Hawaii in the Snow", "Hawaii im Schnee"),
    "fun.on_fire": T("Warm at Last", "Endlich warm"),
    "fun.yoshi_juice": T("Yoshi Slush", "Yoshi-Slush"),
    "fun.playtime_10h": T("Hibernation", "Winterschlaf"),
}
TF_DESC = {
    "live.sunglasses": T("Borrow sunglasses from a Pianta against the glare of the snow.",
                         "Leih dir von einem Pianta eine Sonnenbrille gegen das Gleißen im Schnee."),
    "live.shirt": T("Borrow a Hawaiian shirt from a Pianta, cold or not.",
                    "Leih dir von einem Pianta ein Hawaiihemd, trotz der Kälte."),
    "fun.yoshi_juice": T("Let Yoshi turn to slush in the icy water.", "Lass Yoshi im eiskalten Wasser zu Slush werden."),
}


def winter(suffix, en_de):
    """Sunshine's texts with the winter title (German and English; the others keep Sunshine's)."""
    title = dict(SMS_BY_SUFFIX[suffix]["title"])
    title.update(en_de)
    if suffix not in TF_DESC:
        return title, None
    desc = dict(SMS_BY_SUFFIX[suffix]["description"])
    desc.update(TF_DESC[suffix])
    return title, desc


tf_cats = [SMS_CATS["story"], cat("ice", "❄️", T("Ice", "Eis")), SMS_CATS["boss"], SMS_CATS["shines"],
           SMS_CATS["worlds"], SMS_CATS["plaza"], SMS_CATS["live"], SMS_CATS["fun"], SMS_CATS["pro"],
           SMS_CATS["glitch"]]
tf = []
for a in SMS["achievements"]:
    suf = a["id"].split(".", 1)[1]
    if suf == "fun.fine_tuner":
        continue  # the app's Settings: Sunshine's
    if suf in TF_WINTER:
        title, desc = winter(suf, TF_WINTER[suf])
        tf.append(shared(suf, title=title, description=desc))
    else:
        tf.append(shared(suf))
tf += [
    own("ice.skate_10s", "ice", T("Figure Skating", "Eiskunstlauf"),
        T("Slide over ice for 10 seconds in a row.", "Rutsch 10 Sekunden am Stück über Eis.")),
    own("ice.momentum_jump", "ice", T("Taking the Momentum", "Schwung mitgenommen"),
        T("Jump off an ice floor at full speed.", "Spring mit vollem Tempo von einer Eisfläche ab.")),
    own("ice.all_worlds", "ice", T("Ice Desert", "Eiswüste"),
        T("Slide over ice in all seven worlds.", "Rutsch in allen sieben Welten über Eis."), target=7),
    own("ice.no_brakes", "ice", T("Brake Failure", "Bremsversagen"),
        T("Slide off the ice and lose a life.", "Rutsch auf Eis in einen Abgrund."), secret=True),
]

GAMES = [
    ("sunburn", "Super Mario Sunburn", ["GMSE03"], sunburn_cats, sunburn),
    ("eclipse", "Super Mario Eclipse", ["GMSE04"], eclipse_cats, eclipse),
    ("tf", "Super Mario Tropical Freeze", ["GMSE12"], tf_cats, tf),
]


def enum_name(game, suffix):
    return (game + "_" + suffix).upper().replace(".", "_")


def main():
    ok = True
    for game, title, discs, cats, items in GAMES:
        ids = [game + "." + a["suffix"] for a in items]
        if len(set(ids)) != len(ids):
            sys.exit(f"{game}: an id is there twice")
        used = {a["category"] for a in items}
        cats = [c for c in cats if c["id"] in used]
        missing = used - {c["id"] for c in cats}
        if missing:
            sys.exit(f"{game}: categories without a definition: {missing}")
        order = {c["id"]: i for i, c in enumerate(cats)}
        items = sorted(items, key=lambda a: order[a["category"]])  # stable: keeps the order within a category
        out = {"format": "achievement-definitions", "schema": 1, "game": game, "title": title, "games": discs,
               "categories": cats, "achievements": []}
        for a in items:
            e = {"id": game + "." + a["suffix"], "category": a["category"], "target": a["target"]}
            if a.get("unit"):
                e["unit"] = a["unit"]
            e["secret"] = a["secret"]
            e["title"] = a["title"]
            e["description"] = a["description"]
            out["achievements"].append(e)
        with open(os.path.join(ASSETS, game + ".json"), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
            f.write("\n")
        lines = [f"// {title}'s achievements, written by tools/achievements/mod_defs.py",
                 "// (with android/app/src/main/assets/achievements/" + game + ".json): do not edit by hand.",
                 "// An id is for ever (docs/ACHIEVEMENTS-FORMAT.md).",
                 "//",
                 "// ACH(enum name, id, progress target: 0 = no progress bar), ACH_SECRET for a secret one", ""]
        for a in items:
            mac = "ACH_SECRET" if a["secret"] else "ACH"
            lines.append(f'{mac}({enum_name(game, a["suffix"])}, "{game}.{a["suffix"]}", {a["target"]})')
        with open(os.path.join(NATIVE, f"achievements_list_{game}.h"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print(f"{game}: {len(items)} achievements")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
