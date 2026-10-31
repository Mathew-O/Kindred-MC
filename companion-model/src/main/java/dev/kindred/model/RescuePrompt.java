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
            You help a player in Minecraft (Java Edition 1.21.1) that is stuck. Its own ways of getting moving have run \
            for minutes and failed. You are given the blocks around it, what it carries, what it was doing and what it \
            tried. Reply with a short plan that gets it out: one to six steps, each written exactly in one of these \
            forms and nothing else:
            """ + RescuePlan.VOCABULARY + """

            north is -z, south +z, east +x, west -x. "pillar n" rises n blocks, a block of its own placed under its feet \
            at the top of each jump; "place" puts one of its blocks beside its feet; "dig" breaks a passage two high \
            sideways, or a shaft up or down; "retreat" backs off without turning. Every step is checked as it comes up and refused if the world or the server's rules forbid it, and the \
            plan stops at its first refusal. Give the fewest steps that surely get it out; give no step you are not \
            sure of. The reason is one sentence.""";

    static final String STEPS = "One to six steps, each written exactly as one of the forms listed, such as \"pillar 3\" or \"walk north 2\".";
    static final String REASON = "One sentence: why these steps get the player out.";
}
