# AI-player development operator guide
[Contract](../minecraft_player_spec.md) · [Tracker](feature-spec.md) · [Manifest](../release-manifest.json)

Use a disposable world and back up native saves together with Kindred SQLite metadata. This build is not qualified for unattended production survival.

Use the pinned Java 21/Fabric stack and server JAR. Ordinary clients remain the target; live compatibility requires evidence. No model key or external bot account is needed for current controls.

Use /aiplayer for command help; /companion redirects to the same tree. Creation, status, follow/stay/stop/resume, setup, movement, gather/deposit fixtures, personality and journal retain their existing restrictions. Commands do not imply planned skills are enabled.

## Quick start: the K panel and the Nether-portal goal
Press **K** in game (rebindable under Controls → Key Binds → Kindred AI Players) to open the AI player panel. It lists the canonical roster (Kindred, Sol, Echo) with their own skins, spawns a roster player at your feet, and for a selected player shows health, food, position, goal and progress, with Start Nether run, Stop, Resume, mode, team, activity cap, Connect/Disconnect, Skin... and the server's world edits switch (Edits). Operator-only buttons need permission level 2. Commands behind the buttons:
```text
/aiplayer roster spawn Kindred       spawn a roster player beside you
/aiplayer edits on|off               server-wide switch for AI block breaking and placing (default off)
/aiplayer goal nether [bot]          earn a lit Nether portal from what the player owns
/aiplayer goal end [bot]             on from a lit portal to the End portal (earns the portal first)
/aiplayer goal dragon [bot]          on through the End portal to beat the Ender Dragon (needs dragonfight on)
/aiplayer dragonfight on|off         server-wide leave for AI players to go into the End and fight the dragon (default off)
/aiplayer goal none [bot]            stop pursuing the goal
/aiplayer build hut <x y z> [turns] [bot]  build a generated hut there from its own blocks (hut-7x5x3: width, depth, wall height)
/aiplayer build cabin|house|manor <x y z> [turns] [bot]  build a stage of a base there, of oak and cobblestone
/aiplayer build keep <x y z> [turns] [bot]  build a walled keep there, the first stage of a Factions base (M-06a)
/aiplayer build farmward <x y z> [turns] [bot]  build the keep's farm ward east of it, at the keep's own spot
/aiplayer field <x y z> [bot]  water, till and sow the farm ward's field, at the keep's own spot
/aiplayer field stop [bot]  stop working the field; what is tilled and sown stays
/aiplayer build pastureward <x y z> [turns] [bot]  build the keep's pasture ward south of its farm ward, at the keep's own spot
/aiplayer pasture <animal> <x y z> [bot]  bring a pair into the pasture's pen by its doorway and breed them, at the keep's own spot
/aiplayer build hall <x y z> [turns] [bot]  build the keep's hall south of it, at the keep's own spot
/aiplayer build walltower-<1..5> <x y z> [turns] [bot]  build a tower at one of the compound's outer corners, from the north-west round, at the keep's own spot
/aiplayer build tradinghall-<1..12> <x y z> [turns] [bot]  build a trading hall of that many villager cells (three if not said), of oak and cobblestone
/aiplayer bases on|off               server-wide leave for AI players to choose, site and build their own bases (default off)
/aiplayer build stop [bot]           stop building; what is built stays
/aiplayer build <schematic> <x y z> [turns] [bot]  build a schematic from config/kindred/schematics
/aiplayer build list                 the generators and schematics on offer
/aiplayer storage at <x y z> [bot]   make the container there the player's own storage (stops it until resumed)
/aiplayer ranch <cow|sheep|pig|chicken> <x y z> [bot]  build a pen there from its own wood, lead a pair in with their food and breed them
/aiplayer ranch stop [bot]           stop the ranch; what is built stays
/aiplayer match duel <first> <second>  propose a duel between two players (AI players or humans online)
/aiplayer match speedrun <runner...> propose a speedrun race to the dragon for one to eight runners
/aiplayer match manhunt <runners> vs <hunters>  propose a manhunt: runners make for the dragon, hunters hunt them
/aiplayer match build <builders> [for <seconds>] [theme <name>]  propose a build battle (operators; plots laid where you stand)
/aiplayer match vote <builder> <1-5> score another builder's build while a build battle is voted on
/aiplayer match join <match>         join a match you were invited to (a human joins only for itself)
/aiplayer match start|stop <match>   start a match everyone joined, or stop it (a player in it forfeits)
/aiplayer match list                 matches, their state and results
/aiplayer watch on|off               let the AI players learn how deep you mine, for yourself alone (off deletes it)
/aiplayer skin <name|default> [bot]  dress an AI player in a skin from config/kindred/skins (or its own again)
/aiplayer skin list                  the skins on offer
/aiplayer native goto <x y z> [bot]  walk a pathfound route (no teleport)
/aiplayer native break <x y z> [bot] one recorded survival break with the best owned tool
```
**Skins.** Put Minecraft skin PNGs in `config/kindred/skins` on the server (the folder and a read-me appear on first start; in single player it is your own game folder):
- Files are 64×64, or the old 64×32, at most 64 KiB, up to 64 of them.
- The file name is the skin's name: `Red Knight.png` is chosen as `red_knight`.
- End a name in `_slim` or `_wide` to set the arm model; otherwise it is worked out from the image.
- In game, press K, select an AI player and press Skin... to see each skin from the front, and click one to put it on. The first card brings back the player's own skin.
- Players with the Kindred client see it at once; vanilla clients keep the default skin, as they accept only Mojang-signed textures.
- Choices are kept in `config/kindred/skin-choices.properties`.
- Files that are not usable skins are named in the server log with the reason.
- After adding files, press Reload in the selector.

World edits stay off until an operator turns them on; a fresh world has no `edits.properties`, which means off (decision D4). With edits on and no assigned work area, the player follows the roaming policy: it may break and place where a survival player could, never containers or block entities other than its own empty furnace, never in spawn protection, never the block under its own feet, and never a break that would let lava out (obsidian guarded by water is the one exception).

Every walk moves as a player does (F-10d): straight across open ground to the furthest point it can reach in a line, sprinting when its food allows (7 or more) and sprint-jumping along clear straight stretches (10 or more, as a sprint-jump costs twice the hunger), turning smoothly with its head, slowing for sharp turns, steps and drops, and easing to a walk near where it is going.

Where walking will not do, the End goal makes a way as a player would (F-10e, in development): across a gap or a lava inlet by bridging with its own blocks, up a cliff by pillaring, through a wall or a hill by digging, whichever costs least against walking round. Digging is cheap in soft blocks and dearer in hard ones; a placed block costs more, and far more over lava, so it builds only where going round is much longer. Each change is one ordinary recorded break or place under the server's edit policy, and the way is planned again after each. It does so in the Nether (where a walk on fails, and to reach a fortress it has seen), and in the Overworld when every straight line toward the stronghold is refused. In the Nether it keeps above the lava sea (Y 40) while it explores.

**Stairs (F-10h, in development).** Where a way climbs ahead as well as up, it builds a staircase as it walks rather than a pillar and a bridge. It jumps on the move and sets each step's block in the air under where it lands, step after step without stopping. Each block goes against a wall, cliff or the ground beside or under it, one block a step. In the open each step goes on a filler block, two a step. A flight of up to twelve steps is one task, and every block in it is recorded and checked as any placement is. A way straight up still pillars.

Across open water the End goal goes by boat (F-10f, in development), as players do: where most of the way ahead is water and it carries a boat, it puts the boat on the water (wading out where the shore leaves no room), rows across at a boat's speed, gets out at the far shore and breaks the boat to pick it up again. A drowned does not stop it rowing, and nothing on land is done from the boat. It makes a boat from wood it carries while it prepares for the Nether. Launching from deep water does not work yet.

A walk changes its route on the move (F-10g, in development): when something appears on the way ahead (a block put down, a hole opened, lava come in) it steers round it at a run rather than stopping to plan again. The End goal's walks are also wary of mobs: each mob notices a player it can see within vanilla's own range (a wither skeleton at 16 blocks, a zombie at 35, a blaze at 48; four fifths of that for a sneaking player), and a wary walk keeps out of that range where the ground allows, planning again as the mobs move. Mobs the plan wants (blazes while it is short of rods, endermen while it is short of pearls) are not avoided. In a hallway with no way round it still goes on through.

The End goal knows from the start what the End fight will need, and gets it on the way (F-25c7, in development): a bow and 32 arrows to shoot the End crystals down, 64 blocks to pillar up to the two caged ones, a water bucket and 16 food. It works out what that takes from what it holds (string and sticks for the bow; flint, feathers and sticks for the arrows), and takes the chance as it comes: a chicken in sight while it wants feathers, a spider it can take on or a cobweb (broken with its sword) while it wants string, gravel close by while it wants flint. It sets down the crafting table it carries, or makes one, to craft. Whatever is still missing it gathers at the stronghold before digging down. It goes down anyway if half a day passes there without progress (the log says `<name>: gathering for the End fight has stalled; going down to the stronghold`), or after half a minute when none of it can be had from there (`<name>: nothing more for the End fight to be had here; going down to the stronghold`). It never drops what it is gathering to make room. Nothing is given to it.

What a player learns about a world on the way to the End (its portals, the fortress, each eye's flight and where they cross) is kept across restarts in the world's `kindred/memory` folder, one file per player. Delete a player's file to make it forget.

The Nether goal is a local plan, not a script. Like a player it pillars up out of a hole it cannot walk out of (jumping and placing one of its own blocks under its feet), and takes the wood for its trip below while it is still among the trees, before its first cobblestone takes it down: sticks for its stone tools and their reserve, a spare table's planks, a shield's and the fuel to smelt its iron beyond the coal it carries, about nine logs from an empty start, as players take a stack of logs before going down. A spare pickaxe it has no sticks or wood for is not fetched from the surface. The route: logs, crafting table and wooden pickaxe, cobblestone and stone tools, iron smelted in its own furnace, then the frame's obsidian, then a 4×5 frame lit with its own flint and steel (flint from gravel), built where it can walk to the spot it stands on to build it. For the obsidian it picks the cheaper way from what it holds and has seen, and re-plans as that changes: usually it makes a second bucket and casts the frame in place, as speedrunners do (a lava bucket filled at a pool it has seen, walked to a leg at a time when it saw it far off, or looked for a block above the deep lava level at Y=-54, in long tunnels far apart, where a cell with lava under it is opened to see the lava and fill the bucket, never stepped into, F-25c4ac; a pool it sees with no walk to it it digs its way toward, a staircase to a block above the pool's top and a tunnel along it, F-25c4ad; a cobblestone mould behind each frame block, water poured over the block from its other bucket, lava poured in, where it hardens at once, and the water taken back; a block to dig out before the next cast that it cannot reach from the lava it goes back to the frame for, rather than give the frame up, F-25c4y; with its lava in hand and no room for the frame beside lava lakes, where no frame may stand within three blocks of lava, it climbs a few blocks into the rock over them and makes room there, F-25c4z); with diamonds in hand or diamond ore in sight it makes a diamond pickaxe and mines obsidian made by pouring water over lava instead. On a sky island (nothing at the bottom of the world under the player) there is no stone to dig, so it builds a cobblestone generator from a bucket of lava and a bucket of water (a short trench dug into the ground, water falling into a hole at one end, lava at the other, mined from a pocket beside the cell where cobblestone forms; grass within the lava's reach is cleared first and no table or furnace goes near it), replants every tree it cuts (breaking leftover leaves for saplings and waiting by its saplings when it needs wood), makes a spare log into charcoal for its iron (a log and a plank into the furnace), and skips the iron chestplate, as there are no caves to go down into. It counts stone in sight there only within eight blocks; farther off it is likely on another island. Charcoal and crossing between islands are not done yet. It sees only blocks it could see (exposed blocks nearby; ores, fluids and caves also need a line of sight). A walk out to look for trees ends as soon as it knows of a log it would chop, rather than running its full length (F-25c4aw). It looks for ore the way players do: ore in view first, then into caves it has seen toward the depth where that ore is common, lighting the dark with torches it crafts from spare coal; it digs (a staircase down, then branch mining) only when it has no ore or cave in view. Every step is one bounded skill through the same activity, ledger and body checks as the commands above. It takes the crafting table and an emptied furnace along when it leaves to gather, makes and wears an iron chestplate as soon as it has the iron for it, where there are caves to go down into, then looks for the rest of its iron (F-25c4x), fights back against a mob that is after it without chasing it into lava or off a drop (against a heavy hitter such as a hoglin, whose toss can go sideways as well as back, it first steps to where no edge lies behind it or to either side) (takes cover behind its own block from a skeleton it cannot reach, but never while a zombie or other mob on foot is in reach, which it fights or backs away from instead, and backs off when badly hurt, breaking off a fight that has worn it down; with no sword or axe, as after a respawn, it keeps away from mobs and takes cover from skeletons rather than fight with its fists, and one it has to fight it strikes with what in its hotbar hits hardest, its pickaxe as a rule, F-25c4r), never stands where lava beside or above it could flow in, swims to the side of water it cannot walk out of (a flooded pit) and digs a step out of it, treading water while it digs (F-25c4t), chops a tree standing in water, or anything else it gathers that it can reach only from deep water, from the water while its breath lasts, treading water (F-25c4as), swims out or up for air with less than half its breath left even with a mob after it, without stopping to eat, and fights once it can breathe (F-25c4af), and passes over dry ground it set out for and could not get to, as when a stream carries it past, for the next nearest (F-25c4aa), digs itself out at once if gravel falls into its head space (or a block beside it reaches into its eyes), never digs out the block over its head that sand or gravel rests on, walls itself in when a dig breaks into a cave of mobs it is no match for and waits there to heal (with one still after it a minute on, as from a spawner, it digs away from it on its own level, sealing behind it each cell toward it, F-25c4au; and walls off a cave whose mobs it can see from its passage before they notice it, and an enderman it provoked), explores caves without a shield only where it cannot hear mobs nearby, never digs a staircase step over a deep drop or down into a place where mobs killed it, holds a tunnel mouth against a mob on foot instead of chasing it into the open, stays away for a day from the cave where a mob killed it, smelts its first iron straight into a shield when it kept wood for one (raised while closing in on a skeleton, and toward a creeper about to blow) and makes its iron pickaxe as soon as it has three more where it mines its frame (where it casts its frame it makes it with the rest of the route's iron, once all of it is in hand, for the way down to the lava, F-25c4q; one that wears out once both buckets are made is not made again: stone ones dig the rest), jumps as its swing recovers and strikes on the way down for critical hits (half as much damage again; not against a creeper, nor in water or on a ladder), fights a creeper hit and run or sprints away from it, digs in on the surface at night and seals the way in with its own dirt or stone (with neither, with sand or gravel where it rests on solid ground, F-25c4aq) while it waits for daylight, and in the morning, with no other way up, opens its own seal again, by hand with no pickaxe (F-25c4at) (below ground the night changes nothing: when its next step is logs from the surface, it mines the iron it still needs meanwhile; when it is water from the surface, it closes its way in and waits there for daylight, first filling a bucket at lava for its cast that it knows of below, within a walk), loads a furnace with fuel matched to the ore (one coal for every eight items, and no more ore than its fuel can smelt; coal first, then planks, made from its logs if need be, and its sticks last), cooks the raw meat it carries in its own furnace before taking the furnace on, and drops surplus stone when its inventory fills (the items stay in the world). Hurt with nothing to eat, it climbs toward the surface by day, and once up out of the ground it hunts an animal in sight, fishes, or walks out to look for animals, rather than going straight back down to its work (F-25c4ab); once up, ground a few blocks below where it last had open sky over its head still counts as up, so its search goes on under an overhang or through a hill's side (F-25c4ag). Climbing toward the surface under a lake, every step up refused by the water over its ceiling, it tunnels on one way until the ground over its head is dry and climbs out at the shore, and a climb for water goes on to the top of its staircase (F-25c4ah). Casting its frame in rock, it opens the cell in front of each top block's water first, so the water can be taken back once the block hardens (F-25c4ai). Opening the room for its frame, it digs first the cells it can reach and see, which shows the ones walled in behind them; with none left it can break, it moves to the frame's stand, then beside the cell in the row in front, before it gives the site up (F-25c4aj). Standing in a hazard (on magma, at a lava pool's edge, in fire) it steps to the middle of the safe cell beside it (F-25c4am). After three minutes with no progress the watchdog moves it elsewhere: it digs only along the ways that close on the new place, never back down a step it climbed, and then its plan has three minutes of its own (F-25c4an); a new place farther than one walk reaches it walks to a leg of 96 blocks at a time (F-25c4ar). Climbing out from under sand or gravel, it first digs the block that will be over its head after each step, from where it stands, and lets what falls settle before it steps (F-25c4ao). With no wood and none known close by, on open ground it looks for trees on the skyline out to 96 blocks and walks to the nearest it sees, even past the bound its walks to look keep to (F-25c4ap). Digging toward lava it sees but cannot walk to, it goes at the lava's level only the two ways that bring it nearer, and lays a block of its own over a drop on the way, as across a cave (F-25c4ak). On the Nether goal, once its portal is lit the goal is done: it stays by its portal, and nothing more is dug or climbed for the goal (F-25c4al). Wanting logs with no way up from where it is, it heads back toward home, a leg of about 96 blocks at a time when home is farther than one route reaches (F-25c4ae). With nothing it can do where it stands, it takes the shortest jump off its ledge it can afford, onto ground or into water two or more blocks down; water level with its own floor is not a jump, as it would only swim straight back out (F-25c4w). If it makes no progress for about three minutes of game time (no new stage, nothing new gained, no new ground), it gives up on the spot and tries somewhere else; the panel shows "tried elsewhere N times" and the log records each attempt. Progress shows in the panel and in the server log as `<name> goal at x y z: …`. Two lines are there for reporting a stall. A player on its goal that has started nothing for a minute of game time logs once `<name> has started nothing for N ticks on its goal: …`, with the gates that hold it. A step it was given that is dropped before it runs logs `<name> <skill> dropped before it ran: …`, at most once a minute.

Players of the same controller in one world within 48 blocks of each other are a team. When one comes to hold more diamonds, iron or gold than before, it shares out what it has not promised by what each of them lacks, says who gets what, makes each piece at a table and walks over to toss a teammate's across; the log records `<name> handed over …`. A teammate it cannot get to, walled in say, it walks for at most six times without getting nearer, then lets the hand-over go, logs `<name> lets go of handing over …`, keeps the piece and goes back to its work. A teammate gone more than 48 blocks off it does not walk after: it waits a minute at a time, logging `<name> cannot go on with … its teammate N blocks off`, and lets the hand-over go after six waits (E-40a1).

**The End goal (F-25c6, in development).** `/aiplayer goal end` earns the portal as above, then goes on the way players do:
- It stocks up (food, blocks, a spare pickaxe, iron into a sword, a shield, a helmet and boots) and goes through its portal, or any lit portal it has seen.
- In the Nether it looks for a fortress for blaze rods and for endermen's pearls, then comes back and makes eyes of ender.
- It throws one and watches it fly, walks 200 blocks to the side and throws another, and heads for where the flights cross. There it throws one more close by to pin the spot, digs down beside it (from the nearest dry land if the spot is under water) and looks for the portal room.
- It stops with the End portal in sight and says where in chat.

The player may use portals only while this goal runs. It never enters the End. Not yet seen in play: fortresses, blazes, endermen, eyes made from what it gathered, and the portal room. See [F-25c6](evidence/F-25c6.md).

**The dragon goal (E-39a–g, in development).** `/aiplayer goal dragon` plays the End goal and goes on into the End, but only once an operator has run `/aiplayer dragonfight on` (kept in `edits.properties`, off by default; without it the goal is refused). It fights the way players do:
- It fills the portal's frame with eyes it carries, one at a time, walking round the ring for the rest, and steps in.
- On the main island it shoots the End crystals off their pillars with its own bow and arrows, aiming on the arc an arrow really flies, from spots where the whole arc is clear, 16 to 56 blocks off, away from the edge and the dragon's perch. A crystal it cannot get a clear arc to is left for a minute and tried again.
- A caged crystal it cannot shoot through the bars it opens: it pillars up with its own blocks beside the pillar, breaks the bars on its side, and digs its pillar back down a block at a time. After a restart it finds its own pillar again. Up there, if it sees the dragon coming its way, it walls in its feet and puts a block over its head before the wings arrive (they fling a player sideways and lift it, every tick it is in reach), waits while the dragon goes by, then carries on. It never pearls onto a cage's roof: a roof of bars catches a pearl only about half the time (a pearl strikes as a point and most of a pane is gap), and a pearl that falls through onto the crystal blows it up beside the player, as a fixture showed.
- Ender pearls it throws on the arc a pearl really flies. Each throw is flown through the real blocks first, and again sixteen times as vanilla's spread would throw it, plus the spread's extremes; it throws only when fourteen of the sixteen land where it means and none could strike an End crystal or leave a deadly drop. In the End, when it finds itself on top of a pillar or a cage that is not its own tower, it steps off onto end stone and breaks the fall with its water bucket, keeping its pearls; with no bucket it pearls down.
- In every fight it uses the weapon that deals the most, by vanilla's own damage numbers (a sword's quick critical hits beat an axe's slower ones; the axe for one last hard blow), each swing at full charge.
- It shoots the dragon in flight when it has a clear arc, and when the dragon perches it goes to a spot in front of the head, out of the wings' reach (they fling players away) and the head's touch (it hurts), and strikes the head with jumping critical hits. Arrows are kept for the dragon in flight: while it sits, vanilla sets arrows alight and lets them do nothing. It steps out of the dragon's breath at once, never toward the perch or through another cloud, and when hurt it rests away from the perch.
- When the dragon dies it says so in chat, goes through the exit portal, and after the credits is brought back to its spawn as a player's client would ask; the goal is then won.
- Before going in it sets down a bed in the portal room and uses it, so a death in the End starts again there. It gathers the bed's wool from sheep (three of one colour) and crafts it on the way, as it does the bow and arrows. Back from the End with the dragon alive, it gathers the End fight's kit again (a bow, arrows, blocks, a water bucket, food) before going back through; the bed already by the portal counts as the bed.

Pace: `/aiplayer mode speedrun [player]` makes a player take the quickest way of each part of the run it knows; in normal play (`/aiplayer mode optimal`) it takes a speedrunner's way only when the chance is at hand, never going out of its way for one. A way that fails twice is set aside for the next. No part of the run reads the pace yet: the ways it changes (a ruined portal completed, bartering with piglins for pearls, the Nether shortcut to the stronghold, beds against the dragon) are listed in the playbook but not yet played.

The log shows `<name> in the End: N crystals left (C caged), the dragon at H health` as the fight goes, and `<name> dragon fight: hits, on the head, critical` after each perch fight. A staged fight (an End kit and a teleport given by command, so not earned) killed the dragon and came home on seed 8675309; see [E-39d](evidence/E-39d.md). Not yet seen in play: the bed and the gathering again after a real death in the End, the pearl way in a real fight, and an earned run from the stronghold; see [E-39e](evidence/E-39e.md), [E-39f](evidence/E-39f.md) and [E-39g](evidence/E-39g.md).

**Hoglins (E-07e).** Gold armour keeps piglins neutral, not hoglins: hoglins attack any player they see.
- **With time to spare:** when a hoglin comes for a player that has a goal, the player pillars two blocks up with its own blocks, where a hoglin cannot reach it, and waits it out. It stays up while one is near and can see it, and goes back to work after a minute and a half.
- **Kept up there:** after half a minute with the hoglin in reach below, it strikes it from its perch, where the hoglin cannot strike back.
- **Already on the player:** a hoglin that reaches it before it can get up is fought.
- **Without a goal:** it never starts a fight with a hoglin.

**Gold for piglins (E-07e).** One piece of gold armour worn keeps piglins neutral.
- **What it makes:** a golden helmet, five gold ingots, which guards as well as an iron helmet.
- **Where the gold comes from:** nether gold ore mined with any pickaxe gives two to six nuggets a block, and nine nuggets make an ingot at its crafting table.
- **When it gets it:** with the Nether ahead it takes the gold as the chance comes, from ore within twelve blocks. In the Nether with no gold on it goes up to 48 blocks for ore it knows of.
- **When it wears it:** in the Nether it puts the gold on, carrying the iron piece it swapped out. Out of the Nether the better piece goes back on.
- **Piglins nearby:** it never breaks gold ore, or any other block piglins guard, with a piglin within sixteen blocks. Vanilla angers every piglin that near, seen or not, and gold does not calm a piglin angered that way. It never strikes a piglin that is not after it.
- **Not yet:** Overworld gold ore (raw gold to smelt) is not gathered.

**Combat rules (E-41a, in development).** AI players fight by vanilla 1.21.1's rules and within a player's limits. They strike no further than a player can, three blocks from the eyes to the target's hitbox, and only what they can see. A shield an axe has disabled stays down its full five seconds. They cannot see a creature's health any more than a player can: they judge it from the hits they saw land, so a mob hurt by something else is fought as if it were whole. Damage, critical hits, the attack cooldown and damage immunity are vanilla's own, so an AI player hits exactly as hard and as often as a player with the same gear. Players are attacked only inside a match they joined (not yet available). A fight wears weapons, armour and shields as a player's fights do. One that breaks is lost to wear, and the player goes on without it, even a pickaxe it struck with for want of a sword. Any other loss from what it owns stops it for an operator to reconcile (F-25c4o). Each fight is recorded, as what the player saw, chose and was confirmed to have done tick by tick, in `kindred/encounters/<player id>/` under the server's folder, one file a fight and only the newest 50 a player kept.

**Breaking a fall (E-07d).** A player falling with a water bucket in its hotbar pours water on the block it is about to land on, once the block is in reach, whenever the fall would take four health or more. It lands unhurt and scoops the water back up. The water comes before anything else, even a fight its goal wants with a mob about it (F-25c4p). This works everywhere but the Nether, where water boils away. With a bucket ready it will also jump down ledges and drops of up to forty blocks, not twelve. Keep a water bucket in its hotbar where it climbs. Native players take fall damage as any player does, and vanilla spares any player that has just joined for its first three seconds.

**Building (E-08a, E-08b; in development).** `/aiplayer build hut <x y z> [quarter turns] [player]` has a native player build a hut with its north-west floor corner at that spot, turned clockwise a quarter at a time. The hut has a plank floor, cobblestone walls on upright oak logs, a doorway in the south wall, a torch inside on the north wall, and a gabled roof of oak stairs facing in over a plank ceiling. `hut-7x5x3` sets the width, depth and wall height (3 to 15, 2 to 6); the default is 5×5×3, which takes 40 oak planks, 34 cobblestone, 12 oak logs, 20 oak stairs, 5 oak slabs and a torch. It says what it is short of when it starts. It works the way a player does:
- It builds layer by layer, the nearest block first, each against a face it can click from where it can stand and walk to.
- It stands in each block's centre to place it and looks the way that gives the block its state: a stair's facing and half, a log's axis, a slab's half, a torch on its wall. Every block is checked in that state afterwards; one placed wrong is broken and placed again, and after two tries it is left for the operator.
- What is in the build's way is broken first, but never a container or anything not the build's to break.
- Where nothing is in reach from the ground it scaffolds up with spare dirt or cobblestone (never blocks the build still needs) and digs its scaffold down to get off it. It takes all the scaffolding away at the end.
  - It comes all the way down a column before it walks off to the next, one block at a time, landing on each before it digs the next (E-08f).
  - Hemmed in by its own scaffolding, with nothing it can walk to in reach of the block it wants, it takes that scaffolding down first, rather than give up on the block (E-08f).
- Survival needs still come first. The build goes ahead of a standing goal and the goal resumes when it is done.
- After eight failed steps in a row it stops and says why. `/aiplayer build stop` ends a build; what is built stays.

- A block with nothing to be set against (a log lying along a row with nothing at its ends) gets a prop: a short column of scaffolding beside it, taken down with the rest.

**Short of blocks (E-08c; in development).** A player short of blocks for a build treats its needs as a queue, as the owner set them. It does not stop to fetch each block as it comes up:
- **Easy, at once.** What its own storage holds, when the storage is within 24 blocks, it takes before it places anything. What it can make from what it carries, with no walk, it makes.
- **Then it builds.** It places everything it can with what it holds.
- **One trip for the rest.** Stuck for blocks, it fetches the whole shortfall in one trip: its storage first, then all the logs the rest takes in one go (from trees in sight, walking out to look when none are in sight; never from the build itself or any other: a log touching planks, stairs, slabs, a door, a fence, glass or the like, as a hut's or a cabin's corner post does, is never a tree to it), then it crafts what it needs at a table. A table it carries is set down, or it makes one. Then it comes back and finishes.
- **What it can make.** Every wood's planks, stairs, slabs, fences, gates, doors and trapdoors; sticks, crafting tables, chests, ladders; cobblestone and stone-brick stairs and slabs; glass panes, bookshelves and furnaces.
- **When it cannot finish.** When nothing it can do gets the rest, it finishes what it can and says what the build still wants, for example `hut 5x5: built but for 8 oak log, 5 oak slab, 20 oak stairs`.

Give a native player its storage with `/aiplayer storage at <x y z> [player]`, looking at a chest in its world. Only its administrator may assign storage. The storage is saved with the player's setup, and the player stops until you resume it. Every take is recorded, and checked against the server's edit policy just before the items move. A player with no work area uses no other container, apart from its own crafting and smelting.

**Bases (M-01b; in development).** A base comes in three stages, each a functional build of oak and cobblestone. `/aiplayer build cabin|house|manor <x y z> [quarter turns] [player]` builds one with its north-west floor corner at that spot. Whatever the player is short of goes through the needs queue above. All three have been built by AI players in testing:
- **A cabin** (7×7, 222 blocks): a bed, a table, a furnace and two chests.
- **A two-floor house** (9×11, 553 blocks): a storage wall, a workshop, and a staircase up to a bedroom.
- **A manor** (13×15, 972 blocks): a storage hall, a workshop, an enchanting room ringed by bookshelves, bedrooms and a library.

Doors and beds now have a placement rule: each is placed by its first half, and the other half comes with it. Players walking about open a closed wooden door in their way with a click, as a player does, and shut it again behind them. **Its own base (M-01c; in development).** With `/aiplayer bases on` (operators only; AI world edits must be on too), a player that is playing builds its own base as it goes.
- **When.** It does this when it has no goal, build or ranch of its own and is not stopped. A new player only looks on until it is set to play, with `/aiplayer mode optimal` or the K panel.
- **Which stage.** It moves its base on a stage when it is ready:
  - a cabin once it has stone tools;
  - a house with iron tools, once the cabin's chests are three-quarters full or three days have passed;
  - a manor once it has an enchanting table's makings.
- **Where.** On the nearest flat, open, natural ground within 24 blocks of its home, or of where it stood when it first looked. Each later stage goes beside the last. It says why and where as it starts.
- **Building it.** It builds through the needs queue. For stone it digs a staircase down off its site, and it climbs out after.
- **Remembered.** Its bases are kept across restarts in `kindred/memory/<player>.bases.json`. A stage it did not finish is taken up again where it stands.
- **Life first (F-25c9).** A player with work of its own (a build, a ranch, or its base) puts its life first. At night it goes to a bed of its own base, sleeps there, which sets its spawn, and goes back to its work in the morning. Out exploring when night falls, it heads for that bed rather than back to where it set out from. When its hunger is down to seven shanks and it does not carry enough to last, it takes what it lacks from the food in its own storage first (`/aiplayer storage at`), then hunts for the rest until it has a full bar and a reserve, sparing the animals its ranch is penning. With no food about, it goes back to its work and looks again after five minutes.
- **Kept whole (E-31a).** When it looks to its base it checks what it built against the plan. A chest broken, a wall blown out or any block gone or out of place is built back as any build is, from what it holds or fetches; a chest is crafted again from planks. A repair that ends short is tried again after five minutes.
- **Stopping one.** `/aiplayer build stop` on a base's build puts it off for five minutes.
- **Grown, not stamped (E-29a).** Each stage is grown from a seed block and its world: the same stage comes out a different size, laid out the other way about, with its ridge and windows otherwise, and facing another way in another world or for another player. Give a player a seed block with `/aiplayer seed <block> [player]` (`/aiplayer seed clear` takes it back): a wood seed, a cherry log say, sets the wood it builds in. Without one it builds in the wood it carries most of, else that of the nearest tree it knows. A build cuts only logs of its own wood.
- **Not yet:** the base's chests do not become its storage, and its walls are cobblestone whatever the seed.

**With AI world edits off** (the default), AI players still keep themselves alive without changing a block: they eat when hungry, step out of fire, lava and deep water and away from a hissing creeper, and defend themselves from a mob that is on them. So does a player set idle. They break, place, equip and work on nothing until an operator records `/aiplayer edits on`, and a stopped player does nothing at all.

**What AI players say.** A native AI player talks as the companion body does: with whoever may command it (whoever made it, and operators) as with an owner, in private, and with others only when its public conversation is on (`/aiplayer social public true [bot]`), within a few blocks, at most about once every two minutes unless spoken to. What it says of its own doing (a portal lit, a build finished) goes to its owner and administrators with where it is, and to others who may hear it without the place; never to an opponent in a match it is in. Hurt, it says what the game recorded as hurting it, once: a fall blames nobody.

**A walled keep (M-06a; in development).** `/aiplayer build keep <x y z> [quarter turns] [player]` builds the first stage of a walled base for a Factions world, of oak and cobblestone. The wall's north-west corner stands four blocks east of that spot, leaving room for the stair down outside its west wall. It is the cabin inside a walled yard, the wall 17 blocks west to east and 16 north to south, 687 blocks in all:
- **The wall:** stone, four high from a foundation in the ground, three above the yard, lined inside with planks its whole height. Torches on the lining light the yard.
- **No gate.** The way in is a stair down beside the west wall, three blocks in from its south end; a passage under the wall, three below the yard; and a stair up into the yard. The stair and passage are lined with stone, and nothing in the wall shows where the way in is.
- **Inside:** the cabin, with its bed, table, furnace and chests.

`/aiplayer build farmward <x y z>`, at the keep's own spot, builds its second stage: a farm ward east of the keep, sharing its east wall, its own three walls lined as the keep's, reached from the keep's yard by a way under the shared wall and from outside by its own way under its east wall, and a field nine square with a stone-lined channel down its middle for water. `/aiplayer field <x y z>`, at the keep's spot, then has the player pour a bucket of water it holds into the channel, till every cell of the field with its own hoe and sow it with its own wheat seeds, working from the field's margins and never standing on its farmland; with too little it does what it can and says why. `/aiplayer build pastureward <x y z>`, at the keep's spot, builds its third stage: a pasture ward south of the farm ward, sharing its south wall, its own three walls lined, an oak pen seven square inside, a doorway for cattle built shut in its south wall in line with the pen's gate, and its own way in under its east wall. `/aiplayer pasture <animal> <x y z>`, at the keep's spot, then has the player open the doorway, lead a pair in by it into the pen, breed them, and shut the doorway behind them with the blocks it took out of it (a pair jammed side by side at the doorway or the pen's gate it lets wander off with the food put away, then leads up one behind the other; a cow left in the gateway as the gate shuts, or one that gets out while the other is fed, it fetches in, M-06a1); `/aiplayer ranch stop` stops it. `/aiplayer build hall <x y z>`, at the keep's spot, builds its fourth stage: the hall, the base's two-floor house in its own ward south of the keep, sharing the keep's south wall and the pasture's west wall, lined as the others, with its own way in under its west wall. `/aiplayer build walltower-1 <x y z>` to `walltower-5`, at the same spot, then builds a solid stone tower at each of the compound's outer corners in turn, from the north-west round, three above the wall and crenellated. Bringing in the field's harvest, a way up into the towers, and the moat and bridge are to come, as is a compound a player chooses and builds for itself in a Factions match.

**Schematics (M-05; in development).** Put vanilla structure files (`.nbt`, as a structure block saves them) and Sponge schematics (`.schem`, versions 2 and 3, as WorldEdit saves them) in `config/kindred/schematics`, then `/aiplayer build <name> <x y z> [quarter turns] [player]` with the file name without its extension. Beside `<name>.nbt` or `<name>.schem`, a `<name>.license` file in properties form keeps who made it and on what terms: `creator=`, `license=`, `source=` (a link or a note). The player says them when it starts, with `no license recorded` when there is none, so only use schematics you may use (D8). Kindred never fetches schematics from anywhere.

When it reads a schematic it:
- keeps air as space to keep clear, and ignores structure void;
- places containers, signs and the like empty, never copying their contents;
- leaves out entities;
- leaves out blocks a survival player cannot get as an item (bedrock, spawners, end portal frames, command blocks, infested stone, farmland and the like);
- leaves out blocks or states this version of the game does not know.

It says what it left out. A schematic may be up to 96 blocks a side and 32,768 blocks. Blocks with no placement rule yet (signs, buttons, rails, redstone, plants) are skipped and listed.

The log shows `<name> build: hut 5x5: N/112 placed - <next step>` as it goes, and the player says when the hut is built. A build an operator set it to, by generator or schematic, is kept in the world's `kindred/memory/<player>.work.json`, with the scaffold it has up, and so is a farm ward's field. After a restart the player takes it up again from what stands as it reconnects; a player that was stopped stays stopped until resumed. A ranch is not kept yet. Not yet: stone gathered for a build, smelted blocks such as glass, Litematica `.litematic` files. See [the building plan](building-plan.md), [E-08b](evidence/E-08b.md), [E-08c](evidence/E-08c.md) and [M-01b](evidence/M-01b.md).

**Duels (M-00a; in development).** Players fight one another only inside a match they joined (D7), and only on a server that allows PvP at all (`pvp=true` in `server.properties`; with it off, no duel is proposed or started). `/aiplayer match duel <first> <second>` proposes a duel between two players, AI players or humans online:
- An AI player is entered by whoever may administer it; proposing needs that access for each AI player in it.
- A human is only invited: the invitation has a **[Join]** to click, which runs `/aiplayer match join <match>` as that human. It is in the match only after joining itself, and nobody can enter a human into a fight.
- Once everyone has joined, an operator or a player in it starts it with `/aiplayer match start <match>`. Three seconds of countdown follow, in which nobody may strike, then the fight.
- The AI players in it fight with what they carry (the weapon that deals the most, with the timing and critical hits of their other fights), walking over first when the opponent is far.
- It ends when one player falls (the other wins), when one forfeits (`/aiplayer match stop <match>` from a player in it, or leaving the game, an AI player's body taken out included), after five minutes (a draw), or when an operator stops it. A player leaving before the start calls it off. The leave to strike ends with it at once, mid-swing included. Matches are not kept across a restart; a restart ends them all.
- The fight takes the same damage, wear and deaths as any other: nothing is restored afterwards, and the fallen player respawns and drops its items as in any death.

Each player in it sees the match as a minigame server shows one, on an ordinary client with no mod: a boss bar with the players and the clock, titles with a sound for each second of the countdown, for the fight's start and for its own result (Victory, Defeat, Draw, Stopped or Called off), and the match's state in chat. A match goes by the first eight characters of its id (any start of four or more that no other match shares will do); `/aiplayer match list` shows them. See [M-00a](evidence/M-00a.md).

**Speedrun races (M-03a; in development).** `/aiplayer match speedrun <runner> [runner...]` proposes a race to the Ender Dragon for one to eight runners, AI players or humans online. It is joined and started like a duel (`join`, `start`), with the same three-second countdown.
- **A fresh start.** Every runner must start with nothing: an empty inventory, and none of the race's split advancements. A runner that carries anything, or has reached a split already in this world, is refused with the reason. So a race wants a new world, which is yours to make, with a random seed for a fair run.
- **AI runners.** An AI runner also needs `/aiplayer edits on` and `/aiplayer dragonfight on`. When the race starts it plays the dragon goal at speedrun pace. When it is out of the race, the goal ends and its own pace comes back.
- **No fighting.** Nobody may strike another player in a race. A death is only a setback: the runner respawns and runs on.
- **Splits.** They are vanilla's own advancements, so the game itself says when a runner got there, for humans and AIs alike: Iron (Acquire Hardware), Nether (We Need to Go Deeper), Blaze rods (Into Fire), Stronghold (Eye Spy), The End (The End?) and Dragon (Free the End). Each split is shown to every runner in chat and over the hotbar with its time, the game's own (ticks since the start). A boss bar shows the time and fills as the leader reaches each split.
- **The finish.** The first runner to free the End wins and the race is over. A runner who forfeits or leaves is out, and the rest run on.

See [M-03a](evidence/M-03a.md).

**Manhunts (M-02a to M-02c; in development).** `/aiplayer match manhunt <runner...> vs <hunter...>` proposes a manhunt between two and eight players, AI players or humans online, joined and started like any match.
- **Fair game only once joined.** A runner and a hunter may strike one another; two on the same side never may. A human is fair game only once it has joined (D7).
- **Everyone starts with nothing.** It needs the server's PvP on. An AI player in it needs `/aiplayer edits on`, and an AI runner `/aiplayer dragonfight on` as well.
- **The runners** make for the dragon. An AI runner plays the dragon goal quickly but reliably, at its own steady pace rather than speedrun pace. It keeps its distance from a hunter it can see within twenty blocks, running off away from it, and fights only once caught. It never runs toward its home, its base or else the bed its spawn is set by, while any other way off is open, so it does not lead its hunters there. Running weighs more than eating, so it eats only once no hunter is in sight within twenty blocks. A runner who falls is out. When no runner is left, the hunters win. A runner who frees the End wins it for every runner.
- **The hunters** respawn when they fall. An AI hunter first makes its kit on the run's own route: its tools, then an iron sword and a shield from the iron it mines. Then it hunts: it follows its compass, and fights a runner it can see. It gears up as the hunt goes on: iron armour from the iron it holds (a chestplate, leggings, a helmet, boots, in that order), made and put on before it follows the needle on.
- **The tracking compass (D6).** The one item a manhunt hands out, by a rule announced to everyone at the start, the same for human and AI hunters. Every hunter holds one while the hunt runs. It points at the nearest runner in the hunter's world, or where a runner last left that world, and spins when there is neither. It carries the curse of vanishing, so a hunter's death takes it, and a new one is handed out once the hunter is on its feet. Every one is taken back when the hunt ends. An AI hunter reads only the needle's direction, as a player does, never the runner's place.

**A ranch (M-01a; in development).** `/aiplayer ranch <cow|sheep|pig|chicken> <x y z> [player]` has a native player make a ranch with the pen's north-west post at that spot. Only its administrator may ask, and only where AI world edits are allowed.
- **The pen.** Seven by seven: 23 fences and a gate in the middle of the south side, of the wood it has most of. It crafts them at a crafting table from its own logs or planks, setting down or making a table if none is near. It builds the pen as it builds anything. With too little wood it says so and stops.
- **The pair.** With the animals' own food in hand (wheat for cows and sheep; carrots, potatoes or beetroot for pigs; seeds for chickens), it walks up to the nearest within 32 blocks. It leads them back a few blocks at a time and opens the gate. It waits inside for them to come in, then shuts them in with the food put away.
- **Breeding.** It feeds each of the pair one of their food, waits for the young one, and leaves, shutting the gate behind it. Nothing is given: the food and wood are its own, and each feeding is recorded.
- **What it says.** It says how the ranch went, or why it stopped: no food, no animals in range, or none ready to breed again yet. Survival needs still come first. `/aiplayer ranch stop` stops it, and what is built stays.

See [M-01a](evidence/M-01a.md).

**Build battles (M-04a; in development).** `/aiplayer match build <builder...> [for <seconds>] [theme <name>]` proposes a build battle for two to eight builders, AI players or humans online. Operators only, since it marks out the plots.
- **The plots.** One per builder, fifteen blocks a side, in a row east of where you stand, three apart, marked in white concrete with a grey edge. They need open, level ground, clear sixteen blocks up.
- **The theme.** Announced as a title at the start, by chance or as you name it: cottage, watchtower, pyramid or lighthouse. The build lasts five minutes unless you say otherwise (`for 120`; thirty seconds to half an hour).
- **Free blocks in this mode only.** Every builder must arrive with nothing, builds in creative mode on its own plot, and leaves with nothing in its own game mode. Whatever creative gave is cleared when the build's time is up, and again at the end, a server stop included.
- **Each to its own plot.** While it builds, a human may break or place only on its own plot, and nobody may on another's.
- **AI builders** turn the theme into a design of their own, with a size and materials by chance, and build it block by block with the same planner as a survival build. The theme alone decides the design.
- **The vote.** When the time is up, everyone looks on in adventure mode and scores every other build one to five: AI players at once by what they see standing on each plot, humans by clicking a score in chat or `/aiplayer match vote <builder> <score>`. Once everyone has voted, or after thirty seconds, the best mean wins (ties together), with fireworks over the winner's plot. With no votes it is a draw.

See [M-04a](evidence/M-04a.md).

When a native player dies, its current work settles first, vanilla drops its real inventory, and the same identity respawns about a second later at its bed or the world spawn. It goes back to pick its items up, the nearest first; a drop it cannot get to, in a hole or on a ledge, it passes over for the next, three at most (F-25c4s). Dead again before it has them back, on the way or by them, it leaves what it dropped that time too, rather than walk back into the place that has killed it twice (F-25c4v). After a mob kill it does so only once no hostile mob has them in sight, as the one that killed it may stand over them; it keeps away from them meanwhile, and the drops lie about five minutes. Nothing is restored. Restart while dead is not yet covered and needs reconciliation.

### Optional: a model strategist for the goal (F-19b, F-19d)
The goal plays the same without any model. For development runs a model can be consulted as a strategist: it reads the player's own short report (stage, gear, health, recent deaths and their causes, danger zones, its notes) and picks how careful the player should be for the next five minutes: press on, play safe (no caving, backing off sooner) or surface and recover (climb out to daylight and heal first, then play safe). It may also name up to three places near the player's deaths or danger zones to avoid for a while. It never moves the player, gives items, writes code or decides the goal is done; every moment is still the local plan. It is asked only when something changed (the goal starting, a new stage, a death, a spell with no progress at most every two minutes, and otherwise every five minutes while progress changes), never per tick, off the game thread, and a slow or failed reply changes nothing. Switch it on with environment variables in the server's environment:
```text
KINDRED_STRATEGIST=on                          required; anything else leaves it off
<provider>_API_KEY=<your key>                  one provider's key (see below) or KINDRED_STRATEGIST_API_KEY; read from the environment only, never stored or logged
KINDRED_STRATEGIST_PROVIDER=<name>             optional: anthropic, openai, gemini, groq, mistral, deepseek, xai, openrouter, openai-compatible
KINDRED_STRATEGIST_BASE_URL=<url>              optional; another address for an OpenAI-compatible provider, or a local server
KINDRED_STRATEGIST_MODEL=<model id>            optional; the provider's default below
KINDRED_STRATEGIST_PACE=steady|realtime        optional; default steady (see below)
KINDRED_STRATEGIST_DAILY_TOKENS=<tokens>       optional; daily budget for all players (steady 100000, realtime 10000000)
KINDRED_STRATEGIST_PLAYER_DAILY_TOKENS=<n>     optional; daily budget per player (steady 50000, realtime 5000000)
KINDRED_STRATEGIST_EFFORT=low|medium|high      optional; default low
KINDRED_STRATEGIST_THINKING=off|on             optional; default off
```

Any of these providers can serve it, each with a small, fast default model (F-19d):
```text
ANTHROPIC_API_KEY     Claude Haiku 5.5 (claude-haiku-5-5), through the official Anthropic SDK
OPENAI_API_KEY        gpt-4o-mini
GEMINI_API_KEY        gemini-3.5-flash-lite (GOOGLE_API_KEY also works)
GROQ_API_KEY          openai/gpt-oss-20b
MISTRAL_API_KEY       mistral-small-latest
DEEPSEEK_API_KEY      deepseek-chat
XAI_API_KEY           no default: name the model with KINDRED_STRATEGIST_MODEL
OPENROUTER_API_KEY    openai/gpt-4o-mini
```
Model line-ups change often: check the provider's current list, and set `KINDRED_STRATEGIST_MODEL` if a default has been retired. With several keys set, the first in this list is used unless `KINDRED_STRATEGIST_PROVIDER` names one. A single `KINDRED_STRATEGIST_API_KEY` also works; its provider is recognised by the key's prefix (`sk-ant-`, `sk-or-`, `gsk_`, `xai-`, `AIza`, then `sk-` for OpenAI), and a Mistral or DeepSeek key needs the provider named. Any other server speaking OpenAI's chat completions, such as a local Ollama or LM Studio, needs only `KINDRED_STRATEGIST_BASE_URL` and `KINDRED_STRATEGIST_MODEL`, and no key. All but Anthropic go through the JDK's HTTP client and the Gson the server already carries, so they also work from the release jar. The reply is asked for with a strict JSON schema (JSON mode for DeepSeek, or once more in JSON mode when a provider refuses the schema) and validated in core exactly as Claude's is. Anthropic's own models can be reached from the release jar the same way: `KINDRED_STRATEGIST_PROVIDER=openai-compatible`, `KINDRED_STRATEGIST_BASE_URL=https://api.anthropic.com/v1`, the key in `KINDRED_STRATEGIST_API_KEY` and the model named. Every request to Anthropic's server then asks for no thinking, as the SDK route does: its newer models otherwise think first, and a rescue's short reply came back empty (F-29). Nothing is cached on that route, so each consultation reads its whole report, about 6,700 tokens.
The pace sets how closely it follows play. Steady asks on the big changes (the goal starting, a death, a new stage) and otherwise every few minutes, within the contract's default budgets. Real time asks as often as the planning lane allows (every ten seconds per player at most), and also whenever something new happens: a fight with a new foe, a hit that costs six health or more, nightfall or daybreak. Its advice lapses after a minute of game time unless renewed. Answers come back in about two seconds: requests go at low effort without thinking first, with the instructions cached. Measured on one report, that was about 1.9 seconds every time, against 2.8 to 4.0 at the model's own default, with the same advice. Before the Minecraft guide was added (below), a question cost about $0.00013 with the instructions cached, so real time at the full rate was about five cents an hour per player; the guide is cached with the instructions, and a question now reads about 4,500 cached tokens and sends 200 to 300.

It also has a say in what comes next (F-19c). A reply may set a focus for the same five minutes: food first (hunt, and by day go out and look for animals when none is in sight, until well stocked) or wood first (logs while a tree stands). A reply's note may also teach a habit the player keeps in that world from then on: carry three more food, pack a second spare pickaxe for the work below, dig its own way rather than explore caves, or dig in for the night at dusk. A habit needs a note saying why, at most four are kept, and the strategist can retire one. A focus or a habit only reorders or resizes the route's ordinary jobs; fights, shelter and healing still come first. The habits live with their notes in the memory file below (shown as `"habit": "MORE_FOOD"` on a note). Delete the note, or the file, to make the player drop a habit; each goal the player starts in that world gets the habits it has learned there.

It thinks ahead too (F-19e). It is sent a general Minecraft guide with its instructions: the way to the dragon, what each larger task needs, recipes, where things come from, how far mobs notice a player, and the dangers. The report shows the player's queue of follow-ups (what the tasks ahead want, what it holds, the work left), and a reply may add up to three: a shield after deaths to skeletons, more food after hungry nights, more arrows. Each is a need from a fixed list (bow, arrows, shield, water bucket, bed, string, feathers, wool, flint, sticks, blocks, food) with a count and the task it is for. A reply may also advise one way of doing a part of the run from the same fixed set as the pace above; the player takes it only where it can be done and costs at most half as much again as its own choice. The player gathers and crafts them itself, as with the End fight's own needs above, and drops them when met or when their task is behind it. Only the End goal works the queue so far. The follow-ups are kept in the memory file below (`"queue"`).

Each death is logged as `<name> died at <x y z>: <message> (tick, day or night, under the sky or not, hunger, armour, what it held, a shield or not; what it was doing and its goal's stage; the strategist's stance, focus and habits in force)`, so a run's deaths can be weighed by what they happened in. The log says `Kindred strategist on` at start, `<name> strategist: <stance>[, focus <focus>] (<in> in, <cached> cached, <out> out tokens, <ms> ms) - <reason>` for each reply, `<name> strategist: habits in this world now [...]` when a habit is learned or retired, and `<name> strategist: queued [...]` when it adds follow-ups. Each player keeps its own memory for each world in `<world>/kindred/model-context/<player UUID>.json`: its notes, its deaths and their causes, the advice it was given, and the consultations and tokens they cost. A lesson already noted is kept once however the model rewords it, and the model is told to write a note only for a new lesson and only from what the report shows. Delete the file to make a player forget a world; it holds only the player's own game events and the model's notes about them. Requests are bounded: a fifteen-second deadline, one at a time, at most six a minute per player and twelve in all, and the daily budgets, reserved before each request and settled to what it used, with input read back from the cache counted for a tenth, as it is billed. A consultation costs about 4,700 to 5,100 input tokens, about 4,500 of them read from the cache, and 200 to 300 output tokens. The SDK is on the development classpath (`runServer`, GameTests) but not yet in the release jar; there the switch logs a warning and play goes on without it. From the release jar an Anthropic key still works through Anthropic's OpenAI-compatible endpoint: `KINDRED_STRATEGIST_PROVIDER=openai-compatible`, `KINDRED_STRATEGIST_BASE_URL=https://api.anthropic.com/v1`, `KINDRED_STRATEGIST_MODEL=<a model id>` and the key as `KINDRED_STRATEGIST_API_KEY`. The instructions are not cached that way, so a consultation reads about 6,700 tokens at the full price (the evaluation runs of F-29 were made so).

### Lessons from its own play (E-46)

With or without the strategist, each player learns from its own deaths in a world and keeps what it learns as a note
with a habit, in the same memory file, as the strategist's are:

| What happened | The habit | Its note |
| --- | --- | --- |
| Two deaths with food at six or below within two game days | carry three more food | "Twice dead hungry; carry more food." |
| Two deaths at night under the open sky within two game days | dig in at dusk | "Night on the surface has cost two lives; dig in at dusk." |
| Three deaths below y 40 in the Overworld within one game day | dig its own way for ore, not caves | "The caves took three lives today; dig for ore instead." |
| Its last pickaxe broke below ground, with none spare | pack a spare pickaxe | "Stranded without a pickaxe; pack a spare." |
| Killed below ground by a mob standing where it had walked, in its own tunnel | torches as it goes: one where the light at its feet is down to 6, about every eight blocks, from its own torches | "Something spawned in my own tunnel; torches as I go." |
| Two fall deaths within two game days | the water bucket kept in the hotbar, where a fall's clutch reaches it | "Falls have cost two lives; keep the bucket to hand." |

A habit it learned so and has not needed for three game days is dropped again, with a note saying so ("Three days
without dying hungry; dropping the habit of carrying more food."). A habit it already keeps, from a lesson or the
strategist, is not taught twice, and one dropped is not taught again from what came before. The habits only reorder
or resize ordinary work, as the strategist's do, and at most four are kept. The report the strategist reads marks a
lesson's habit `from its own play`, and the strategist is told not to teach it again, though it may retire it. The log
says `<name> lesson: learned <HABIT> - <note>` or `<name> lesson: dropped <HABIT> - <note>`. The memory keeps, with
each death, the game time, its height, whether it was night or under the sky or in the Overworld, its food, how it
came (a mob, a fall, or else) and whether a mob in its own tunnel struck it; nothing of anyone else. Set
`KINDRED_LESSONS=off` in the server's environment to turn the lessons off (an evaluation scenario says `lessons=off`).

### The atlas (F-30)

Each player keeps a map of each world from its own play, in its own file beside its memory
(`kindred/memory/<uuid>.atlas.json` in the world's folder), read once when it connects, written once a game minute and
when the server stops, off the game thread. It holds:

- the chunks it stood in, with the biome and when, and the chunks it saw iron, diamonds, coal, lava, water, trees, a
  fortress's or a stronghold's blocks in, with where it last saw each. Only what its sweep saw goes in: blocks in its line
  of sight and open to the air, as it already remembers them; nothing is read from the world it could not see;
- where it was hurt by a hostile mob or killed, by 16-block cell, by night or by day, fading to nothing over three game
  days;
- its bed and the holes it sealed itself into for the night.

At most 4,096 chunks are kept (a chunk it never stood in goes first, the one longest unseen), 1,024 danger cells and 64
shelters. Where each resource is likely in 1.21.1 (diamonds near the bottom of the world, iron and coal in their bands,
villages by biome) comes with the mod as data.

What changes in play: wanting iron or diamonds with none in reach, a player goes back to where it last saw some, a leg of
up to 96 blocks at a time ("Back to where it saw diamond ore at ..."), before it explores a cave or digs; stood there with
none in view, it lets that place go. At night a walk pays extra for each block in a cell where it was hurt or killed at
night (up to three times an ordinary block's cost), so it takes a way round of about the same length, never a long
detour. The strategist's report has one line: how many chunks it knows and where it last saw iron and diamonds. Set
`KINDRED_ATLAS=off` in the server's environment to turn the atlas off: nothing is kept or read (an evaluation scenario
says `atlas=off`).

### Rescue plans (F-29)

With the strategist on, a player that is stuck and whose own move elsewhere has had its turn (its watchdog's second
spell in a row of three minutes with no progress) asks the strategist for a way out. It asks once for each such spell,
at most once every five minutes, within the same rates and daily budgets as advice. It sends a report of about 800
tokens:

- where it is, and its health and food;
- the light at its feet;
- the blocks round it, nine by nine and five high, each kind a letter;
- the last five things refused or failed, with why;
- what it carries;
- what the goal wants next;
- its stance and habits;
- what its own tries did.

The reply is one to six steps, each in one of these forms and nothing else:

```text
walk <north|south|east|west> <1-8>       walked to the block that many away, round what is in the way, at about its height
dig <north|south|east|west|up|down> <1-4> the blocks in the way: a passage two high sideways; over its head, or under its feet
pillar <1-6>                             up that many, a block of its own from the hotbar under its feet each jump
place <north|south|east|west>            a block of its own from the hotbar beside its feet
wait <1-4>                               seconds
eat                                      food from its hotbar
retreat <north|south|east|west> <1-3>    back off without turning
```

The strategist is told what each step does as it is carried out: a dig does not move the player (it climbs a shaft dug
up with `pillar`, and drops into a hole dug down); a walk climbs one block at a time at most; it breaks only what a tool
it carries can harvest, so stone, deepslate and ores need a pickaxe.

Core reads the reply and refuses the whole of it for one step that is not in that list or outside its bounds. A plan
is also refused, with nothing moved, when:

- a step's skill is not installed;
- the player was stopped, set going again or given a command since it asked (its fence);
- the stuck spell is over;
- the player is no longer where it asked from.

While it waits for the reply, a few seconds, its goal gives out nothing and a walk of its own under way stops where it
is, so that the plan finds it where it asked from. What is a danger to it still comes first: a hostile mob within 16
blocks, water, lava or fire, its breath short, or its health or food at half or less. What it does of its own choosing
meanwhile (eating, say) does not refuse the plan; a stop, a resume or a command does.

A plan that passes is carried out a step at a time before anything else of the goal's. Each step is worked out from
where the player stands when it comes up, and is given out as an ordinary task: the server's edit policy, the claims
and the action ledger check and record it as they do every task. The first step that does not go ends the plan. With
replays on (F-27), the scene is captured as a `stuck` replay before the plan runs.

The log says `<name> rescue: asked, stuck spell <n> at <x, y, z> (<characters> characters reported)`, `<name> rescue:
plan pillar 3, walk north 2 - <reason> (<in> in, <cached> cached, <out> out tokens, <ms> ms)`, and how it ended:
`<name> rescue: pillar 3, walk north 2 -> unstuck`, `-> failed: <why>` or `-> refused: <why>`. An evaluation's result
lists each player's rescues. A rescue sends a report of about 1,300 to 2,200 characters; in the ten-seed evaluation on
Anthropic's server through the compatible route that was about 1,700 to 2,000 tokens in, with the instructions, and
about 100 out, at most 300. Set `KINDRED_RESCUE=off` to turn rescues off; the strategist's advice is unchanged.

### The player's voice in chat (E-20a)
With the strategist on, a player spoken to by name ("Echo, what are you doing?") answers in its own words: the same model phrases one short, clean line in the player's personality from what the player knows right now. It is told only what that listener may know. Whoever may command the player (its controller, an operator) is told everything, its position included. Another player is told what anyone beside it could see or ask, never where it is or what exactly it carries. An opponent in a match is told only what it could see for itself. The line is said through the same social lane as the fixed replies, with the same limits: eight blocks and in sight, public chat for anyone who may not command the player, a player's three seconds between direct replies, and each speaker's three seconds. Words alone never make the player do anything; only the asks below, from someone who may command it, do. A line that is too long, starts with `/`, carries formatting codes or rough words, or gives a place to someone who may not know it is refused. Then, or when anything else goes wrong (a timeout, an error, the budgets or rates spent, a line already being phrased for that player), the player says its fixed reply, as it does with no model. While one reply is being phrased, the newest message to the player waits for it; someone else whose message that one replaced is given the fixed reply at once.

```text
KINDRED_VOICE=off                              optional; the fixed replies only, though the strategist is on
```

Cost: each reply is one request on the chat lane, apart from the planning lane, with an eight-second deadline and 120 output tokens at most. It is reserved at about 1,000 tokens and settled to what it used. Replies count against the same rates (six a minute per player, twelve in all) and the same daily budgets as the strategist's advice: `KINDRED_STRATEGIST_PLAYER_DAILY_TOKENS` (steady pace 50,000) and `KINDRED_STRATEGIST_DAILY_TOKENS` (steady pace 100,000). Nonstop chat with one player spends the player's steady budget in about six to eight minutes; after that the fixed replies carry on, and the log's one "budget is spent" line says so. Raise the two budgets for a chattier server, or set `KINDRED_VOICE=off`. The log says `Kindred voice on` at start and, for each reply, `<Echo> Chopping logs for a table. (to Steve, 1,180 in / 22 out, 1.4 s)`, or `Echo voice: no line (<why>); the fixed reply instead (...)`. The last six lines between a player and each speaker go with the next request for five minutes, in memory only, and the count of replies is kept in the player's model-context file as `conversations`.

#### Asks in chat (E-20b)
Whoever may command a native player (its controller or an operator) can also ask it in chat to come over ("Echo, come here"), wait a moment, stop, or toss them some of an item it carries ("Echo, toss me some bread"). The model's reply carries the ask. The player says its reply, then does what the matching command does, through the same code and with the speaker's own authority, so every check the command makes is made again:

| Ask | As the command |
| --- | --- |
| come | `/aiplayer native goto <where you stand>` |
| wait | `/aiplayer native wait 4` |
| stop | `/aiplayer native stop` |
| toss | `/aiplayer native toss <item> 8` (new: up to that many from one stack, to whoever runs it) |

If the command would refuse, the player says why in the command's own words ("Echo has no diamond."). Nobody else is offered asks; a reply that carries one for anyone else is refused, and the fixed reply is said. An opponent in a match never may, operator or not. There is no follow in chat yet. The log says `<name> asked in chat by <who>: <ask>: done` or `refused (<why>)`.

#### Play by asking (E-52)
Beyond E-20b's four, a player does what it is asked in chat in plain words, with or without a model, exactly as the matching command does it when the person asking types it: the same method runs, with that person's own authority, so every check the command makes is made again. It says how it went: done in a few words ("On it: build a nether portal."), or the command's refusal as its own ("Only an operator or whoever looks after me can ask me that.", "AI world edits are disabled on this server; an operator must enable them first."). Nothing moves on an ask the command would refuse.

| Say | As the command | Who may |
| --- | --- | --- |
| go for the Nether · find the End portal · beat the dragon · drop your goal | `/aiplayer goal nether\|end\|dragon\|none` | its controllers |
| play it safe · take it easy · speed it up | `/aiplayer mode OPTIMAL\|IDLE\|SPEEDRUN` | its controllers |
| work together · go solo | the panel's collaboration switch | its controllers |
| build a hut\|cabin\|house\|manor here · make a cow\|sheep\|pig\|chicken ranch here · plant a field here | `/aiplayer build`, `ranch`, `field` at the speaker's feet | its controllers |
| wear the knight skin | `/aiplayer skin knight` | its controllers |
| race me · duel me (or name someone) | `/aiplayer match speedrun` / `duel` | anyone; see below |
| I'm in · count me out | `/aiplayer match join` / `stop` | the person, for themselves |
| keep it down · you can talk more | `/aiplayer prefs unasked fewer\|more` | the person, for themselves |
| come here · wait · stop · toss me some bread | E-20b's asks | its controllers |

A race or a duel asked in chat is proposed only if every AI player in it says yes by its own rule: not while hurt or hungry, nor out of the Overworld or already in a match, nor a duel against someone far better armed unless it is the fighting kind; otherwise as keen as its personality makes it. Its yes stands in for its controller's for that match alone. Each person named still joins only by their own "I'm in", and the match starts once everyone is in. A race or duel with nobody named is asked back once ("Race whom?"); an answer that names nobody drops it.

What stays a command: making and removing players, the operator's switches (`edits`, `dragonfight`, `bases`), limits, and anything with coordinates. Asked for one in chat, a player says which command does it ("That's not mine to change; an operator can, with /aiplayer dragonfight on."), to an operator as well. With the voice on, E-20b's four asks are still the model's to read; the rest are read locally either way, so asking in an unusual way may not be understood. The log says `<name> asked in chat by <who>: <ask>: done (<the command's message>)` or `refused (<why>)`, and for a match `<name> on a RACE with 1: yes|no (<its words>)`.

#### Speaking first (E-53)
A player speaks first, unasked, on these occasions, the most pressing first:

| Occasion | What it says | Its own limit |
| --- | --- | --- |
| A monster near a person and behind them, that the player can see | "Behind you, Steve: a creeper!" | at once, then 30 seconds for that person |
| A person near it is hungry (food 10 of 20 or less) and it has food to spare (more than the 4 it keeps) | "Need food? I've got bread." | once an hour for each person |
| Someone told it they need something, and it has some spare (E-21a) | "You said you need iron? I've got 4 iron ingot spare. Want some?" | once an hour for each thing and person |
| It needs something (its follow-up queue's gathering, as it tells its team) | "Anyone got flint?" | once an hour for each thing |
| Idle together with someone for 20 seconds | a word in its manner ("Quiet out here.") | the budget alone |

An offer waits a minute for an answer. Only "yes please" (or yes, sure, okay) hands anything over, through the toss method: up to four of food, the offered count of anything else, as a recorded toss, within E-49's sixteen a day to one person; the player's own offer stands in for control for that toss alone. "No thanks" withdraws it. Nothing moves before the yes.

Every line but a warning is held to each person's *attention budget*: one unasked line in three minutes, as that person's `/aiplayer prefs unasked fewer|more` stretches or shortens it (six or one and a half minutes). Someone who answers within a minute earns a little more (down to half the wait); someone who never answers gets a little less (up to half as long again); both drift back to the usual over a day of real time. Nothing unasked, warnings included, goes to a person out of earshot, in a match's countdown, or away (not moved for five minutes); nothing but a warning goes to someone fighting (they struck or were struck in the last ten seconds) or building fast (two or more blocks from their hand in a second). A player whose remarks are off (`/aiplayer social ambient false`) says nothing first at all. Greetings and the remarks from before (rain, nightfall, a passing word, work done) keep their own limits. The log says `<name> to <who> unasked (<occasion>): <line>` and `<name>'s offer of <count> <item> to <who>: taken|refused (<why>)`.

#### Conversations between AI players (E-51)
Two AI players who meet again after ten minutes or more apart talk: two or three lines, in each one's manner. The first asks after what its ledger last said the other was doing or needing; the other answers from its own record (what it is doing, and what it needs), never by reading the first one's words; the first says a last word (it has some spare, or wishes them luck). A playful one may open with something they went through together, a tease that goes out plainly to anyone in earshot who asked for no teasing. Nothing is talked of twice within half an hour.

```text
KINDRED_CHATTER=4      optional; lines between AI players a minute, server-wide (default 4); quiet for none
```

The lines go only to people who can hear the one speaking (within eight blocks and in sight, and whoever may command it or, with public chat on, anyone), never to the whole server, and never to another AI player as chat. With nobody to hear, both still note the meeting in their ledgers ("talked: what you were doing") and nothing is said. A pair talks at most once in two minutes, and not while either is fighting, backing off, falling, asleep or in a match's countdown; a fight stops a talk under way. No model is asked: the turns are local, so they cost no tokens. They replace the old fixed peer conference, which whispered one line to anyone within six blocks. The log says `<speaker> to <other> (<n> hearing): <line>`, or that they met with nobody to hear, or that the chatter was spent.

#### How it sounds, and how each person asked to be spoken to (E-18a, E-19a, E-18b)
Each player has an *expression preset*, set by whoever may command it, and the server's operator sets the most expressive any player may be:

| Preset | How it sounds |
| --- | --- |
| `plain` | Says things plainly, without jokes. |
| `dry` | Short and understated ("Rain. Lovely."), without jokes. |
| `playful` | A light joke or a callback when it fits, and a little teasing of other AI players. A person is never teased. |
| `rough` | Like a mate on a survival server: swears ("Rain again? Damn it."), makes dark jokes about dying in the game ("That zombie almost sent me back to spawn lol."), and teases other AI players harder. |

```text
/aiplayer expression [<player>]                             how it sounds and types, and why
/aiplayer expression plain|dry|playful|rough|auto [<player>] set it; auto goes back to sounding by its personality
/aiplayer chatstyle [<player>]                              how it types
/aiplayer chatstyle proper|casual|gamer|chaotic|auto [<player>] set it; auto goes back to its personality
KINDRED_EXPRESSION_CAP=plain|dry|playful|rough              optional; the most any player may be (default rough)
KINDRED_ROUGH_AUDIENCE=on                                   optional; people who said nothing hear swearing and dark jokes (default off)
```

A player with no preset sounds by its personality: playful from humour 65 up, plain below, as before presets, and rough when it is playful and combative from 80 up (Sol, of the starters). A sociable one with some humour (sociability 75 and humour 55 up: Kindred) also flirts, with other AI players and with a person who said yes to it, never sexually.

**Who hears the rough stuff.** A preset never opts anyone in. A rough player's swear words reach only people who said yes to swearing (`/aiplayer prefs swearing on`); everyone else hears the same line softened ("Dang it.", "shoot", "heck"). Dark jokes reach only people who said yes to them, and flirting only people who said yes to it; everyone else hears the plain line. A line heard by several people follows the strictest of them. The operator can say yes to swearing and dark jokes for everyone who has said nothing (`KINDRED_ROUGH_AUDIENCE=on`, for a server of adults); a person's own `off` still holds, and flirting is never the server's to say. Some words are never said whatever anyone asked for: slurs, anything sexual, insults or threats to a person, jokes about real-world harm. Every line, from every lane and from the voice (E-20a), passes the same last check as it is delivered, so a change to someone's settings holds from the next line, even one already waiting.

**How each player types.** Each player has a *chat style*, from its personality unless its controller sets one: the looser its humour and sociability and the less its caution, the more it types like a player in a hurry. It changes how a line looks, never what it says: names, numbers, coordinates, commands and item ids come through as they were.

| Style | How it types | Starter |
| --- | --- | --- |
| `proper` | Capitals, full stops, whole words. | Echo, and an even personality |
| `casual` | Lower case, no full stop at the end, the common shorthand (`u`, `ur`, `rn`, `idk`, `tbh`, `wanna`). | Kindred |
| `gamer` | Lower case, no punctuation, more shorthand (`r`, `w`, `smth`), and now and then its own word (`ngl`, `fr`, `lol`, `xd`, `lmao`, `bruh`). | Sol |
| `chaotic` | As a gamer, its own word more often, and a pleased last word stretched ("niceee"). | |

The voice is told the style too, so a model's line already reads that way; the last check types it all the same.

The preset and the chat style are saved with the player's profile, so they survive a restart. The cap bounds every preset to at most its own rank (under `dry`, a playful or rough player sounds dry); a value it does not know is taken as `plain`, and the log says so. Playful now has more lines than before (a hello, nightfall, and the tease of another AI player, beside the rain, a passing word and work done). The cap bounds every preset to at most its own rank (under `dry`, a playful player sounds dry); a value it does not know is taken as `plain`, and the log says so. The preset is saved with the player's profile, so it survives a restart.

Each person sets, for themselves only, how AI players speak to them; nobody else can set or see it, and it gives nobody control of a player:

```text
/aiplayer prefs                           what you asked for
/aiplayer prefs unasked fewer|usual|more  the pause before you are spoken to unasked again: 60, 30 or 15 seconds
/aiplayer prefs teasing on|off            off: no teasing between AI players while you can hear it
/aiplayer prefs jokes on|off              off: no jokes to you or near you; the plain line instead
/aiplayer prefs swearing on|off|default   on: a rough player's swear words reach you; default: as the server says (off unless KINDRED_ROUGH_AUDIENCE=on)
/aiplayer prefs dark on|off|default       on: dark jokes about dying in the game reach you; default as above
/aiplayer prefs flirting on|off           on: a flirty player may flirt with you, and you hear AI players flirt; off by default
/aiplayer prefs forget                    delete what you set
```

The pause is per person, across all players; each player's own pause between remarks (about two minutes) and the server's ten seconds between any two still apply, so `more` shortens only the per-person part. A line said to several people at once is put as the strictest of them asked: one person with jokes off, and everyone there hears the plain line. A reply to one person follows that person's own preferences, and the voice (E-20a) is told in one line how to speak to them. Jokes off also means no dark jokes and no flirting. Preferences are kept per person in the metadata database (`listener_preferences`) and survive a restart; going back to all the defaults deletes the row, as `forget` does. Until the saved preferences have been read at start, players say nothing unasked and make no jokes; if they cannot be read, that holds until the next restart, and the log says so.

#### What each player knows of the others (E-50)
Each AI player keeps a ledger of the people and players it has met in this world, filled from three sources, the most trusted first:

1. **What the game recorded.** Who it has seen and when (once a second, within 16 blocks and in sight); a person's place only while they are in sight; a blow, harm after coming where someone asked, a chest emptied (E-49); what it handed someone and what they handed it.
2. **Its teammates' own updates**, sent through the team every five seconds, never in chat: what each is doing (its goal and where it has got to, or its task), what its follow-up queue still has to gather (the End fight's string and feathers, say), what it holds of its finds unpromised, and where it is. Teammates are AI players out of any match, as in co-op; in a match they send nothing.
3. **What a person says of themselves**: "I'm off to the Nether", "I need iron", "I've got spare wheat". Each is kept only as a claim: it holds for a game day, is never told as a fact ("Steve said, 2 minutes ago, that they were going to the nether (said, not seen)"), and is retired once the player sees them there. Nothing else said in chat leaves a note, and what was said is never written to disk.

Whoever said something can take it back: "forget that", "forget what I said about the Nether", or "that's wrong". The claim is marked with who took it back and when, keeping who said it, and is never used again; the player answers in its own words ("All right, I've forgotten it."), without the model. Only the person who said it can take it back.

Asked "what does Sol need?", "what is Sol doing?" or "where is Sol?" about someone it knows, the player answers from its ledger, only as far as the asker may be told: a place to whoever may command it; a teammate's needs and doing to its controllers and teammates; what someone said only back to them; nothing of others to an opponent. With its voice on (E-20a), the voice phrases the answer, told the same lines under the same rules and costed as any reply (E-50a); when the voice gives no line (its budget spent, a refused or failed reply), the player says the ledger's own words, as it does with no voice at all. An unknown name is ordinary talk.

The ledger holds 64 peers (the least lately met dropped) and 20 episodes each, and is kept in `kindred/memory/<uuid>.peers.json` beside the atlas, saved every minute and at a stop; people's places and claims are not kept. To remove everything a native player knows of someone: `/aiplayer memory forget <their uuid> <player>` (operators and the player's controllers).

#### Gifts and friendship (E-21a, E-22a, E-24a)
**Gifts.** Tell an AI player you need something ("Echo, I need iron") and, if it has some spare, it offers you some on its next turn (the table above). What is spare is half of what it holds that nobody has a claim on, at most 16. It never offers:
- its tools, weapons or armour;
- buckets, flint and steel, ender pearls or eyes, totems, elytra or beds;
- food (that is the food offer's);
- anything while a goal of its own runs;
- what its team's share-out holds for someone else.

It never offers to someone it is wary of (E-49).

**Where a toss lands.** A toss lands just past your feet, whichever way the thrower is facing, and never within the thrower's own reach, where it would pick it straight back up (E-22b). Deliveries and hand-overs between AI players use the same toss.

**When a toss counts.** Anything it tosses you through the toss method (your ask in chat, a command, an offer's yes) counts as given only once you pick it up. The game's own count of what you picked up decides. Then the gift goes into its ledger ("gave you 4 iron ingot") and its friendship with you. If nobody picks it up within ninety seconds, it notes that the toss was not taken. It does not count it and does not toss again: a dropped stack is not a gift received, and a second toss could be a copy. The log says `<giver>'s gift of <n> <item> was picked up by <who>`, or `<giver>'s toss of <n> <item> was not picked up by <who>: noted, nothing tossed again`.

**Friendship.** Each AI player keeps a friendship with each person and player it knows. It is worked out only from what happened between them in the game:

| What happened | Friendship |
| --- | --- |
| A gift it gave that was picked up | +3 |
| A gift it was given | +6 |
| An outing together | +5 |
| A blow from them outside a match both joined | −12 |

Each thing counts once. Nothing said in chat counts. Neither does:
- a declined gift or a toss left lying;
- a chest found short;
- a fall after coming where someone asked;
- an ask.

The sum gives a band: friend (30 or more), friendly (8 or more), neutral, cool (below 0) and wary (−24 or less). For now friendship changes nothing the player does. It never changes who may control it, what it may do or its budgets, and it is never a reason to strike.

**Looking and correcting.** Operators and a player's controllers can look and correct:
- `/aiplayer rapport [player]` lists its standings.
- `/aiplayer rapport <player> <peer>` lists the things with that peer, numbered.
- `/aiplayer rapport <player> <peer> strike <n>` strikes one out as wrong. It keeps who struck it and when, and the friendship is worked out again at once.

The record is kept in `kindred/memory/<uuid>.rapport.json` beside the ledger: 64 peers, the 50 newest things each. `/aiplayer memory forget` removes someone from it too.

#### Outings (E-27a)
An AI player with nothing of its own to do can ask people near to come and see somewhere it has seen.
- **When it asks:** no goal, ranch, field, build or team task, and not fighting, fleeing or in a match. Someone it may talk with has been near for twenty seconds. It is daytime, and its sociability is 40 or more.
- **Where:** a village or a lake it has itself seen (its atlas), 10 to 96 blocks off.
- **What it says:** "I found a lake not far off. Anyone want to come and see it? Say "I'm in"." At most once in half an hour of game time for each player.
- **Who goes:**
  - A person answers within the minute: "I'm in", "count me in", "I'll come" or "let's go".
  - Another AI player in sight answers by its own rule: yes only with nothing of its own on, out of danger and of any match, and sociable enough. It never goes because it was told to.
- **The trip:**
  - With a yes the host says "Off we go." and walks there: "Here we are: a lake.".
  - It stays a minute, says "Right, heading back." and walks back. An AI player that came walks back to where it set off.
  - Each walk is that player's own, chosen in its decision loop below its reflexes. A walk edits nothing, so it needs no leave for AI world edits.
- **What it remembers:** who was within 16 blocks when it arrived, not who said they would come. Each of them gets an episode ("went to a lake together") and +5 friendship, on both sides for an AI player.
- **Ending early:**
  - A fight, nightfall or ten game minutes in all ends it: "Let's head back.", or "Never mind, another time." if it had not set off.
  - Nobody's yes in the minute drops it.
  - Saying no costs nothing.

The log says `Outing: <host> invited <n> to <place>`, then `... set off ...`, `... got to ...; there with it: <n> of <m>`, `... is back ...`, `... cut short: <why>` or `... dropped: nobody wanted to come`.

Roster skins come from the bundled textures. To show the same skins to clients without the mod, put signed Mojang texture properties in `config/kindred_skins.properties` as `<key>.value` and `<key>.signature` (keys `kindred`, `sol`, `vesp`; Echo's key keeps its former name, Vesp, so settings made before the rename still apply).

## How the players look about

While a player waits (resting, by its furnace, for daylight), it looks at what matters, as a person does: a monster in plain sight, whoever speaks to it, a player close by who looks at it (for a moment), what it waits on, and otherwise about it, slowly. It never looks an enderman in the eyes. Say its name in chat while it can see you and it stops what it is doing for a moment, between one task and the next, to look at you. Mining, placing and fighting still turn its head in one step.

## Valuables on the way

A player on its goal never walks past valuables. It weighs what it sees by what it is worth to it as it stands: kit better than its own it takes and puts on; what its run is short of it carries; what it has no use for yet but will (gold before the Nether, an enchanted book, a saddle, rails) it takes and keeps for later; what is not worth a slot it leaves. It goes out of its way by what a thing is worth, up to 64 blocks: a structure's chest or a mineshaft's chest minecart nobody has opened, a ruined portal's gold blocks, a drop nobody threw. It loots only containers nobody has opened since the world made them; a chest anyone has opened, yours or another player's, it never takes from. Looting needs the server's AI world edits on, as roaming edits do.

## Raiding in a match

In a match whose sides are opponents (a manhunt, a duel), a player that sees an opponent use a chest knows the chest as theirs, and nothing else makes a chest anyone's. It asks the model whether to raid it, leave it or watch it, telling it what it knows (its side and theirs, how far, an opponent in sight, its health, what it holds and its run wants) and nothing of what is in the chest. With no model set up, or no usable answer, its own rule decides on the same facts: never with an opponent in sight or badly hurt, and a manhunt's runner only for what is close to its way. A raid takes what is worth taking and leaves the rest, never while the opponent stands at the chest, and the player tells its own side what it took, never its opponents. Outside a running match, or against a teammate, a player never takes from another's chest. A raid needs the server's AI world edits on, as looting does; questions to the model count against the same budgets as its advice.

## Named kit

Once a player has reached the enchanting stage (an enchanting table of its own, held or in its manor), it names its best weapons and tools at an anvil, as players do, a level each: its best sword, axe, bow, pickaxe and so on, when that is diamond or netherite or enchanted and has no name yet. The name is the model's when one is set up and the name keeps the rules (2 to 32 letters, spaces, apostrophes and hyphens, a capital first, none it has used), else its own, drawn from what the piece is and in keeping with its temper: "Scorchbane" for a sword with Fire Aspect, "Windbore" for a pickaxe with Efficiency, a title such as "Fang of the Embers" from a curious player, now and then a joke from a playful one. The anvil is its own: one it holds, or one it makes of iron it can spare. It never fetches iron for a name, never names anything during a match, and never renames a piece that already has a name, yours included. It says what it named.

## Enchanting, brewing and netherite

Out of any match, between its work, a player improves its kit as players do, with what it has earned and at workstations of its own. With thirty levels and three lapis it goes to its enchanting table (one in a base it built, such as the manor's enchanting room, or one it holds, put down beside it) and enchants its best unenchanted piece, its sword first, then its bow and armour, taking the best of the table's offers it can pay for. With nether wart and a potion's ingredient it brews: bottles filled at water close by, its brewing stand loaded and fuelled with blaze powder, the brew waited out nearby; fire resistance first, then strength, slow falling and healing, three of each. With ancient debris, gold, an upgrade template and a smithing table it smelts the debris, makes the ingot and upgrades its diamond pieces, sword and pickaxe first, keeping their enchantments and names. It never takes these makings from you; using a table, a stand or a furnace is a workstation's use under the server's edit policy, and it says what it enchanted, brewed or upgraded.

## Fishing

A player out of food with no animal in sight fishes, as players do, at still open water within twelve blocks: with its rod, or one it makes of two string and three sticks at a crafting table. It casts, watches the bobber, reels in when a fish bites and keeps what it catches; a cast that lands off the water it reels back and casts again. On its run it fishes before walking out to look for animals, and only while it has no food; stocking up with food in hand, it hunts. Fishing at a spot you name, as a chore, is not there yet.

## Chores in a work area

A player given a work area or a patch (the native setup commands) does the area's chores as players do, ahead of its own progression, and says when each is done:

- **Lighting.** Every floor of its work area within sixteen blocks of it where no block light reaches, where monsters spawn, gets a torch on the floor, the torches made of its coal or charcoal and sticks; "I lit the work area: 5 torches."
- **A patch mined out.** A patch of ore or stone is mined, every block open to the air, with a pickaxe it holds that harvests it, and what drops picked up; with none that can, it says so and mines nothing. A patch of logs is cut with an axe, and once each trunk is down a sapling of its wood it holds is planted where it stood.
- **Bringing supplies.** `/aiplayer bring <count> <item> [target]` asks it to bring you that many: what it holds, and the rest from its assigned storage, tossed across to you from close by, exactly as many and as they are; `0` calls it off. It says when it is short.
- **An auto-smelter.** `/aiplayer build smelter <x y z>` has it build a furnace fed by hoppers: ore into the chest on top, fuel into the chest beside, and what it smelts comes out in the chest in front. It makes the hoppers and chests of its own iron and planks.

## Storage and what goes missing

A player puts what it keeps for later (enchanted books, saddles, name tags, rails; with no goal on, the diamonds and gold it has no use for yet) into a store of its own: the storage you assigned it, or a chest of a base it built. A chest assigned to two players is their team's: each tells the other what it puts in and takes out, and both count it. On a goal it keeps what the run needs on it, and fetches what the run wants from its storage before going to find more. Each time it opens one of its stores it counts what is there against what it knew. If something is gone: alone, it says so; with a team, it asks first, and takes the word of whoever owns up ("me", "I took it"); with no answer in thirty seconds it reckons it gone. It names only someone it saw at the chest, only to its own side, and never blames or retaliates against anyone else. Storing needs the server's AI world edits on.

## Hit hard by another player

A single hit from another player, AI or human, that takes more than three hearts after armour is lethal aggression to an AI player, however the fight began. It drops what it was doing and weighs the fight from what it can see and knows: what it deals them a second through the armour it sees on them, what they deal it (the weapon in their hand, the hardest hit they have landed on it), the health of each (its own; theirs as it believes it) and what each can heal (its golden apples). An opponent's enchantments it cannot read are allowed for at their likely strongest. If the fight is winnable, it may strike that player at all (a match you both joined, such as a duel or a manhunt across sides), and it is equipped (a weapon better than its fist), it fights back; an even fight it takes only when it has no way away. Otherwise it sprints away. In a duel both joined it is weighed by the kit on each side alone, and a fight even by kit is fought on (see the next section). It never strikes back outside a match it joined, whatever its kit, and once away, or the attacker gone, it does not seek them out. It says who hit it, how hard, and why it chose what it did: "Rue hit me hard, 4 hearts. I'm getting away." A lesser hit changes nothing.

## Duels: how an AI player fights another player

In a duel both joined (`/aiplayer match duel <first> <second>`), an AI player fights by a melee of its own, never by a model: it closes in, and leads a moving opponent a little (where it will be, from how it was seen to move, at most half a second ahead). It winds up a critical from half a swing so that the blow lands full on the way down. Between its swings it strafes round the opponent; with no shield, it steps out of reach as the opponent's swing is about to come. With a shield, it raises it the moment the opponent's swing is about to come (a shield guards only after a quarter of a second up), takes the swing on it, and strikes back while the opponent recovers; a strike already under way, a jump for a critical, is finished first. Getting low against a stronger opponent, it gets away; well out of reach and hurt, it eats. It learns what its opponent tends to do, counted over the chances it had: whether it raises its shield while its own swing recovers, and whether it swings before its swing has recovered. Against one that keeps its shield up, it takes up an axe, which disables a raised shield. In a duel a hit of more than three hearts is the fight going on: it is judged by the kit on each side, not by a critical landed, and only one outclassed by kit gets away, and only where it can walk away. A shield or armour worn through in a fight is wear, not a loss.

Three switches, each an operator's (they reset on a restart):
- `/aiplayer combat advice on|off`: with a strategist set up, it is asked for a tactic at the start of a fight and when the fight changes: press, counter, hit and run, or get away. Only the tactics that make sense for the fight are offered. The answer is checked again when it comes back, and dropped once it no longer makes sense. It only leans the melee for ten seconds; the melee fights on meanwhile, and without any answer. Off by default; it costs tokens.
- `/aiplayer combat model on|off`: a learned melee in place of the hand-set one. The model is `kindred/combat/melee.model` under the server's directory, trained offline (below). A damaged model, or one trained for another ruleset, is refused; off is the hand-set melee again.
- `/aiplayer combat record on|off`, run by a human player for themselves: their duels are recorded as demonstrations, what they saw and the move they made each tick, under `kindred/demonstrations/<player>/` on the server. Nothing is recorded of anyone who has not turned it on.

To train a model from the demonstrations: `gradlew :companion-testkit:trainMelee --args="<server>/kindred/demonstrations <server>/kindred/combat/melee.model"`. It prints the model's version and how many of the held-out moves it makes.

## Pets

With pets on (`/aiplayer pets on`, an operator's choice, off by default and reset on a restart), an AI player with bones of its own tames a wild wolf it comes across, a bone at a time until it takes. It keeps at most two pets and one wolf, and never another player's animal. With a name tag and a level to spare, it puts its anvil down, letters the tag with a name of its own, and names its wolf: "My wolf is called Molly now." When its pet dies, it says so, with what the game says killed it.

## Wary of someone (E-49)

An AI player holds back from whoever the game saw do it harm. Each player keeps its own wariness of each person, from
0 to 100:

- a blow or an arrow from them, outside a match where the two may strike each other: +40;
- harm within ten seconds of arriving where they asked it to come: +30;
- something gone from a chest of its own while they, not on its side, were seen at it: +30;
- an ask for a toss past the day's cap: +10;
- something they threw it, picked up (a gift, or what was taken given back): −20;
- each game day with none of the above: −10.

At 50 or more it is wary of them, so two blows are enough. While wary, it refuses their asks in chat, all but a stop,
in its own words: "Not after last time." It also tosses them nothing. With the voice on, the voice is told: "This
person has hurt you lately". It is never told to answer in kind.

Two rules hold for everyone, wary or not:

- **Tosses.** It hands one person at most 16 items a game day, owner or not, by chat or by `/aiplayer native toss`.
  Past that it says "That's all I hand out in a day."
- **Coming when asked.** A come-to-them never ends beside lava, fire or a drop of more than three blocks. If the only
  way to the person ends there, it stops short where it is safe.

For a minute after someone strikes it, it walks off if they come within four blocks. Wariness never makes it strike
anyone: it withholds, and that is all. A hard hit is answered as before ("Hit hard by another player"). Wariness is
kept for the session and starts again when the server restarts. The log says
`<name> notes that <person> hurt it: wariness 80; it holds back from them now`.

## Learning from the server's people (E-47)

Each person decides for themselves whether the AI players may learn from them:

- `/aiplayer watch on` lets them;
- `/aiplayer watch off` stops it, and deletes what was learned from them at once, in the server's file too;
- `/aiplayer watch` says how it stands.

No one can decide it for anyone else. The console, a command block, an AI player and a command run as someone else
(`/execute as`, by an operator too) are refused. Until a person says yes, nothing is learned from them.

**The privacy rule (D10).** What is learned, and from whom:

- only from people who opted in, each for themselves;
- only the height of the coal, iron and diamond ore they break in the overworld, in bands of four blocks;
- never where (no x or z), when, or in what order;
- never their chat, their inventory, or anything else they do;
- never the AI players' own mining.

It is kept as counts in `<world>/kindred/customs.json`, with the list of who opted in. Each person's counts are kept
apart from the others' only so that opting out can delete exactly theirs. Deleting the file deletes everything, and no
one is opted in after that.

**What the players do with it.** Once the people who opted in have broken 16 of an ore between them, the middle of
their heights is the server's custom for it. An AI player's goal digs for that ore toward its own depth (iron y 16,
diamonds y −58, coal y 48), moved three tenths of the way toward the custom and never more than eight blocks. Players
who mine their iron at y 12 move it to y 15. The log says
`<name> mines iron toward y 15, leaning toward the depth this server's people mine it at`. Nothing else changes: it
copies no build and follows no one.

## Trading with villagers

A player that comes by a villager at work (not a nitwit, a child, one asleep or one trading with someone) opens its trading screen to see what it offers, as you would, and remembers it. It knows what each profession usually buys and sells, and weighs each offer by what it is worth to it as it stands, against what an emerald costs it to earn: the cheapest of the villager's trades it can feed with what it can spare (wheat from its field, paper from its sugar cane, sticks from its trees). It buys what is worth more to it than it costs (a Mending book, diamond kit better than its own, ender pearls its run is short of), selling what it can spare for the emeralds, and never what its run needs; it buys only what it can pay for, and says what it traded: "I sold 48 paper for 2 emeralds and bought a Mending book for 2 emeralds and a book." What is worth buying but beyond it, or sold out until the villager restocks, it tells you of once and comes back for. A villager with nothing worth trading it lets be until it has restocked. It never trades during a match. Holding a lectern, by a villager with no job it makes a librarian of its own, as players do: it puts the lectern down, looks at the librarian's offers without trading (a trade would lock them), and if nothing is worth keeping breaks the lectern and puts it down again for new ones, until a librarian is worth keeping or it gives up and takes its lectern back, and says which. A trading hall (`/aiplayer build tradinghall-<cells>`) is a row of cells along a corridor, one villager to a cell: its bed along the cell, the villager's floor before it, and its job block across the cell's front at the villager's feet, under a lintel, which shuts it in; you trade with it across the open block above the job block, from the corridor. The corridor is walled, lit and shut by a door against zombies, and open to the sky. With bases on (`/aiplayer bases on`), a player whose base has a stage standing and that keeps a villager worth a cell (a librarian it rerolled and kept, or a villager it has looked at whose offers are worth keeping to it) builds a trading hall of three cells of its own accord, on the nearest flat open ground to its base and clear of it, in its base's wood, before its next stage: "A villager worth keeping, and a cell to keep it in: a trading hall of 3 cells." It is kept in the player's record of its bases (`kindred/memory/<player>.bases.json`, as `hall`) and taken up again if begun. Once it stands it is kept whole as a base is: its own blocks gone or out of place (a wall blown out, a lintel, a roof plank) are put back, and nothing else; a cell's front shut with its villager's job block is left as it is, and nothing is repaired while it is bringing a villager home. You can also order one with the command above. Its beds come through its needs as any short block does: it shears sheep of the bed's colour with shears it holds (making a pair from two iron it can spare), picks up their wool and makes the beds at a crafting table with three planks each; with no shears it hunts sheep instead, one wool a sheep, never those of the kind its ranch pens or a named one. A villager it keeps, within 48 blocks of its hall and not yet in a cell, it brings home by minecart once it holds what that takes (the rails the line needs, a minecart, the villager's job block and a plain block such as dirt, one more for each block of a bridge over water): it takes the hall's door off, lays a line from behind the villager into a free cell, pushes its cart into the villager and on into the cell, shuts the cell with the villager's job block, takes up its rails (the one on the cell's floor it leaves, as players do) and puts the door back, and says so: "I've brought the librarian home: it's in its cell in my trading hall." Short of rails or a minecart, it makes them from iron it can spare at a crafting table near it; short of the iron, it goes and gets it as its run does, mining iron ore, finding fuel, putting a furnace down and smelting it. Across water the line runs over a bridge of plain blocks it puts down from the bank it is on and takes back after its rails. A villager that steps off the line before the cart comes it follows, laying the line again from where it now stands. A villager that strolls (its AI on) it holds first, if it has a boat and a pickaxe for its hall's stone: it puts the boat down just beside the villager, which the boat takes up, lays the line from where it sits, takes out the wall over the hall's door and the cell's lintel (a villager in a boat in a cart rides a block higher, and would put its eyes into them) and pushes the cart into the boat, which it takes up with the villager in it. In the cell it puts the lintel back and, so that the villager is never loose while the cell's front is open, leaves the cart in the cell: it takes up the front's rail, puts the job block across the front, breaks the boat (its item falls in the cell, out of reach, and stays there) and puts the wall back with the door; the line it lays for a boat needs three blocks open over it. Across water it cannot bridge with what it holds, it rows such a villager over in its boat, as players do: it gets in with it, rows across to a bank no more than a block over the water within 64 blocks, gets out and lays the line along the bank past the boat, and the cart takes the boat up from the water as it rolls by. It builds nothing on the water. It brings only a villager that has traded (one moved before it has would let its job go and its offers with it). Given up part way, it takes back what it put down first. It never breaks a village's own lectern; this needs the server's AI world edits on.

## Development native bodies
Explicit development native bodies are available:
```text
/aiplayer native create Ada
/aiplayer native create Ben
/aiplayer native create Cy
/aiplayer native list
/aiplayer native status|stop|resume|connect|disconnect <UUID>
/aiplayer native wait <1..4 seconds> <UUID>
/aiplayer native eat <0..8 hotbar slot> <UUID>
/aiplayer native walk <north|south|east|west> <1..3 blocks> <UUID>
/aiplayer native equip <0..35 inventory slot> <UUID>
/aiplayer native patch <from x y z> <to x y z> <resource block ID> <UUID>
/aiplayer native mine <target x y z> <0..8 hotbar slot> <UUID>
```
Only operators create them, on solid ground in an already loaded controlled area. Controllers/operators may use lifecycle controls. The cap is three, including pending/incomplete records. New bodies are established after verifying a native player-file receipt. Disconnect/shutdown preserve native items and stop. Restart leaves them offline/stopped; connect explicitly while the saved area is loaded, then resume explicitly. Resume enables no autonomous work yet.

Wait/eat are explicit bounded development tasks. A request reserves at most five seconds through the existing activity writer before acquiring a lease. Eating uses one actual owned hotbar food with native timing, requires hunger, rejects chorus fruit and never retries an uncertain outcome. Stop clears use immediately. A failed/expired allowance starts no work; status distinguishes charged and observed milliseconds. Settlement must finish before another request; shutdown settles accounting without additional game ticks. A skill that runs past its slice (a long walk, obsidian with a diamond pickaxe, stone by hand) has the slice renewed shortly before it lapses, through the same writer and within the same cap and window; an exhausted allowance or window still stops it (F-11c). Walking is limited to one short flat loaded corridor, checked again during movement; unsafe or changed routes block. No jumping, digging or general pathfinding.

**Activity limits (F-11e to F-11g).** Each AI player has a daily allowance of paid activity, kept per accounting period: a local day in its timezone. `/aiplayer limit status [name]` shows the cap and timezone. Operators set them with `/aiplayer limit cap <hours> [name]` and `/aiplayer limit timezone <zone> [name]`, where the zone is an IANA name such as `Europe/London` or `UTC`. These reach native players under the same access as their lifecycle controls. A timezone edit never resets time already spent. The current period runs to its end as it was kept, and the new zone starts at its first local day that begins at or after that end. That can leave a gap of up to a day with no allowance (UTC to New York, four hours; UTC to Tokyo, fifteen); the command says when the first period in the new zone begins. If the server clock is behind the last period (for example, stepped back over midnight), no allowance is charged until it catches up. A request then gets "No activity allowance until …" and the player is not stopped. **Activity hours (F-11f).** `/aiplayer limit window <start> <end> [name]` sets the local hours a player may be active in. Write the times as `18.00`, `18` or a quoted `"18:00"`; an end before the start runs overnight. `/aiplayer limit window off [name]` clears them. Outside its hours, a request gets "Outside its activity hours (…); they open …" and waits. A skill running when the hours end stops there, and so does one whose hours are narrowed while it runs. **Over midnight (F-11g).** A walk, traverse, retreat, goto or wait running at its period's end holds still for a moment, then carries on, charged to the new day. A recorded action (break, mine, place, craft …) stops at the period's end and is chosen again in the new one, since stalling it mid-action could leave its record uncertain. `limit offline` remains a legacy-body control.

Equip moves one owned armor/shield into an empty native equipment slot using ordinary inventory-handler rules. Slots 0..8 are the hotbar; 9..35 are main inventory. Occupied gear/cursor, invalid or changed stacks reject. Durable intent acknowledgements precede the transfer; exact items/components/counts and durable outcome verification precede reported completion. Pending action IO is flushed before store shutdown. Unknown outcomes block the native record without replay; preserve its actual native save for reconciliation. No borrowing, gear replacement or automatic equipment selection.

A resource-work patch is explicit bounded task scope: at most nine loaded chunks and 4096 blocks, in the native player dimension and world bounds. No home or owner-presence requirement. Changing it stops current/pending work immediately; after the save acknowledgement, resume explicitly. Status shows its bounds/resource. Configuration does not install mining or grant server edit permission.

Mine executes a single scoped log break using owned hotbar contents, native survival timing, and tool wear. It requires explicit server edit policy opt-in, active paid activity, an unexpired resource claim, and a matching work patch. A matching native break receipt completes the action; drop collection is not claimed. Stop cancels digging immediately; conflicting world changes block for reconciliation without replay.

A block is placed only against one still there to click: if that block was dug out meanwhile, the placement is refused and the player goes on, where before it was stopped for reconciliation (F-25c4u). A placement that still cannot be verified records which slots changed and what is at the target.

Missing, foreign, wrong-version or unverified state blocks for reconciliation; it is never replaced with an empty inventory. A saved dead body (a crash during the one-second respawn window) also blocks. Blocked/incomplete records keep their slot until recovery/removal is implemented. Autonomous work beyond the Nether-portal goal above remains disabled, and the goal itself is not qualified for unattended worlds. Default /aiplayer create still uses the legacy body; no saves are converted. Full client and restart recovery qualification remains open.

Legacy owner/mode/scope fields preserve access and saves; they are not the new product interface. Native creation has its development cap; team management and legacy migration remain unfinished. Combat/duels reject until qualified native skills use the arbiter. Timezone changes reject until safe accounting transitions exist; spent activity is not reset.

Inference is optional and currently disconnected. Planned Claude/OpenAI keys/budgets belong in private operator configuration, never memory/logs/source. No Jev.

Report exact build, seed/difficulty, IDs, commands and native before/after evidence. Stop and snapshot ambiguous actions; do not recreate missing items.

## Scoring a build (F-26)

A build can be played unattended on many seeds and scored, so that a change is judged on ten games, not one. Set
`KINDRED_EVAL` in a server's environment and the mod plays that scenario with no one at the console: once the server has
started it spawns the starters (Kindred, Sol and Echo, or those named) a few blocks apart at the world's spawn, each on
the nearest ground there it can be made on, sets the edits switch and their goal, sprints the ticks asked for (`/tick sprint`), writes `logs/eval-result.json` and stops the
server. The value is `key=value` pairs separated by `;`, for example
`starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on`.
Missing keys take those defaults; a key or value it does not know is refused with an error in the log, and no run is
made. Without `KINDRED_EVAL` nothing of this happens: the setting is read when the mod loads and nowhere else.

The result holds, per player and in ticks from the run's start, the first tick it reached each stage of the way (first
wooden tool, stone tools, iron ingot, iron pickaxe, diamond, obsidian, portal lit, Nether entered, blaze rods, eyes,
stronghold, End, dragon), every death with its cause as the game records it and where, when and what it was doing,
every time its watchdog gave a spot up, and the model tokens it used. A run is *earned* only when nothing in it is
handed out or kept (edits on, `keep=none`; the starters always spawn with empty inventories); otherwise it is
*staged*, and the result says which.

`tools/eval/run.py` runs a build on many seeds, two servers at a time on four cores, each in its own folder under
`run/eval/` with its own world; `tools/eval/score.py` writes a summary with medians and ranges across the seeds, and
`tools/eval/compare.py` puts two builds side by side on the same seeds, calling a difference worth noting only when the
ranges do not overlap. A run where a starter never came into the world is named in the summary and left out of its
medians. They need Python 3 and nothing else; see `tools/eval/README.md`. The optional model players'
key is passed to a server only when its scenario asks for them. Summaries go under `docs/evidence/eval/`; logs do not.

### Death replays (F-27)

With `KINDRED_REPLAYS=on` in a server's environment (on by default in an evaluation run, off in play), each time a
player dies the mod keeps a copy of the ground round it, 33 by 17 by 33 blocks with the mobs in it, and of its state:
what it held, its effects, its health, food and breath ten seconds before, its goal, stage, task, stance and habits,
the cause of its death, and its own last twenty decisions from the log. It keeps one a minute at most for each player,
in `kindred/replays/<world>/<player>-<tick>/` as `replay.snbt` and `replay.json`; `tools/eval/run.py` keeps a seed's
beside its result. Taking the copy takes less than a server tick; writing it happens off the tick. Nothing in the world
or the player changes.

A replay that is to be kept as a test goes into the gametest resources (see the conventions in
[validation](validation.md)), and the suite plays it three times by the real runtime: it passes if the player is
alive at the end. A replay that plays its death again two times of three is kept with the fix that makes it pass three
of three; one that does not is left out.

### The simulator (F-28)

`companion-sim` plays the players' decisions and plans on a made-up world, with no Minecraft in it. A hundred games
take seconds. **It tests decisions and plans, not execution.** The planner, the choice between staying alive and getting
on, the lessons and the atlas are the real ones; what each step does and how long it takes are the simulator's own
approximations of vanilla. A result from it says nothing about whether a body carries a plan out on a server: the
GameTests and the evaluation runs above say that.

```
./gradlew :companion-sim:runPacks                                     # the three packs of 100 seeds, into run/sim/
./gradlew :companion-sim:run --args="portal 100 run/sim"             # one pack
tools/eval/score.py run/sim/portal --no-metrics                       # scored as a server's runs are
```

There are three packs:

- `night`: at dusk on open ground, with stone tools;
- `caving`: in a cave, with an iron pickaxe and torches;
- `portal`: empty-handed at dawn, toward the Nether.

Each seed's `eval-result.json` and `latest.log` are written as an evaluation run's are; leave out `--no-metrics` and
`RunMetrics` reads the logs too. Each pack's `anomalies.txt` lists what the planner did that no player should:

- a step asked for twenty times running and refused each time;
- a minute of daylight with nothing it could act on;
- iron left in a furnace it walked away from.

It found that last one in the planner, and a GameTest confirmed it on a real server. See
[the evidence](evidence/F-28.md), which also records how its deaths an hour compare with a server's.

## The watch page (E-48)

A read-only web page that shows what each AI player is doing and weighing, for an operator, or for a stream. It is off
unless the server's environment sets both of the first two:

- `KINDRED_WATCH_PORT=8077`: the port it answers on;
- `KINDRED_WATCH_TOKEN=<a long random string>`: the token every request must carry;
- `KINDRED_WATCH_BIND=127.0.0.1`, optional: the address it answers on, this computer alone unless told otherwise.

With a port and no token it stays off, and the log says so. Open `http://127.0.0.1:8077/?token=<token>`: a dark card
for each player, with:

- its health and food;
- its goal and how far along it is, and what it is doing;
- what it weighed at its last decision, with each candidate's priority and score;
- what it said last, and the lines before that;
- its habits and notes;
- a feed of highlights: deaths, the stages of its run, rescues, habits taken up, matches starting and ending.

It reads once a second. The same data is served as JSON, each request with the token, as `Authorization: Bearer <token>`
or `?token=<token>`:

- `GET /api/players`, and `GET /api/players/<id or name>` for one;
- `GET /api/events`: the last fifty highlights;
- `GET /api/stream`: the players as server-sent events, once a second, four streams at most, each for ten minutes;
- `GET /api/eval`: an evaluation run's result so far (F-26), or `null`.

A request without the token, or with a wrong one, gets 401; anything but a `GET` gets 405. Nothing on the page or its
API changes anything in the game (D9): every control stays in commands and the K panel. Once a game tick the server's
own thread makes a copy of what the page shows; the page's own threads serve only that copy and never touch the world.

The page shows what whoever may command the players sees: where they are, their own log lines, and what they said,
privately too. Anyone with the token sees it. Keep the token to yourself, leave the bind on this computer, and over a
network put the page behind TLS: it is plain HTTP, and the token travels with each request.

To put it on a stream with OBS, add a **Browser** source with the URL
`http://127.0.0.1:8077/?token=<token>&transparent=1` (`transparent=1` drops the page's background), a width of 1280 and
a height of 720 or more, and leave "Shutdown source when not visible" off so it keeps reading. Each OBS scene that
shows it reads it on its own. The game's work for the page is the same however many read it: the one copy a tick.

## Offline support reports and evidence

Use the [support CLI](../tools/support-batch-01/README.md) for test summaries, before/after regressions, repeated runs, explicit sanitized evidence bundles and static documentation/manifest/JAR checks. Python and its standard library are sufficient; these commands never start Minecraft, run builds or call providers. The [reconstructed support handoff](../tools/support-batch-05/HANDOFF.md) lists the two ModelGateway fixes and the bounded verification scope.
