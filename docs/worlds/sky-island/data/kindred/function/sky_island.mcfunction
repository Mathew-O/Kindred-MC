# Kindred limited sky island, built around 0 64 0 in a void world (see ../../../../README.md).
# The same island for every player; nothing is handed out. Run once: /function kindred:sky_island
# Ground: an 11 x 11 island, three blocks deep, grass on top (players stand at y 64).
fill -5 61 -5 5 62 5 minecraft:dirt
fill -5 63 -5 5 63 5 minecraft:grass_block
# A low stone wall around the edge so animals (and players) do not wander off into the void (not wood: the lava is near).
fill -5 64 -5 5 64 -5 minecraft:cobblestone_wall
fill -5 64 5 5 64 5 minecraft:cobblestone_wall
fill -5 64 -4 -5 64 4 minecraft:cobblestone_wall
fill 5 64 -4 5 64 4 minecraft:cobblestone_wall
# One tree, south-west.
place feature minecraft:oak -3 64 2
# Water: a 2 x 2 pond (an endless source), north-west.
fill -4 63 -4 -3 63 -3 minecraft:water
# Lava: twelve sources sunk into the ground, north-east, away from the tree.
fill 1 63 -4 4 63 -2 minecraft:lava
# Stone: a small outcrop, south-east, sixteen stone with eight iron ore showing on its faces: two buckets and a
# flint and steel take seven, and one is spare.
fill 2 64 1 4 65 4 minecraft:stone
setblock 2 64 1 minecraft:iron_ore
setblock 4 65 1 minecraft:iron_ore
setblock 2 65 3 minecraft:iron_ore
setblock 4 64 4 minecraft:iron_ore
setblock 3 65 4 minecraft:iron_ore
setblock 4 64 2 minecraft:iron_ore
setblock 3 65 2 minecraft:iron_ore
setblock 2 64 4 minecraft:iron_ore
# Gravel for flint, south.
fill -1 64 3 0 64 4 minecraft:gravel
fill -1 64 2 -1 64 2 minecraft:gravel
# Animals for food.
summon minecraft:cow -1 64 -1
summon minecraft:cow 0 64 1
summon minecraft:cow 1 64 0
summon minecraft:chicken -2 64 0
summon minecraft:chicken 1 64 -1
setworldspawn 0 64 0
