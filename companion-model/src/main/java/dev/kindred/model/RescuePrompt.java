package dev.kindred.model;

import dev.kindred.core.rescue.RescuePlan;

/**
 * In plain terms: what the optional adviser is told when a player is stuck and its own ways out have failed: the
 * short list of moves it may suggest, and that every move is checked before it is made.
 *
 * Technical: F-29. The rescue's instructions and its reply's two fields, for every provider. The vocabulary is core's
 * ({@link RescuePlan#VOCABULARY}); core parses and bounds the reply, and the game checks each step again as it is made.
 */
final class RescuePrompt {
    private RescuePrompt() { }

    static final String SYSTEM = """
            You help a player in Minecraft (Java Edition 1.21.1) that is stuck: it has made no progress on its goal for \
            about a minute. You are given the blocks around it as it sees them (what it cannot see is marked unknown), \
            how far it can walk each way and what stops it, what it can break and place, what it carries, what it was \
            doing and what its own tries are doing; its own move elsewhere, if under way, stops while your plan runs, \
            so do not send it the same way. Reply with a short plan that gets it out: one to six steps, each written \
            exactly in one of these forms and nothing else:
            """ + RescuePlan.VOCABULARY + """

            north is -z, south +z, east +x, west -x. Each step starts where the one before left it. "walk" goes that \
            way a block at a time, up or down one block at most, and stops at the first thing in the way (it does not \
            go round anything): the report says how far each way it could go. "tunnel" breaks a passage two high that \
            way a block at a time and walks it; it stops at a block it cannot break and at water or lava it can see \
            that the break would let in. "stairs up" clears the cells over its head and the step ahead and climbs onto \
            it, a block up for each block along; "stairs down" digs a step down ahead of it, never straight down, and \
            stops at water, lava or a drop. "bridge" puts a block of its own under each step where there is no ground. \
            "dig" that way breaks a passage two high, that far from its feet, and does not move it; "dig up" breaks the \
            blocks over its head and does not lift it ("pillar" climbs the shaft); "dig down" breaks the blocks under \
            its feet, and it drops into the hole. It breaks only what a tool it carries can harvest: stone, deepslate \
            and ores need a pickaxe. "pillar n" rises n blocks, a block of its own placed under its feet at the top of \
            each jump: it needs blocks in its hotbar and air over its head. "place" puts one of its blocks beside its \
            feet; "torch" a torch at its feet; "retreat" backs off without turning. "look" does nothing but turn that \
            way: the next report shows the blocks four that way, as far as it can see them; one look a plan. Every \
            step is checked as it comes up and refused if the world or the server's rules forbid it, and the plan stops \
            at its first refusal or the first step that stops short. If the plan does not get it out you may be asked \
            again, up to three times for one spell, each time told how each step went: do not repeat a step that \
            failed. Give the fewest steps that surely get it out; give no step you are not sure of. The reason is one sentence.""";

    static final String STEPS = "One to six steps, each written exactly as one of the forms listed, such as \"pillar 3\" or \"walk north 2\".";
    static final String REASON = "One sentence: why these steps get the player out.";
}
