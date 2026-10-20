# Limited sky island (test world)

A small datapack for trying Kindred players in a world with scarce resources, F-25c5 slice (d). It is our own
creation, released with this repository; no outside map or schematic is used.

The world is a void (nothing at the bottom of the world), with one island built around `0 64 0`:

- an 11 × 11 grass and dirt island, three blocks deep, with a cobblestone wall round the edge;
- one oak tree;
- a 2 × 2 water pond and twelve lava sources sunk into the ground, apart from the tree;
- an outcrop of sixteen stone with eight iron ore showing (two buckets and a flint and steel take seven), and five
  gravel;
- three cows and two chickens.

There is no chest and nothing is handed out: a player starts with an empty inventory, exactly as a human would.
Stone runs out (a stone pickaxe, a sword and a furnace use thirteen), so the rest comes from a cobblestone
generator built once iron has made two buckets; no iron pickaxe is needed, since the frame is cast rather than
mined; wood comes from replanting the one tree, and the saplings its leaves drop are picked up; there is no coal,
so wood is the fuel; the frame is cast from the pool's lava.

To use it on a server: in `server.properties` set `level-type=minecraft\:flat` and
`generator-settings={"layers"\:[{"block"\:"minecraft\:air","height"\:1}],"biome"\:"minecraft\:plains"}`, put this
folder in the world's `datapacks` folder before first start, then run `/function kindred:sky_island` once.
