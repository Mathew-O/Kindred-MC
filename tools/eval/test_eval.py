import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


score = load('score')
compare = load('compare')


def death(cause, light=15, hunger=20, stage='Iron: Mine iron ore'):
    return {'cause': cause, 'light': light, 'hunger': hunger, 'stage': stage}


class DeathKindTest(unittest.TestCase):
    def test_01_lava_drowning_and_fall_by_cause(self):
        self.assertEqual('lava', score.death_kind(death('Kindred tried to swim in lava')))
        self.assertEqual('lava', score.death_kind(death('Kindred discovered the floor was lava')))
        self.assertEqual('drowning', score.death_kind(death('Echo drowned')))
        self.assertEqual('fall', score.death_kind(death('Sol fell from a high place')))

    def test_02_the_drowned_mob_is_not_drowning(self):
        self.assertEqual('dark melee', score.death_kind(death('Sol was slain by Drowned', light=0)))

    def test_03_starving_with_a_mob_near_comes_before_cornered(self):
        self.assertEqual('starving with a mob near',
                         score.death_kind(death('Sol was slain by Zombie', hunger=4, stage='Iron: fight back - Back off from zombie')))

    def test_04_cornered_is_a_mob_death_while_backing_off(self):
        self.assertEqual('cornered', score.death_kind(death('Sol was shot by Skeleton', stage='Iron: fight back - Back off from skeleton')))
        self.assertEqual('cornered', score.death_kind(death('Echo was slain by Zombie', stage='End: Prepare: Take cover from zombie')))

    def test_05_dark_melee_is_a_close_mob_in_the_dark(self):
        self.assertEqual('dark melee', score.death_kind(death('Echo was slain by Spider', light=3)))
        self.assertEqual('other', score.death_kind(death('Echo was slain by Spider', light=12)))

    def test_06_a_ranged_mob_in_the_dark_is_not_melee(self):
        self.assertEqual('other', score.death_kind(death('Kindred was shot by Skeleton', light=0)))

    def test_07_every_rule_names_a_listed_kind(self):
        self.assertEqual(score.DEATH_KINDS[-1], 'other')
        self.assertEqual(len(set(score.DEATH_KINDS)), len(score.DEATH_KINDS))


class StuckKindTest(unittest.TestCase):
    def test_01_water_loop(self):
        lines = ['Sol goal at 1 2 3: Iron: Climb toward water: out from under the water'] * 4
        self.assertEqual('water loop', score.stuck_kind(lines))

    def test_02_no_walkable_route_counts_blocked_lines_only(self):
        blocked = ['Echo native_goto BLOCKED: No walkable route toward 1, 2, 3 [Walk into the portal]'] * 3
        self.assertEqual('no walkable route', score.stuck_kind(blocked))
        self.assertEqual('other', score.stuck_kind(['No route from 1, 2, 3 toward 4, 5, 6 (expanded 6000)'] * 5))

    def test_03_the_same_refusal_its_numbers_aside(self):
        lines = ['Echo native_goto refused: Goto target is too far for one route [Walk to %d, 64, 0]' % x for x in range(3)]
        self.assertEqual('refused target repeated', score.stuck_kind(lines))

    def test_04_different_refusals_are_not_a_repeat(self):
        lines = ['Echo native_goto refused: a', 'Echo native_goto refused: b', 'Echo native_goto refused: c']
        self.assertEqual('other', score.stuck_kind(lines))

    def test_05_hole_or_pit_and_hemmed_in(self):
        self.assertEqual('hole or pit', score.stuck_kind(['Sol goal at 0 0 0: Wood: Climb toward the surface for food: pillar up'] * 3))
        self.assertEqual('hemmed in', score.stuck_kind(['Kindred goal at 0 0 0: End: Nether: no way on from here - Climb to find another way'] * 3))

    def test_06_most_matches_win_and_too_few_is_other(self):
        lines = ['x out from under the water'] * 3 + ['y BLOCKED: No walkable route toward 1, 1, 1'] * 5
        self.assertEqual('no walkable route', score.stuck_kind(lines))
        self.assertEqual('other', score.stuck_kind(['x out from under the water'] * (score.STUCK_LEAST - 1)))

    def test_07_a_spell_reads_its_players_lines_before_the_watchdog(self):
        log = '\n'.join(['[10:00:00] [Server thread/INFO]: Evaluation run: /tick sprint 100'] +
                        ['[10:00:01] [Server thread/INFO]: Sol goal at 0 0 0: Wood: no way on from here - wait'] * 3 +
                        ['[10:00:01] [Server thread/INFO]: Echo goal at 0 0 0: Wood: out from under the water'] * 3 +
                        ['[10:00:02] [Server thread/INFO]: Goal made no progress for 3600 ticks near 5, 6, 7; trying 8, 6, 7 (attempt 1)'])
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'console.txt'
            p.write_text(log, encoding='utf-8')
            found = score.stuck_kinds_of(p, {'Sol': {'stuck': [{'tick': 50, 'x': 5, 'y': 6, 'z': 7}]}, 'Echo': {'stuck': []}})
        self.assertEqual({'Sol': ['hemmed in'], 'Echo': []}, found)

    def test_08_a_spell_with_no_watchdog_line_is_placed_by_its_tick(self):
        log = '\n'.join(['[10:00:00] [Server thread/INFO]: Evaluation run: /tick sprint 24000'] +
                        ['[10:00:10] [Server thread/INFO]: Sol goal at 0 0 0: Wood: Climb toward the surface: step up'] * 3 +
                        ['[10:01:00] [Server thread/INFO]: Sol goal at 0 0 0: Wood: out from under the water'] * 3 +
                        ['[10:02:00] [Server thread/INFO]: Evaluation run: thinking at tick 12000: 1 slices'])
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'console.txt'
            p.write_text(log, encoding='utf-8')
            found = score.stuck_kinds_of(p, {'Sol': {'stuck': [{'tick': 2000, 'x': 1, 'y': 1, 'z': 1}]}})
        self.assertEqual({'Sol': ['hole or pit']}, found)


def summary(name, rows):
    """A summary with one seed a row: (seed, furthest index, deaths an hour, stuck minutes, iron pickaxe minutes or None)."""
    per_seed = {}
    for seed, furthest, deaths, stuck, pickaxe in rows:
        minutes = {} if pickaxe is None else {'iron_pickaxe': pickaxe}
        per_seed[seed] = {'teamFurthestIndex': furthest, 'teamDeathsPerHour': deaths, 'ticksRun': 144000,
                          'players': {'Kindred': {'stuckMinutes': stuck, 'minutesTo': minutes}}}
    return {'build': name, 'seeds': sorted(per_seed), 'perSeed': per_seed}


class PairedTest(unittest.TestCase):
    def test_01_sign_test_is_exact_and_two_sided(self):
        self.assertEqual(1.0, compare.sign_test(0, 0))
        self.assertAlmostEqual(2 / 1024 * 11, compare.sign_test(9, 1))
        self.assertAlmostEqual(2 / 1024, compare.sign_test(10, 0))
        self.assertEqual(1.0, compare.sign_test(5, 5))

    def test_02_bootstrap_interval_holds_the_median_and_is_repeatable(self):
        diffs = [-3, -2, -2, -1, 0, 1, -4, -2, -3, -1]
        lo, hi = compare.bootstrap(diffs)
        self.assertLessEqual(lo, -2)
        self.assertGreaterEqual(hi, -2)
        self.assertEqual((lo, hi), compare.bootstrap(diffs))

    def test_03_fewer_stuck_minutes_count_as_helped(self):
        a = summary('A', [(str(s), 5, 1.0, 30, 20) for s in range(10)])
        b = summary('B', [(str(s), 5, 1.0, 30 - (s < 8) * 6, 20) for s in range(10)])
        r = compare.paired(a, b, a['seeds'], 'stuckMinutes', 'fewer')
        self.assertEqual((8, 0, 2), (r['helped'], r['hurt'], r['same']))
        self.assertEqual(-6, r['median'])

    def test_04_a_portal_reached_counts_as_more(self):
        a = summary('A', [(str(s), 5, 1.0, 0, 20) for s in range(10)])
        b = summary('B', [(str(s), 6 if s < 3 else 5, 1.0, 0, 20) for s in range(10)])
        r = compare.paired(a, b, a['seeds'], 'portal', 'more')
        self.assertEqual((3, 0), (r['helped'], r['hurt']))

    def test_05_a_stage_not_reached_counts_as_the_runs_length(self):
        a = summary('A', [('1', 3, 0, 0, None)])
        self.assertEqual(120.0, compare.per_seed_value(a, '1', 'ironPickaxe'))

    def test_06_an_old_summary_md_gives_furthest_deaths_and_stuck(self):
        text = ('Runs: 1 seeds (101), EARNED. Scenario: goal=nether; ticks=144000.\n'
                '| 101 | 144000 | portal_lit | 1.5 | Kindred: portal_lit, 0, 2, 1/1, 1, 100%, 1; Sol: iron_ingot, 1, 1 |\n')
        s = compare.from_markdown(text, 'e40a10-x')
        self.assertEqual(['101'], s['seeds'])
        self.assertEqual((6, 1.5), (s['perSeed']['101']['teamFurthestIndex'], s['perSeed']['101']['teamDeathsPerHour']))
        self.assertEqual(9, compare.per_seed_value(s, '101', 'stuckMinutes'))
        self.assertIsNone(compare.per_seed_value(s, '101', 'ironPickaxe'))
        self.assertEqual(['goal=nether; ticks=144000'], s['scenarios'])


if __name__ == '__main__':
    unittest.main()
