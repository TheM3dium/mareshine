#!/usr/bin/env python3
# Writes the achievements' definitions with their texts in every language
# (docs/ACHIEVEMENTS-FORMAT.md) from the table below, after checking them
# against platform/achievements/achievements_list.h:
#   tools/achievements_defs.py platform/achievements/achievements_list.h \
#       android/app/src/main/assets/achievements/sms.json
import json, re, sys

LIST_H = sys.argv[1]
OUT = sys.argv[2]

CATS = [
    ("story",  "🌟", {"en": "Story", "de": "Story", "es": "Historia", "fr": "Histoire", "it": "Storia"}),
    ("boss",   "👑", {"en": "Bosses", "de": "Bosse", "es": "Jefes", "fr": "Boss", "it": "Boss"}),
    ("shines", "☀️", {"en": "Milestones", "de": "Meilensteine", "es": "Hitos", "fr": "Étapes", "it": "Traguardi"}),
    ("worlds", "🏝️", {"en": "Worlds", "de": "Welten", "es": "Mundos", "fr": "Mondes", "it": "Mondi"}),
    ("plaza",  "⛲", {"en": "Delfino Plaza & extras", "de": "Delfino Plaza & Extras", "es": "Plaza Delfino y extras", "fr": "Place Delfino et extras", "it": "Piazza Delfino ed extra"}),
    ("live",   "🎈", {"en": "Little moments", "de": "Kleine Momente", "es": "Pequeños momentos", "fr": "Petits moments", "it": "Piccoli momenti"}),
    ("fun",    "🤡", {"en": "Fun", "de": "Spaß", "es": "Diversión", "fr": "Pour rire", "it": "Divertimento"}),
    ("pro",    "⏱️", {"en": "For pros", "de": "Für Profis", "es": "Para expertos", "fr": "Pour les pros", "it": "Per esperti"}),
    ("glitch", "👾", {"en": "Glitches", "de": "Glitches", "es": "Glitches", "fr": "Glitchs", "it": "Glitch"}),
]

def T(en, de, es, fr, it):
    return {"en": en, "de": de, "es": es, "fr": fr, "it": it}

A = []
def a(id, cat, title, desc, secret=False):
    A.append({"id": id, "category": cat, "secret": secret, "title": title, "description": desc})

# --- story
a("sms.story.first_shine", "story", T("Holiday Interrupted", "Urlaub mit Hindernissen", "Vacaciones interrumpidas", "Vacances contrariées", "Vacanza interrotta"),
  T("Get the first Shine Sprite at the Delfino Airstrip.", "Hol den ersten Shine am Flughafen von Delfino.", "Consigue el primer Sol en el aeródromo de Delfino.", "Obtiens le premier Soleil sur l'aéroport de Delfino.", "Ottieni il primo Sole all'aeroporto di Delfino."))
a("sms.story.gatekeeper", "story", T("Guardian of the Statue", "Wächter der Statue", "Guardián de la estatua", "Gardien de la statue", "Guardiano della statua"),
  T("Defeat the goop monster blocking the Pianta statue in Delfino Plaza.", "Besiege das Schleimmonster vor der Pianta-Statue in Delfino Plaza.", "Derrota al monstruo de pringue que bloquea la estatua Pianta en la Plaza Delfino.", "Bats le monstre de boue qui bloque la statue Pianta sur la place Delfino.", "Sconfiggi il mostro di melma che blocca la statua Pianta in Piazza Delfino."))
a("sms.story.yoshi", "story", T("Old Friend", "Alter Freund", "Viejo amigo", "Vieil ami", "Vecchio amico"),
  T("Free Yoshi in Delfino Plaza.", "Befreie Yoshi in Delfino Plaza.", "Libera a Yoshi en la Plaza Delfino.", "Libère Yoshi sur la place Delfino.", "Libera Yoshi in Piazza Delfino."))
a("sms.story.rocket_nozzle", "story", T("Lift-Off", "Abheben", "Despegue", "Décollage", "Decollo"),
  T("Unlock the Rocket Nozzle in Delfino Plaza.", "Schalte die Raketendüse in Delfino Plaza frei.", "Desbloquea la boquilla cohete en la Plaza Delfino.", "Débloque la buse fusée sur la place Delfino.", "Sblocca l'ugello razzo in Piazza Delfino."))
a("sms.story.turbo_nozzle", "story", T("Full Throttle", "Vollgas", "A toda máquina", "Plein gaz", "A tutto gas"),
  T("Unlock the Turbo Nozzle in Delfino Plaza.", "Schalte die Turbodüse in Delfino Plaza frei.", "Desbloquea la boquilla turbo en la Plaza Delfino.", "Débloque la buse turbo sur la place Delfino.", "Sblocca l'ugello turbo in Piazza Delfino."))
a("sms.story.shadow_mario_all", "story", T("Shadows Chased Away", "Schatten vertrieben", "Sombras ahuyentadas", "Ombres chassées", "Ombre scacciate"),
  T("Catch Shadow Mario in episode 7 of all seven worlds.", "Fang Shadow Mario in Episode 7 aller sieben Welten.", "Atrapa a Mario Oscuro en el episodio 7 de los siete mundos.", "Attrape Mario Ombre dans l'épisode 7 des sept mondes.", "Cattura Mario Ombra nell'episodio 7 di tutti e sette i mondi."))
a("sms.story.true_ending", "story", T("A Real Holiday at Last", "Endlich Urlaub", "Por fin vacaciones", "Enfin des vacances", "Finalmente in vacanza"),
  T("Collect all 120 Shine Sprites and see the other ending.", "Sammle alle 120 Shines und sieh dir das andere Ende an.", "Reúne los 120 Soles y mira el otro final.", "Récupère les 120 Soleils et regarde l'autre fin.", "Raccogli tutti i 120 Soli e guarda l'altro finale."))

# --- bosses
def boss(key, en, de, es, fr, it, t1, t2):
    a("sms.boss.%s.defeat" % key, "boss", t1,
      T("Defeat %s." % en, "Besiege %s." % de, "Derrota a %s." % es, "Bats %s." % fr, "Sconfiggi %s." % it))
    a("sms.boss.%s.nohit" % key, "boss", t2,
      T("Defeat %s without taking a hit in the fight." % en, "Besiege %s, ohne im Kampf getroffen zu werden." % de,
        "Derrota a %s sin recibir daño en el combate." % es, "Bats %s sans être touché pendant le combat." % fr,
        "Sconfiggi %s senza subire colpi nello scontro." % it))
boss("petey", "Petey Piranha", "Petey Piranha", "Peta Piraña", "Flora Piranha", "Pipino Piranha",
     T("Weeded Out", "Unkraut gejätet", "Malas hierbas fuera", "Mauvaise herbe arrachée", "Erbaccia estirpata"),
     T("Master Gardener", "Gärtnermeister", "Maestro jardinero", "Maître jardinier", "Maestro giardiniere"))
boss("gooper", "Gooper Blooper", "Gooper Blooper", "Gooper Blooper", "Gooper Blooper", "Gooper Blooper",
     T("Calamari Night", "Tintenfisch-Sushi", "Noche de calamares", "Soirée calamars", "Serata di calamari"),
     T("Not a Drop of Ink", "Kein Kratzer, keine Tinte", "Ni una gota de tinta", "Pas une goutte d'encre", "Neanche una goccia d'inchiostro"))
boss("wiggler", "Wiggler", "Wiggler", "Floruga", "Wiggler", "Torcibruco",
     T("Bug Tamer", "Raupe gezähmt", "Domador de orugas", "Dresseur de chenille", "Domatore di bruchi"),
     T("Barefoot on the Sand", "Barfuß über den Sand", "Descalzo en la arena", "Pieds nus sur le sable", "A piedi nudi sulla sabbia"))
boss("mecha_bowser", "Mecha-Bowser", "Mecha-Bowser", "Mecha-Bowser", "Mécha-Bowser", "Mecha-Bowser",
     T("Roller Coaster Duel", "Achterbahn-Duell", "Duelo en la montaña rusa", "Duel sur les montagnes russes", "Duello sulle montagne russe"),
     T("Free Ride", "Freifahrt", "Viaje gratis", "Tour gratuit", "Giro gratis"))
boss("phantamanta", "Phantamanta", "Phantamanta", "Phantamanta", "Phantamanta", "Phantamanta",
     T("Divide and Conquer", "Mantas geteilt", "Divide y vencerás", "Diviser pour régner", "Dividi e conquista"),
     T("Dry Feet", "Trockener Fuß", "Pies secos", "Pieds au sec", "Piedi asciutti"))
boss("king_boo", "King Boo", "König Buu Huu", "el Rey Boo", "le Roi Boo", "Re Boo",
     T("Debts Settled", "Spielschulden beglichen", "Deudas saldadas", "Dettes réglées", "Debiti saldati"),
     T("Broke the Bank", "Bank gesprengt", "Banca rota", "La banque a sauté", "Banco sbancato"))
boss("eely_mouth", "Eely-Mouth", "Eely-Mouth", "Eely-Mouth", "Eely-Mouth", "Eely-Mouth",
     T("Dentist Appointment", "Zahnarzt-Termin", "Cita con el dentista", "Rendez-vous chez le dentiste", "Appuntamento dal dentista"),
     T("No Anaesthetic Needed", "Ohne Betäubung", "Sin anestesia", "Sans anesthésie", "Senza anestesia"))
boss("bowser", "Bowser and Bowser Jr. on Corona Mountain", "Bowser und Bowser Jr. auf dem Corona-Berg",
     "Bowser y Bowser Jr. en el Monte Corona", "Bowser et Bowser Jr. sur le mont Corona", "Bowser e Bowser Junior sul Monte Corona",
     T("Family Reunion", "Familientreffen", "Reunión familiar", "Réunion de famille", "Riunione di famiglia"),
     T("Bathtub Champion", "Badewannen-Meister", "Campeón de la bañera", "Champion de la baignoire", "Campione della vasca"))

# --- milestones
def shines(n, t):
    a("sms.shines.%d" % n, "shines", t,
      T("Collect %d Shine Sprites." % n, "Sammle %d Shines." % n, "Reúne %d Soles." % n, "Récupère %d Soleils." % n, "Raccogli %d Soli." % n))
shines(3, T("First Sparks", "Erste Funken", "Primeras chispas", "Premières étincelles", "Prime scintille"))
shines(10, T("Getting Brighter", "Es wird hell", "Cada vez más luz", "Ça s'éclaircit", "Si fa luce"))
shines(30, T("Beaming", "Strahlemann", "Radiante", "Rayonnant", "Raggiante"))
shines(50, T("Halfway Sun", "Halbzeit-Sonne", "Sol a medias", "Soleil à mi-chemin", "Sole a metà"))
shines(70, T("Sun Worshipper", "Sonnenanbeter", "Adorador del sol", "Adorateur du soleil", "Adoratore del sole"))
shines(100, T("Almost There", "Fast geschafft", "Casi lo tienes", "Presque fini", "Quasi fatto"))
shines(120, T("Full Sun", "Volle Sonne", "Sol pleno", "Plein soleil", "Pieno sole"))
def blue(n, t):
    a("sms.blue_coins.%d" % n, "shines", t,
      T("Collect %d Blue Coins." % n, "Sammle %d blaue Münzen." % n, "Reúne %d monedas azules." % n, "Récupère %d pièces bleues." % n, "Raccogli %d monete blu." % n))
blue(50, T("Feeling Blue", "Blaues Wunder", "Todo azul", "Bleu de bleu", "Tutto blu"))
blue(100, T("Coin Collector", "Münzsammler", "Coleccionista", "Collectionneur", "Collezionista"))
blue(240, T("Every Last Blue", "Alle Blauen", "Hasta la última azul", "Jusqu'à la dernière bleue", "Fino all'ultima blu"))
a("sms.blue_coins.trade_all", "shines", T("The Big Trade", "Großer Tausch", "El gran trueque", "Le grand échange", "Il grande scambio"),
  T("Trade Blue Coins for all 24 Shine Sprites in the boathouse.", "Tausche im Bootshaus blaue Münzen gegen alle 24 Shines.", "Cambia monedas azules por los 24 Soles del cobertizo.", "Échange des pièces bleues contre les 24 Soleils du hangar à bateaux.", "Scambia monete blu con tutti i 24 Soli nella rimessa delle barche."))

# --- worlds
WORLDS = [
    ("bianco", "Bianco Hills", "Bianco Hills", "Bianco Hills", "Bianco Hills", "Bianco Hills",
     ["Windmill Saviour", "Windmühlen-Retter", "Salvador del molino", "Sauveur du moulin", "Salvatore del mulino"],
     ["Cave Explorer", "Höhlenforscher", "Espeleólogo", "Spéléologue", "Speleologo"],
     ["Bianco Treasure", "Bianco-Schatz", "Tesoro de Bianco", "Trésor de Bianco", "Tesoro di Bianco"],
     ["Hills Combed Through", "Hügel abgegrast", "Colinas peinadas", "Collines passées au peigne fin", "Colli setacciati"]),
    ("ricco", "Ricco Harbor", "Ricco Harbor", "Ricco Harbor", "Ricco Harbor", "Ricco Harbor",
     ["Harbour Master", "Hafenmeister", "Capitán del puerto", "Capitaine du port", "Capitano di porto"],
     ["High Diver", "Turmspringer", "Saltador de altura", "Plongeur de haut vol", "Tuffatore"],
     ["Harbour Till", "Hafenkasse", "Caja del puerto", "Caisse du port", "Cassa del porto"],
     ["Every Container", "Jeder Container", "Cada contenedor", "Chaque conteneur", "Ogni container"]),
    ("gelato", "Gelato Beach", "Gelato Beach", "Gelato Beach", "Gelato Beach", "Gelato Beach",
     ["Beach Warden", "Strandwärter", "Vigilante de la playa", "Gardien de la plage", "Guardiano della spiaggia"],
     ["Sand Artist", "Sandkünstler", "Artista de arena", "Artiste du sable", "Artista della sabbia"],
     ["Shell Money", "Muschelgeld", "Dinero de conchas", "Monnaie de coquillages", "Soldi di conchiglie"],
     ["Every Grain of Sand", "Jedes Sandkorn", "Cada grano de arena", "Chaque grain de sable", "Ogni granello di sabbia"]),
    ("pinna", "Pinna Park", "Pinna Park", "Pinna Park", "Pinna Park", "Pinna Park",
     ["Park Director", "Parkdirektor", "Director del parque", "Directeur du parc", "Direttore del parco"],
     ["Behind the Scenes", "Hinter den Kulissen", "Entre bastidores", "Dans les coulisses", "Dietro le quinte"],
     ["Funfair Takings", "Kirmes-Kasse", "Caja de la feria", "Recette de la fête foraine", "Incasso del luna park"],
     ["Every Ride", "Jede Attraktion", "Cada atracción", "Chaque attraction", "Ogni attrazione"]),
    ("sirena", "Sirena Beach", "Sirena Beach", "Sirena Beach", "Sirena Beach", "Sirena Beach",
     ["Guest of the Year", "Hotelgast des Jahres", "Huésped del año", "Client de l'année", "Ospite dell'anno"],
     ["Room Service", "Zimmerservice", "Servicio de habitaciones", "Service d'étage", "Servizio in camera"],
     ["Tip for the Staff", "Trinkgeld", "Propina", "Pourboire", "Mancia"],
     ["Under Every Bed", "Unter jedem Bett", "Bajo cada cama", "Sous chaque lit", "Sotto ogni letto"]),
    ("noki", "Noki Bay", "Noki Bay", "Noki Bay", "Noki Bay", "Noki Bay",
     ["Bay Diver", "Taucher der Bucht", "Buzo de la bahía", "Plongeur de la baie", "Sub della baia"],
     ["Shell Whisperer", "Muschelflüsterer", "Susurrador de conchas", "Murmureur de coquillages", "Sussurratore di conchiglie"],
     ["Deep-Sea Treasure", "Tiefsee-Schatz", "Tesoro de las profundidades", "Trésor des abysses", "Tesoro degli abissi"],
     ["Every Waterfall", "Jeder Wasserfall", "Cada cascada", "Chaque cascade", "Ogni cascata"]),
    ("pianta", "Pianta Village", "Pianta Village", "Pianta Village", "Pianta Village", "Pianta Village",
     ["Honorary Citizen", "Ehrenbürger", "Ciudadano de honor", "Citoyen d'honneur", "Cittadino onorario"],
     ["Under the Village", "Unter dem Dorf", "Bajo el pueblo", "Sous le village", "Sotto il villaggio"],
     ["Mushroom Money", "Pilzgeld", "Dinero de setas", "Argent des champignons", "Soldi dei funghi"],
     ["Every Tree Trunk", "Jeder Baumstamm", "Cada tronco", "Chaque tronc", "Ogni tronco"]),
]
for key, en, de, es, fr, it, t_ep, t_sec, t_100, t_blue in WORLDS:
    a("sms.world.%s.episodes" % key, "worlds", T(*t_ep),
      T("Complete all 8 episodes in %s." % en, "Schließ alle 8 Episoden in %s ab." % de, "Completa los 8 episodios de %s." % es,
        "Termine les 8 épisodes de %s." % fr, "Completa gli 8 episodi di %s." % it))
    a("sms.world.%s.secrets" % key, "worlds", T(*t_sec),
      T("Collect both secret Shine Sprites in %s." % en, "Sammle beide Geheim-Shines in %s." % de, "Reúne los dos Soles secretos de %s." % es,
        "Récupère les deux Soleils secrets de %s." % fr, "Raccogli entrambi i Soli segreti di %s." % it))
    a("sms.world.%s.coins100" % key, "worlds", T(*t_100),
      T("Collect 100 coins in %s and get the Shine Sprite." % en, "Sammle 100 Münzen in %s und hol dir den Shine." % de,
        "Reúne 100 monedas en %s y consigue el Sol." % es, "Récupère 100 pièces à %s et obtiens le Soleil." % fr,
        "Raccogli 100 monete a %s e ottieni il Sole." % it))
    a("sms.world.%s.blue_coins" % key, "worlds", T(*t_blue),
      T("Collect all 30 Blue Coins in %s." % en, "Sammle alle 30 blauen Münzen in %s." % de, "Reúne las 30 monedas azules de %s." % es,
        "Récupère les 30 pièces bleues de %s." % fr, "Raccogli tutte le 30 monete blu di %s." % it))

# --- plaza & extras
a("sms.plaza.shines", "plaza", T("Plaza Cleaner", "Plaza-Putzkraft", "Limpiador de la plaza", "Agent d'entretien de la place", "Addetto alle pulizie della piazza"),
  T("Collect all 10 Shine Sprites in Delfino Plaza.", "Sammle alle 10 Shines in Delfino Plaza.", "Reúne los 10 Soles de la Plaza Delfino.", "Récupère les 10 Soleils de la place Delfino.", "Raccogli tutti i 10 Soli di Piazza Delfino."))
a("sms.plaza.blue_coins", "plaza", T("Blue Cobblestones", "Pflasterblau", "Adoquines azules", "Pavés bleus", "Ciottoli blu"),
  T("Collect all 19 Blue Coins in Delfino Plaza.", "Sammle alle 19 blauen Münzen in Delfino Plaza.", "Reúne las 19 monedas azules de la Plaza Delfino.", "Récupère les 19 pièces bleues de la place Delfino.", "Raccogli tutte le 19 monete blu di Piazza Delfino."))
a("sms.corona.blue_coins", "plaza", T("Summit Savings", "Gipfelgeld", "Ahorros de la cumbre", "Économies du sommet", "Risparmi della vetta"),
  T("Collect all 10 Blue Coins on Corona Mountain.", "Sammle alle 10 blauen Münzen auf dem Corona-Berg.", "Reúne las 10 monedas azules del Monte Corona.", "Récupère les 10 pièces bleues du mont Corona.", "Raccogli tutte le 10 monete blu del Monte Corona."))
def bonus(id, t, en, de, es, fr, it):
    a(id, "plaza", t, T("Get the Shine Sprite in %s." % en, "Hol den Shine in %s." % de, "Consigue el Sol en %s." % es,
                        "Obtiens le Soleil dans %s." % fr, "Ottieni il Sole in %s." % it))
bonus("sms.bonus.super_slide", T("Slip 'n' Slide", "Rutschpartie", "Tobogán", "Glissade", "Scivolata"),
      "the Super Slide", "der Super-Rutsche", "el Supertobogán", "le Super toboggan", "Super Scivolo")
bonus("sms.bonus.pachinko", T("Pinball Wizard", "Flipperkugel", "Bola de pinball", "Bille de flipper", "Pallina da flipper"),
      "the Pachinko game", "Pachinko", "el Pachinko", "le Pachinko", "Pachinko")
bonus("sms.bonus.lily_pad", T("Lily Pad Captain", "Seerosen-Kapitän", "Capitán del nenúfar", "Capitaine du nénuphar", "Capitano della ninfea"),
      "the Lily Pad Ride", "der Seerosen-Fahrt", "el Paseo en nenúfar", "la Balade en nénuphar", "Giro sulla ninfea")
bonus("sms.bonus.turbo_track", T("Turbo Track", "Turbo-Strecke", "Pista turbo", "Piste turbo", "Pista turbo"),
      "the Turbo Track", "der Turbo-Strecke", "la Pista turbo", "la Piste turbo", "Pista turbo")
bonus("sms.bonus.red_coin_field", T("Seeing Red", "Rot gesehen", "Todo rojo", "Voir rouge", "Tutto rosso"),
      "the Red Coin Field", "dem Rote-Münzen-Feld", "el Campo de monedas rojas", "le Champ des pièces rouges", "Campo delle monete rosse")
a("sms.airstrip.after_corona", "plaza", T("Overtime", "Nachspielzeit", "Prórroga", "Prolongations", "Tempi supplementari"),
  T("After Corona Mountain, get the Shine Sprite and the Blue Coin at the Delfino Airstrip.", "Hol dir nach dem Corona-Berg den Shine und die blaue Münze am Flughafen.",
    "Tras el Monte Corona, consigue el Sol y la moneda azul del aeródromo.", "Après le mont Corona, obtiens le Soleil et la pièce bleue de l'aéroport.",
    "Dopo il Monte Corona, ottieni il Sole e la moneta blu all'aeroporto."))
a("sms.nozzle_boxes", "plaza", T("Nozzle Box Hunter", "Düsenkisten-Jäger", "Cazador de cajas", "Chasseur de caisses", "Cacciatore di casse"),
  T("Open every nozzle box in the seven worlds.", "Öffne alle Düsenkisten in den sieben Welten.", "Abre todas las cajas de boquilla de los siete mundos.",
    "Ouvre toutes les caisses de buse des sept mondes.", "Apri tutte le casse degli ugelli nei sette mondi."))

# --- little moments
a("sms.live.sunglasses", "live", T("Cool Shades", "Coole Sonnenbrille", "Gafas guays", "Lunettes stylées", "Occhiali fichi"),
  T("Borrow a pair of sunglasses from a Pianta.", "Leih dir von einem Pianta eine Sonnenbrille.", "Pide prestadas unas gafas de sol a un Pianta.", "Emprunte des lunettes de soleil à un Pianta.", "Fatti prestare gli occhiali da sole da un Pianta."))
a("sms.live.shirt", "live", T("Hawaiian Look", "Hawaii-Look", "Look hawaiano", "Look hawaïen", "Look hawaiano"),
  T("Borrow a tropical shirt from a Pianta.", "Leih dir von einem Pianta ein Hawaiihemd.", "Pide prestada una camisa hawaiana a un Pianta.", "Emprunte une chemise hawaïenne à un Pianta.", "Fatti prestare una camicia hawaiana da un Pianta."))
a("sms.live.flagpole", "live", T("Just Like Old Times", "Wie in alten Zeiten", "Como en los viejos tiempos", "Comme au bon vieux temps", "Come ai vecchi tempi"),
  T("Climb to the very top of the flagpole at the Delfino Airstrip.", "Kletter ganz nach oben auf den Fahnenmast am Flughafen.", "Sube hasta lo alto del mástil del aeródromo.", "Grimpe tout en haut du mât de l'aéroport.", "Arrampicati in cima all'asta della bandiera all'aeroporto."))

# --- fun
a("sms.fun.stuck_in_goop", "fun", T("Sticky Situation", "Klebefalle", "Situación pegajosa", "Situation collante", "Situazione appiccicosa"),
  T("Get stuck in the brown goop for the first time.", "Bleib das erste Mal im braunen Schleim kleben.", "Quédate pegado en el pringue marrón por primera vez.", "Reste collé dans la boue marron pour la première fois.", "Resta incollato nella melma marrone per la prima volta."))
a("sms.fun.on_fire", "fun", T("Hot Pants", "Feuer unterm Hintern", "Culo en llamas", "Le feu aux fesses", "Fuoco sotto il sedere"),
  T("Get set on fire by fire goop.", "Lass dich vom Feuerschleim anzünden.", "Prende fuego con el pringue de fuego.", "Prends feu à cause de la boue enflammée.", "Prendi fuoco per la melma infuocata."))
a("sms.fun.electrocuted", "fun", T("Live Wire", "Unter Strom", "Bajo tensión", "Sous tension", "Sotto tensione"),
  T("Get an electric shock.", "Kassier einen Stromschlag.", "Recibe una descarga eléctrica.", "Prends une décharge électrique.", "Prendi una scossa elettrica."))
a("sms.fun.thrown", "fun", T("Pianta Projectile", "Pianta-Wurfgeschoss", "Proyectil Pianta", "Projectile Pianta", "Proiettile Pianta"),
  T("Get thrown by a Chuckster.", "Lass dich von einem Chuckster werfen.", "Haz que un Pianta lanzador te arroje.", "Fais-toi lancer par un Pianta lanceur.", "Fatti lanciare da un Pianta lanciatore."))
a("sms.fun.lives_99", "fun", T("Nine Lives Times Eleven", "Katzenleben", "Vidas de gato", "Neuf vies fois onze", "Vite da gatto"),
  T("Have 99 lives.", "Hab 99 Leben.", "Ten 99 vidas.", "Aie 99 vies.", "Abbi 99 vite."))
a("sms.fun.coins_300", "fun", T("Scrooge", "Dagobert", "Tío Gilito", "Picsou", "Paperone"),
  T("Collect 300 coins in one visit to a world.", "Sammle 300 Münzen in einem Besuch einer Welt.", "Reúne 300 monedas en una sola visita a un mundo.", "Récupère 300 pièces en une seule visite d'un monde.", "Raccogli 300 monete in una sola visita a un mondo."))
a("sms.fun.yoshi_colors", "fun", T("Rainbow Yoshi", "Regenbogen-Yoshi", "Yoshi arcoíris", "Yoshi arc-en-ciel", "Yoshi arcobaleno"),
  T("Turn Yoshi every colour by feeding him each kind of fruit.", "Lass Yoshi jede Farbe annehmen, indem du ihn mit jeder Fruchtsorte fütterst.", "Haz que Yoshi tenga todos los colores dándole cada fruta.", "Fais prendre à Yoshi toutes les couleurs en lui donnant chaque fruit.", "Fai cambiare a Yoshi tutti i colori dandogli ogni tipo di frutto."))
a("sms.fun.all_movies", "fun", T("Movie Night", "Kinoabend", "Noche de cine", "Soirée cinéma", "Serata al cinema"),
  T("Watch every movie in the game.", "Sieh dir alle Videos des Spiels an.", "Mira todos los vídeos del juego.", "Regarde toutes les vidéos du jeu.", "Guarda tutti i filmati del gioco."))
a("sms.fun.time_travel", "fun", T("Time Traveller", "Zeitreisender", "Viajero del tiempo", "Voyageur temporel", "Viaggiatore del tempo"),
  T("Load a save state.", "Lade einen Save State.", "Carga un estado guardado.", "Charge une sauvegarde instantanée.", "Carica uno stato salvato."))
a("sms.fun.playtime_10h", "fun", T("In Need of a Holiday", "Urlaubsreif", "Necesito vacaciones", "Besoin de vacances", "Serve una vacanza"),
  T("Play for 10 hours in total.", "Spiel insgesamt 10 Stunden.", "Juega 10 horas en total.", "Joue 10 heures au total.", "Gioca 10 ore in totale."))
a("sms.fun.game_over", "fun", T("Game Over", "Game Over", "Game Over", "Game Over", "Game Over"),
  T("Lose all your lives.", "Verlier alle Leben.", "Pierde todas tus vidas.", "Perds toutes tes vies.", "Perdi tutte le vite."), secret=True)
a("sms.fun.impatient", "fun", T("Impatient", "Ungeduldig", "Impaciente", "Impatient", "Impaziente"),
  T("Skip a movie you had not seen yet by pressing START twice.", "Überspring ein noch nicht gesehenes Video mit zweimal Start.", "Sáltate un vídeo que no habías visto pulsando START dos veces.", "Passe une vidéo jamais vue en appuyant deux fois sur START.", "Salta un filmato mai visto premendo START due volte."), secret=True)
a("sms.fun.yoshi_juice", "fun", T("Juicer", "Saftpresse", "Exprimidor", "Presse-agrumes", "Spremiagrumi"),
  T("Let Yoshi turn into juice in the water.", "Lass Yoshi im Wasser zu Saft werden.", "Deja que Yoshi se convierta en zumo en el agua.", "Laisse Yoshi se transformer en jus dans l'eau.", "Lascia che Yoshi diventi succo nell'acqua."), secret=True)

a("sms.fun.fine_tuner", "fun", T("Fine-Tuner", "Feintuner", "Afinador", "Peaufineur", "Perfezionista"),
  T("Changed 120 settings in the menu, as many as there are Shine Sprites.",
    "120 Einstellungen im Menü geändert, so viele, wie es Shines gibt.",
    "Has cambiado 120 ajustes en el menú, tantos como Soles hay.",
    "120 paramètres modifiés dans le menu, autant qu'il y a de Soleils.",
    "Hai cambiato 120 impostazioni nel menu, tante quanti sono i Soli."), secret=True)

# --- for pros
a("sms.pro.veteran", "pro", T("The Veteran Is Back", "Veteran ist zurück", "El veterano ha vuelto", "Le vétéran est de retour", "Il veterano è tornato"),
  T("Defeat the first Piranha Plant at the airstrip within 90 seconds of starting a new game.", "Besiege die erste Piranha-Pflanze am Flughafen innerhalb von 90 Sekunden nach Spielbeginn.",
    "Derrota a la primera Planta Piraña del aeródromo en 90 segundos desde el inicio de la partida.", "Bats la première Plante Piranha de l'aéroport en moins de 90 secondes après le début de la partie.",
    "Sconfiggi la prima Pianta Piranha all'aeroporto entro 90 secondi dall'inizio della partita."), secret=True)
a("sms.pro.slip_25", "pro", T("Slipped Through", "Durchgerutscht", "Colado", "Passé en douce", "Sgusciato via"),
  T("Have 25 Shine Sprites within 1:30 hours of play on a save file.", "Hab 25 Shines innerhalb von 1:30 Stunden Spielzeit in einem Spielstand.", "Consigue 25 Soles en menos de 1:30 horas de juego en una partida.",
    "Obtiens 25 Soleils en moins de 1 h 30 de jeu sur une sauvegarde.", "Ottieni 25 Soli entro 1:30 ore di gioco in un salvataggio."), secret=True)
a("sms.pro.speedrun", "pro", T("Speedrunner", "Speedrunner", "Speedrunner", "Speedrunner", "Speedrunner"),
  T("Defeat Bowser within 2:30 hours of play on a save file.", "Besiege Bowser innerhalb von 2:30 Stunden Spielzeit in einem Spielstand.", "Derrota a Bowser en menos de 2:30 horas de juego en una partida.",
    "Bats Bowser en moins de 2 h 30 de jeu sur une sauvegarde.", "Sconfiggi Bowser entro 2:30 ore di gioco in un salvataggio."), secret=True)
a("sms.pro.wr_attempt", "pro", T("WR Attempt?", "WR-Versuch?", "¿Intento de récord?", "Tentative de record ?", "Tentativo di record?"),
  T("Defeat Bowser within 1:25 hours of play on a save file.", "Besiege Bowser innerhalb von 1:25 Stunden Spielzeit in einem Spielstand.", "Derrota a Bowser en menos de 1:25 horas de juego en una partida.",
    "Bats Bowser en moins de 1 h 25 de jeu sur une sauvegarde.", "Sconfiggi Bowser entro 1:25 ore di gioco in un salvataggio."), secret=True)
a("sms.pro.speedrun_120", "pro", T("Speedrunner 120", "Speedrunner 120", "Speedrunner 120", "Speedrunner 120", "Speedrunner 120"),
  T("Have all 120 Shine Sprites within 6 hours of play on a save file.", "Hab alle 120 Shines innerhalb von 6 Stunden Spielzeit in einem Spielstand.", "Consigue los 120 Soles en menos de 6 horas de juego en una partida.",
    "Obtiens les 120 Soleils en moins de 6 heures de jeu sur une sauvegarde.", "Ottieni tutti i 120 Soli entro 6 ore di gioco in un salvataggio."), secret=True)
a("sms.pro.wr_attempt_120", "pro", T("WR Attempt 120?", "WR-Versuch 120?", "¿Intento de récord 120?", "Tentative de record 120 ?", "Tentativo di record 120?"),
  T("Have all 120 Shine Sprites within 3:30 hours of play on a save file.", "Hab alle 120 Shines innerhalb von 3:30 Stunden Spielzeit in einem Spielstand.", "Consigue los 120 Soles en menos de 3:30 horas de juego en una partida.",
    "Obtiens les 120 Soleils en moins de 3 h 30 de jeu sur une sauvegarde.", "Ottieni tutti i 120 Soli entro 3:30 ore di gioco in un salvataggio."), secret=True)
a("sms.pro.hacker", "pro", T("Hacker", "Hacker", "Hacker", "Hacker", "Hacker"),
  T("Play with a cheat turned on.", "Spiel mit einem eingeschalteten Cheat.", "Juega con un truco activado.", "Joue avec un code de triche activé.", "Gioca con un trucco attivo."), secret=True)

# --- glitches
a("sms.glitch.out_of_bounds", "glitch", T("Border Crosser", "Grenzgänger", "Cruzafronteras", "Passe-muraille", "Oltreconfine"),
  T("Be alive outside the level's bounds.", "Sei lebendig außerhalb der Levelgrenzen.", "Sigue vivo fuera de los límites del nivel.", "Sois en vie hors des limites du niveau.", "Resta vivo fuori dai confini del livello."), secret=True)
a("sms.glitch.under_floor", "glitch", T("Through the Floor", "Durch den Boden", "A través del suelo", "À travers le sol", "Attraverso il pavimento"),
  T("Get under the floor without dying straight away.", "Komm unter den Boden, ohne sofort zu sterben.", "Métete bajo el suelo sin morir al instante.", "Passe sous le sol sans mourir aussitôt.", "Finisci sotto il pavimento senza morire subito."), secret=True)
a("sms.glitch.too_fast", "glitch", T("Sound Barrier", "Schallmauer", "Barrera del sonido", "Mur du son", "Muro del suono"),
  T("Move faster than the game's physics normally allow.", "Sei schneller, als die Physik des Spiels es normalerweise erlaubt.", "Muévete más rápido de lo que permite la física del juego.", "Va plus vite que la physique du jeu ne le permet normalement.", "Muoviti più veloce di quanto la fisica del gioco consenta."), secret=True)
a("sms.glitch.too_high", "glitch", T("Sky Climber", "Himmelsstürmer", "Escalacielos", "Grimpeur céleste", "Scalacieli"),
  T("Get higher than any normal platform in the level.", "Komm höher als jede normale Plattform im Level.", "Llega más alto que cualquier plataforma normal del nivel.", "Monte plus haut que toute plateforme normale du niveau.", "Arriva più in alto di qualsiasi piattaforma normale del livello."), secret=True)
a("sms.glitch.hunter", "glitch", T("Glitchhunter", "Glitchhunter", "Cazaglitches", "Chasseur de glitchs", "Cacciatore di glitch"),
  T("Get all four glitch achievements.", "Hol dir alle vier Glitch-Achievements.", "Consigue los cuatro logros de glitches.", "Obtiens les quatre succès de glitchs.", "Ottieni tutti e quattro gli obiettivi dei glitch."), secret=True)

# --- checks against achievements_list.h and output
ids = re.findall(r'^ACH\(\s*\w+,\s*"([^"]+)",\s*(\d+)\)', open(LIST_H).read(), re.M)
assert [i for i, _ in ids] == [x["id"] for x in A], "order or ids differ from achievements_list.h"
for (i, t), x in zip(ids, A):
    x["target"] = int(t)
out = {
    "format": "achievement-definitions", "schema": 1, "game": "sms",
    "games": ["GMSE01", "GMSP01", "GMSJ01"],
    "categories": [{"id": k, "icon": ic, "title": t} for k, ic, t in CATS],
    "achievements": [{k: x[k] for k in ("id", "category", "target", "secret", "title", "description")} for x in A],
}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(A), "achievements written")
