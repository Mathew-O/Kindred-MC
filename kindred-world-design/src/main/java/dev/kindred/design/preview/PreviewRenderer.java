package dev.kindred.design.preview;

import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Text preview for diagnostics only; it never represents collision, lighting, or construction proof. */
public final class PreviewRenderer {
    public record Diff(Set<Pos> added, Set<Pos> removed, Set<Pos> changed) {
        public Diff { added = Set.copyOf(added); removed = Set.copyOf(removed); changed = Set.copyOf(changed); }
    }
    public String overhead(Design design, int y) {
        Box site = design.brief().site();
        if (site.max().x() - site.min().x() + 1 > 64 || site.max().z() - site.min().z() + 1 > 64) throw new IllegalArgumentException("Preview exceeds 64x64");
        Map<Pos, Voxel> voxels = new HashMap<>(); for (Voxel v : design.voxels()) if (v.position().y() == y) voxels.put(v.position(), v);
        StringBuilder out = new StringBuilder("approximate-overhead y=").append(y).append('\n');
        for (int z = site.min().z(); z <= site.max().z(); z++) {
            for (int x = site.min().x(); x <= site.max().x(); x++) {
                Voxel voxel = voxels.get(new Pos(x, y, z));
                out.append(voxel == null ? '.' : symbol(voxel));
            }
            out.append('\n');
        }
        return out.toString();
    }
    public Diff diff(Design before, Design after) {
        Map<Pos, Voxel> oldBlocks = map(before), newBlocks = map(after); Set<Pos> added = new TreeSet<>(), removed = new TreeSet<>(), changed = new TreeSet<>();
        for (Map.Entry<Pos, Voxel> entry : newBlocks.entrySet()) {
            Voxel old = oldBlocks.get(entry.getKey()); if (old == null) added.add(entry.getKey()); else if (!old.equals(entry.getValue())) changed.add(entry.getKey());
        }
        for (Pos p : oldBlocks.keySet()) if (!newBlocks.containsKey(p)) removed.add(p);
        return new Diff(added, removed, changed);
    }
    private static Map<Pos, Voxel> map(Design design) { Map<Pos, Voxel> result = new HashMap<>(); for (Voxel v : design.voxels()) result.put(v.position(), v); return result; }
    private static char symbol(Voxel voxel) { return switch (voxel.phase()) { case FLOOR_AND_PATH -> '#'; case WALLS -> 'W'; case ROOF -> '^'; }; }
}
