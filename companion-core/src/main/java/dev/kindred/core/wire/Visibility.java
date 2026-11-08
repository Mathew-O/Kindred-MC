package dev.kindred.core.wire;

/**
 * In plain terms: who may know a fact: only the one it is about, their faction, their team, their side of a match,
 * everyone playing their match, everyone living at their place, everyone, or only the operators. It follows who someone is to the fact, never how
 * near they stand.
 *
 * Technical: The visibility of F-32's relations, used by F-34 to keep a message's facts on a channel whose audience
 * may know them. Not ordered: a faction fact is not a team fact. {@link #OPERATOR} facts go only to operators; the
 * operator channel carries any fact, as operators may audit everything.
 */
public enum Visibility { SELF, FACTION, TEAM, SIDE, MATCH, SETTLEMENT, PUBLIC, OPERATOR }
