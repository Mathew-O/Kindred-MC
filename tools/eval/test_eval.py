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
run = load('run')


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

    def test_06b_quiet_work_only_where_no_failure_rule_holds(self):
        waits = ['Sol native_wait COMPLETED [Wait for daylight]'] * 9
        self.assertEqual('waiting for daylight or a teammate', score.stuck_kind(waits))
        blocked = ['Sol native_goto BLOCKED: No walkable route toward 1, 2, 3 [Walk]'] * 3
        self.assertEqual('no walkable route', score.stuck_kind(waits + blocked))
        self.assertEqual('mining for a find', score.stuck_kind(['Echo goal at 1 2 3: Iron: Mine iron ore (2/18) - Branch mine for iron: clear 4, 5, 6'] * 3))

    def test_06c_the_same_step_over_and_over_is_a_busy_loop(self):
        table = ['Echo native_place COMPLETED [Place a crafting table]', 'Echo native_break COMPLETED [Pick up the crafting table]']
        self.assertEqual('busy-work loop', score.stuck_kind(table * score.BUSY_LEAST))
        self.assertEqual('other', score.stuck_kind(table * (score.BUSY_LEAST - 1)))

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



class FreeSwitchTest(unittest.TestCase):
    """A run with free travel or free items allowed is never earned, and no comparison (no ladder step) counts it."""

    def run_dir(self, root, policy):
        folder = Path(root) / 'b' / '101'
        folder.mkdir(parents=True)
        result = {'scenario': 'goal=nether; ticks=1200', 'kind': 'earned', 'build': 'x', 'seed': 101, 'ticks': 1200,
                  'ticksRun': 1200, 'complete': True, 'policy': policy,
                  'players': {'Kindred': {'stages': {}, 'deaths': [], 'deathsNotListed': 0, 'stuck': [], 'tokens': 0}}}
        (folder / 'eval-result.json').write_text(json.dumps(result), encoding='utf-8')
        return folder.parent

    def test_01_teleport_allowed_is_staged_and_named(self):
        with tempfile.TemporaryDirectory() as root:
            s = score.score(self.run_dir(root, {'teleport': True, 'freeItems': False}), use_metrics=False)
        self.assertEqual(['staged'], s['kinds'])
        self.assertEqual({'101': ['teleport']}, s['freeSwitches'])
        self.assertIn('Seed 101 ran with teleport allowed: not earned', score.markdown(s))

    def test_02_both_forbidden_stays_earned(self):
        with tempfile.TemporaryDirectory() as root:
            s = score.score(self.run_dir(root, {'teleport': False, 'freeItems': False}), use_metrics=False)
        self.assertEqual(['earned'], s['kinds'])
        self.assertEqual({}, s['freeSwitches'])

    def test_03_the_comparison_refuses_a_run_with_free_items(self):
        a = {'build': 'A', 'seeds': ['101'], 'freeSwitches': {}}
        b = {'build': 'B', 'seeds': ['101'], 'freeSwitches': {'101': ['freeItems']}}
        self.assertEqual([], compare.free_runs(a))
        self.assertEqual(['B seed 101 (freeItems)'], compare.free_runs(b))

class NightsMeasureTest(unittest.TestCase):
    """E-70a: chunk-loads and chunk-minutes per game hour, and deaths and stuck minutes per game day as well as per hour."""

    def test_01_chunks_per_hour_and_deaths_per_day(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / 'b' / '101'
            folder.mkdir(parents=True)
            p = {'stages': {}, 'deaths': [{'tick': 10, 'cause': 'Kindred was slain by Zombie'}], 'deathsNotListed': 0,
                 'stuck': [{'tick': 5, 'x': 0, 'y': 0, 'z': 0, 'attempt': 1}], 'tokens': 0, 'chunkLoads': 600, 'chunkMinutes': 1734.0}
            result = {'scenario': 'goal=nether; ticks=144000', 'kind': 'earned', 'build': 'x', 'seed': 101, 'ticks': 144000,
                      'ticksRun': 144000, 'complete': True, 'days': 8.0, 'players': {'Kindred': p}}
            (folder / 'eval-result.json').write_text(json.dumps(result), encoding='utf-8')
            s = score.score(folder.parent, use_metrics=False)
        seed = s['perSeed']['101']
        self.assertEqual(300.0, seed['players']['Kindred']['chunkLoadsPerHour'])
        self.assertEqual(867.0, seed['teamChunkMinutesPerHour'])
        self.assertEqual(8.0, seed['days'])
        self.assertEqual(0.12, seed['teamDeathsPerDay'])
        self.assertEqual(0.38, seed['teamStuckMinutesPerDay'])
        text = score.markdown(s)
        self.assertIn('| Chunk-loads a game hour, the team | 300.0', text)
        self.assertIn('| Deaths a game day, the team | 0.12', text)

    def test_02_an_older_file_has_none_of_them(self):
        with tempfile.TemporaryDirectory() as root:
            s = score.score(FreeSwitchTest().run_dir(root, {}), use_metrics=False)
        self.assertNotIn('days', s['team'])
        self.assertNotIn('Chunk-loads', score.markdown(s))


class KeepWorldTest(unittest.TestCase):
    """F-26d: --keep-world keeps the run's players' own saves beside its ground."""
    IDS = {'Kindred': 'c3eed6dc-3bd3-4239-85eb-cfc75047f22d', 'Sol': 'ddc070f5-3cb9-4e18-a639-41eff507547a',
           'Echo': 'df828ada-60b1-415f-8a3f-86a161d71c49', 'Stranger': '0b0c0ea5-0f1a-4783-b56e-b7f3e2107db5'}

    class Done:
        returncode = 0

    def seed(self, root, usercache=True):
        folder = Path(root) / '101'
        (folder / 'logs').mkdir(parents=True)
        (folder / 'world' / 'region').mkdir(parents=True)
        (folder / 'world' / 'region' / 'r.0.0.mca').write_bytes(b'region')
        (folder / 'world' / 'playerdata').mkdir()
        for uuid in self.IDS.values():
            (folder / 'world' / 'playerdata' / (uuid + '.dat')).write_bytes(b'save')
            (folder / 'world' / 'playerdata' / (uuid + '.dat_old')).write_bytes(b'old')
        players = {name: {} for name in ('Kindred', 'Sol', 'Echo')}
        (folder / 'logs' / 'eval-result.json').write_text(json.dumps({'ticksRun': 100, 'players': players}), encoding='utf-8')
        if usercache:
            cache = [{'name': name, 'uuid': uuid, 'expiresOn': 'x'} for name, uuid in self.IDS.items()]
            (folder / 'usercache.json').write_text(json.dumps(cache), encoding='utf-8')
        return folder

    def kept(self, folder):
        saves = folder / 'world-kept' / 'playerdata'
        return sorted(p.name for p in saves.iterdir()) if saves.is_dir() else []

    def test_01_one_save_for_each_player_in_the_result(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.seed(root)
            ok, ticks = run.finish(folder, self.Done(), 0, keep_world=True)
            self.assertTrue(ok)
            self.assertEqual(100, ticks)
            self.assertEqual(sorted(self.IDS[n] + '.dat' for n in ('Kindred', 'Sol', 'Echo')), self.kept(folder))
            self.assertTrue((folder / 'world-kept' / 'region' / 'r.0.0.mca').is_file())
            self.assertFalse((folder / 'world').exists())

    def test_02_without_the_usercache_every_save_is_kept(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.seed(root, usercache=False)
            run.finish(folder, self.Done(), 0, keep_world=True)
            self.assertEqual(sorted(uuid + '.dat' for uuid in self.IDS.values()), self.kept(folder))

    def test_03_no_saves_kept_without_keep_world(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.seed(root)
            run.finish(folder, self.Done(), 0)
            self.assertFalse((folder / 'world-kept').exists())
            self.assertFalse((folder / 'world').exists())

    def test_04_what_a_restart_needs_is_kept_too(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.seed(root)
            world = folder / 'world'
            (world / 'level.dat').write_bytes(b'level')
            (world / 'data').mkdir()
            (world / 'data' / 'raids.dat').write_bytes(b'raids')
            (world / 'kindred' / 'memory').mkdir(parents=True)
            (world / 'kindred' / 'memory' / 'Sol.json').write_text('{}', encoding='utf-8')
            (world / 'kindred' / 'mode.properties').write_text('mode=factions\n', encoding='utf-8')
            (world / 'kindred' / 'edits.properties').write_text('maxNativePlayers=8\n', encoding='utf-8')
            run.finish(folder, self.Done(), 0, keep_world=True)
            kept = folder / 'world-kept'
            self.assertEqual(b'level', (kept / 'level.dat').read_bytes())
            self.assertEqual(b'raids', (kept / 'data' / 'raids.dat').read_bytes())
            self.assertEqual('{}', (kept / 'kindred' / 'memory' / 'Sol.json').read_text(encoding='utf-8'))
            self.assertEqual('mode=factions\n', (kept / 'kindred' / 'mode.properties').read_text(encoding='utf-8'))
            self.assertEqual('maxNativePlayers=8\n', (kept / 'kindred' / 'edits.properties').read_text(encoding='utf-8'))
            self.assertFalse(world.exists())

    def test_05_a_world_without_them_keeps_what_it_has(self):
        with tempfile.TemporaryDirectory() as root:
            folder = self.seed(root)
            run.finish(folder, self.Done(), 0, keep_world=True)
            kept = folder / 'world-kept'
            self.assertFalse((kept / 'level.dat').exists())
            self.assertFalse((kept / 'kindred').exists())
            self.assertTrue((kept / 'region' / 'r.0.0.mca').is_file())


class BridgeMeasureTest(unittest.TestCase):
    """E-71a: bridge blocks a second, per player and for the team, over every bridge run."""

    def test_01_bridge_blocks_a_second(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / 'b' / '101'
            folder.mkdir(parents=True)
            p = {'stages': {}, 'deaths': [], 'deathsNotListed': 0, 'stuck': [], 'tokens': 0, 'bridgeBlocks': 17, 'bridgeSeconds': 6.0}
            q = {'stages': {}, 'deaths': [], 'deathsNotListed': 0, 'stuck': [], 'tokens': 0, 'bridgeBlocks': 0, 'bridgeSeconds': 0.0}
            result = {'scenario': 'goal=nether; ticks=144000', 'kind': 'earned', 'build': 'x', 'seed': 101, 'ticks': 144000,
                      'ticksRun': 144000, 'complete': True, 'players': {'Kindred': p, 'Sol': q}}
            (folder / 'eval-result.json').write_text(json.dumps(result), encoding='utf-8')
            s = score.score(folder.parent, use_metrics=False)
        seed = s['perSeed']['101']
        self.assertEqual(2.83, seed['players']['Kindred']['bridgeBlocksPerSecond'])
        self.assertNotIn('bridgeBlocksPerSecond', seed['players']['Sol'])
        self.assertEqual(2.83, seed['teamBridgeBlocksPerSecond'])
        self.assertIn('| Bridge blocks a second, the team | 2.83', score.markdown(s))

    def test_02_a_run_that_never_bridged_has_no_rate(self):
        with tempfile.TemporaryDirectory() as root:
            s = score.score(FreeSwitchTest().run_dir(root, {}), use_metrics=False)
        self.assertNotIn('teamBridgeBlocksPerSecond', s['team'])
        self.assertNotIn('Bridge blocks', score.markdown(s))


# A stand-in for run.py's main with the server replaced by a stub child: argv[1] is how the stub takes SIGTERM
# ("term": it ends; "ignore": it must be killed), argv[2] what stops run.py ("signal" or "error"), argv[3] the folder.
STOPPED_RUN = r"""
import importlib.util, os, subprocess, sys, time
from pathlib import Path
spec = importlib.util.spec_from_file_location('run', sys.argv[4])
run = importlib.util.module_from_spec(spec); spec.loader.exec_module(run)
stub, how, root = sys.argv[1], sys.argv[2], Path(sys.argv[3])
code = {'term': 'import os, time', 'ignore': 'import os, signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN)'}[stub]
code += '; open("pid", "w").write(str(os.getpid())); time.sleep(600)'
real = subprocess.Popen
run.subprocess.Popen = lambda command, **kw: real([sys.executable, '-c', code], cwd=kw['cwd'], stdout=kw['stdout'], stderr=kw['stderr'])
run.pinned = lambda: ('m', 'l', 'f')
run.launcher = lambda cache, minecraft, loader: root / 'launcher.jar'
run.fabric_api_jar = lambda fabric: None
run.vanilla_server = lambda cache, minecraft: None
run.prepare = lambda folder, *rest: folder.mkdir(parents=True, exist_ok=True)
run.STOP_WAIT = 1
if how == 'error':
    real_sleep = time.sleep
    def sleep(seconds):
        while not (root / 'out' / 'stub' / '1' / 'pid').exists():
            real_sleep(0.05)
        run.time.sleep = real_sleep  # once: the stop's own waits sleep as usual
        raise RuntimeError('a fault in the loop')
    run.time.sleep = sleep
(root / 'stub.jar').write_bytes(b'jar')
sys.argv = ['run.py', '--jar', str(root / 'stub.jar'), '--name', 'stub', '--seeds', '1', '--out', str(root / 'out')]
run.main()
"""


class StopTest(unittest.TestCase):
    """A stopped run.py leaves no server behind (a server left running keeps playing and, with a key, spending)."""

    def stopped(self, stub, how, sig=None):
        import os, signal, subprocess, time
        with tempfile.TemporaryDirectory() as root:
            script = Path(root) / 'stopped_run.py'
            script.write_text(STOPPED_RUN, encoding='utf-8')
            wrapper = subprocess.Popen([sys.executable, str(script), stub, how, root, str(HERE / 'run.py')],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            pid_file = Path(root) / 'out' / 'stub' / '1' / 'pid'
            deadline = time.time() + 30
            while not (pid_file.exists() and pid_file.read_text()) and time.time() < deadline and wrapper.poll() is None:
                time.sleep(0.05)
            pid = int(pid_file.read_text())
            if sig is not None:
                wrapper.send_signal(sig)
            out, _ = wrapper.communicate(timeout=30)
            time.sleep(0.2)
            with self.assertRaises(ProcessLookupError, msg='the stub server is still running'):
                os.kill(pid, 0)
            return wrapper.returncode, out

    def test_01_sigterm_stops_the_server_and_says_so(self):
        import signal
        code, out = self.stopped('term', 'signal', signal.SIGTERM)
        self.assertEqual(128 + signal.SIGTERM, code)
        self.assertIn("seed 1: server stopped (pid", out)
        self.assertIn("on run.py's SIGTERM", out)

    def test_02_sigint_kills_a_server_that_ignores_the_stop(self):
        import signal
        code, out = self.stopped('ignore', 'signal', signal.SIGINT)
        self.assertEqual(128 + signal.SIGINT, code)
        self.assertIn("seed 1: server killed after 1 s (pid", out)
        self.assertIn("on run.py's SIGINT", out)

    def test_03_a_fault_in_the_loop_stops_the_server_too(self):
        code, out = self.stopped('term', 'error')
        self.assertNotEqual(0, code)
        self.assertIn('RuntimeError: a fault in the loop', out)
        self.assertIn("seed 1: server stopped (pid", out)
        self.assertIn("on run.py's exit", out)


class BlockedTest(unittest.TestCase):
    """E-40: blocks for reconciliation, scored apart from stuck minutes."""
    SOL, ECHO = '9e26ad61-0d9a-4812-a5fc-67ec7ccf5078', 'df828ada-60b1-415f-8a3f-86a161d71c49'

    def console(self, root, echo_comes_back):
        lines = ['[10:00:00] [Server thread/INFO]: Allocated Sol (%s). Preparing its native body.' % self.SOL,
                 '[10:00:00] [Server thread/INFO]: Allocated Echo (%s). Preparing its native body.' % self.ECHO,
                 '[10:00:00] [Server thread/INFO]: Evaluation run: /tick sprint 24000',
                 '[10:00:10] [Server thread/INFO]: Evaluation run: thinking at tick 12000: 1 slices',
                 '[10:00:15] [Server thread/ERROR]: Native player %s blocked: Native action outcome requires reconciliation' % self.SOL,
                 'java.lang.IllegalStateException: Native break outcome requires reconciliation (The block changed before an attributable break)',
                 '[10:00:15] [Server thread/INFO]: Sol picked up 1 minecraft:gravel',
                 '[10:00:16] [Server thread/ERROR]: Native player %s blocked: Native action outcome requires reconciliation' % self.ECHO,
                 'java.lang.IllegalStateException: Attack item conservation check failed; reconcile']
        if echo_comes_back:
            lines.append('[10:00:18] [Server thread/INFO]: Echo native_goto COMPLETED [Explore north for animals]')
        lines.append('[10:00:20] [Server thread/INFO]: Evaluation run: thinking at tick 24000: 1 slices')
        path = Path(root) / 'console.txt'
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        return path

    def test_01_a_block_lasts_to_the_next_completed_action_or_the_run_end(self):
        with tempfile.TemporaryDirectory() as root:
            found = score.blocked_of(self.console(root, True), 24000)
        self.assertEqual(18000, found['Sol'][0]['tick'])
        self.assertEqual(5.0, found['Sol'][0]['minutes'])  # to the run's end: 6,000 ticks; its own pickup does not end it
        self.assertTrue(found['Sol'][0]['reason'].startswith('Native break outcome requires reconciliation'))
        self.assertEqual(19200, found['Echo'][0]['tick'])
        self.assertEqual(2.0, found['Echo'][0]['minutes'])  # to its next completed action, 2,400 ticks on
        self.assertEqual('Attack item conservation check failed; reconcile', found['Echo'][0]['reason'])

    def test_02_scored_apart_from_stuck_minutes(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / 'b' / '101'
            folder.mkdir(parents=True)
            self.console(folder, False)
            players = {n: {'stages': {}, 'deaths': [], 'deathsNotListed': 0, 'stuck': [{'tick': 6000, 'x': 0, 'y': 0, 'z': 0}], 'tokens': 0}
                       for n in ('Sol', 'Echo', 'Kindred')}
            result = {'scenario': 'goal=nether; ticks=24000', 'kind': 'earned', 'build': 'x', 'seed': 101, 'ticks': 24000,
                      'ticksRun': 24000, 'complete': True, 'policy': {}, 'players': players}
            (folder / 'eval-result.json').write_text(json.dumps(result), encoding='utf-8')
            s = score.score(folder.parent, use_metrics=False)
        seed = s['perSeed']['101']['players']
        self.assertEqual((1, 5.0), (seed['Sol']['blockedForReconciliation'], seed['Sol']['blockedMinutes']))
        self.assertEqual((1, 4.0), (seed['Echo']['blockedForReconciliation'], seed['Echo']['blockedMinutes']))
        self.assertEqual((0, 0), (seed['Kindred']['blockedForReconciliation'], seed['Kindred']['blockedMinutes']))
        self.assertNotIn('blocks', seed['Kindred'])
        self.assertEqual([3, 3, 3], [seed[n]['stuckMinutes'] for n in ('Sol', 'Echo', 'Kindred')])  # unchanged by a block
        self.assertEqual(9.0, s['team']['blockedMinutes']['median'])
        self.assertIn('| Blocked for reconciliation, game minutes, the team (apart from stuck) | 9', score.markdown(s))

    def test_03_no_console_no_blocks(self):
        self.assertEqual({}, score.blocked_of(None, 24000))



# A stand-in server for the stall watcher (M-06k4a): it logs a stop that reaches "Saving worlds" and, with argv[1]
# "hang", never saves; with "save", it saves and exits. The watcher's bounds are cut to seconds.
STALLED_RUN = r"""
import importlib.util, subprocess, sys, time
from pathlib import Path
spec = importlib.util.spec_from_file_location('run', sys.argv[3])
run = importlib.util.module_from_spec(spec); spec.loader.exec_module(run)
how, root = sys.argv[1], Path(sys.argv[2])
code = 'import sys, time; print("Stopping server"); print("Saving worlds", flush=True); '
code += {'hang': 'time.sleep(600)', 'save': 'time.sleep(1); print("ThreadedAnvilChunkStorage: All dimensions are saved", flush=True)'}[how]
real = subprocess.Popen
# The server's start only (it alone runs in the seed's folder); the dump's own jstack runs as it is.
run.subprocess.Popen = lambda command, **kw: (real([sys.executable, '-c', code], cwd=kw['cwd'], stdout=kw['stdout'], stderr=kw['stderr'])
                                              if 'cwd' in kw else real(command, **kw))
run.pinned = lambda: ('m', 'l', 'f')
run.launcher = lambda cache, minecraft, loader: root / 'launcher.jar'
run.fabric_api_jar = lambda fabric: None
run.vanilla_server = lambda cache, minecraft: None
def prepare(folder, *rest):
    (folder / 'world' / 'region').mkdir(parents=True, exist_ok=True)
    (folder / 'world' / 'level.dat').write_bytes(b'l')
    (folder / 'world' / 'region' / 'r.0.0.mca').write_bytes(b'r')
run.prepare = prepare
run.STALL, run.STALL_KILL = 2, 8
real_dump = run.dump_stall
run.dump_stall = lambda folder, process: real_dump(folder, process, dumps=2, apart=0)
(root / 'stub.jar').write_bytes(b'jar')
sys.argv = ['run.py', '--jar', str(root / 'stub.jar'), '--name', 'stub', '--seeds', '1', '--out', str(root / 'out')]
run.main()
"""


class StallTest(unittest.TestCase):
    """A stop that hangs at "Saving worlds" is dumped, then killed, before --timeout (M-06k4a)."""

    def test_01_the_decision_from_the_console(self):
        self.assertEqual(('none', None), run.stall_action('Starting\n', None, 100))
        self.assertEqual(('watch', 100), run.stall_action('Stopping server\nSaving worlds\n', None, 100))
        self.assertEqual(('watch', 100), run.stall_action('Saving worlds\n', 100, 100 + run.STALL - 1))
        self.assertEqual(('dump', 100), run.stall_action('Saving worlds\n', 100, 100 + run.STALL))
        self.assertEqual(('kill', 100), run.stall_action('Saving worlds\n', 100, 100 + run.STALL_KILL))
        self.assertEqual(('none', None), run.stall_action('Saving worlds\nAll dimensions are saved\n', 100, 100 + run.STALL_KILL))

    def stalled(self, how):
        import subprocess
        with tempfile.TemporaryDirectory() as root:
            script = Path(root) / 'stalled_run.py'
            script.write_text(STALLED_RUN, encoding='utf-8')
            done = subprocess.run([sys.executable, str(script), how, root, str(HERE / 'run.py')], capture_output=True, text=True, timeout=90)
            stall = Path(root) / 'out' / 'stub' / '1' / 'stall'
            files = sorted(p.name for p in stall.iterdir()) if stall.is_dir() else []
            world = (stall / 'world-files.txt').read_text(encoding='utf-8') if (stall / 'world-files.txt').is_file() else ''
            info = json.loads((stall.parent / 'run.json').read_text(encoding='utf-8'))
            return done.stdout + done.stderr, files, world, info

    def test_02_a_hung_stop_is_dumped_then_killed(self):
        out, files, world, info = self.stalled('hang')
        self.assertIn('seed 1: its stop has stood at "Saving worlds"', out)
        self.assertIn('seed 1: its stop hung', out)
        self.assertIn('seed 1 ended: exit -9', out)
        self.assertEqual(['threads-1.txt', 'threads-2.txt', 'world-files.txt'], files)
        self.assertIn('level.dat', world)
        self.assertIn('region/r.0.0.mca', world)
        self.assertEqual('stall/', info['stopHung']['dumps'])  # counted in its own record, not silent
        self.assertGreaterEqual(info['stopHung']['killedAfter'], 8)

    def test_03_a_stop_that_saves_is_left_alone(self):
        out, files, world, info = self.stalled('save')
        self.assertNotIn('its stop', out)
        self.assertIn('seed 1 ended: exit 0', out)
        self.assertEqual([], files)
        self.assertNotIn('stopHung', info)

    def test_04_the_summary_says_so(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / 'b' / '109'
            folder.mkdir(parents=True)
            players = {n: {'stages': {}, 'deaths': [], 'deathsNotListed': 0, 'stuck': [], 'tokens': 0} for n in ('Sol', 'Echo', 'Kindred')}
            result = {'scenario': 'goal=none; mode=factions; ticks=144000', 'kind': 'earned', 'build': 'x', 'seed': 109, 'ticks': 144000,
                      'ticksRun': 144001, 'complete': True, 'policy': {}, 'players': players}
            (folder / 'eval-result.json').write_text(json.dumps(result), encoding='utf-8')
            (folder / 'run.json').write_text(json.dumps({'exit': -9, 'stopHung': {'killedAfter': 600, 'dumps': 'stall/'}}), encoding='utf-8')
            s = score.score(folder.parent, use_metrics=False)
        self.assertEqual({'109': {'killedAfter': 600, 'dumps': 'stall/'}}, s['stopHung'])
        self.assertIn('Seed 109: stop hung past Saving worlds, killed at 600 s, dumps in stall/ (M-06k4a).', score.markdown(s))


if __name__ == '__main__':
    unittest.main()
