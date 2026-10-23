package net.mabyfox.unborders;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Draws nation land on BlueMap as a "Nations" layer. Only touched when BlueMap is installed,
 * so the plugin still loads without it.
 */
final class BlueMapLayer {

    private static final String SET_ID = "unborders-nations";

    private final UNBorders plugin;

    BlueMapLayer(UNBorders plugin) { this.plugin = plugin; }

    void start() {
        // BlueMap calls this when it starts and again after every /bluemap reload.
        BlueMapAPI.onEnable(api -> Bukkit.getScheduler().runTask(plugin, this::redraw));
    }

    /** One rectangle per run of side-by-side chunks in a row, so there are few markers and few inner lines. */
    void redraw() {
        BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
        if (api == null) return;

        // world -> nation -> z -> sorted x
        Map<String, Map<String, Map<Integer, TreeSet<Integer>>>> rows = new HashMap<>();
        for (Map.Entry<String, String> e : plugin.claims().entrySet()) {
            String[] k = e.getKey().split(";");
            rows.computeIfAbsent(k[0], w -> new HashMap<>())
                .computeIfAbsent(e.getValue(), n -> new TreeMap<>())
                .computeIfAbsent(Integer.parseInt(k[2]), z -> new TreeSet<>())
                .add(Integer.parseInt(k[1]));
        }

        for (World world : Bukkit.getWorlds()) {
            BlueMapWorld bw = api.getWorld(world).orElse(null);
            if (bw == null) continue;
            MarkerSet set = new MarkerSet("Nations");
            set.setToggleable(true);
            set.setDefaultHidden(false);
            float y = world.getSeaLevel();
            for (Map.Entry<String, Map<Integer, TreeSet<Integer>>> byNation : rows.getOrDefault(world.getName(), Map.of()).entrySet()) {
                UNBorders.Nation n = plugin.nation(byNation.getKey());
                if (n == null) continue;
                boolean war = plugin.atAnyWar(n.key);
                int rgb = colorFor(n.key);
                Color line = new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 1f);
                Color fill = new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, war ? 0.45f : 0.25f);
                String label = n.name + (war ? " (at war)" : "");
                int i = 0;
                for (Map.Entry<Integer, TreeSet<Integer>> row : byNation.getValue().entrySet()) {
                    int z = row.getKey();
                    for (int[] run : runs(row.getValue())) {
                        ShapeMarker m = new ShapeMarker(label,
                                Shape.createRect(run[0] * 16, z * 16, (run[1] + 1) * 16, (z + 1) * 16), y);
                        m.setLineColor(line);
                        m.setFillColor(fill);
                        m.setLineWidth(2);
                        m.setDepthTestEnabled(false);
                        m.setDetail("<b>" + n.name + "</b>" + (war ? "<br><span style='color:#f55'>At war: can be raided</span>" : ""));
                        set.getMarkers().put(n.key + "-" + i++, m);
                    }
                }
            }
            for (BlueMapMap map : bw.getMaps()) map.getMarkerSets().put(SET_ID, set);
        }
    }

    private static List<int[]> runs(TreeSet<Integer> xs) {
        List<int[]> out = new ArrayList<>();
        int start = Integer.MIN_VALUE, prev = Integer.MIN_VALUE;
        for (int x : xs) {
            if (start != Integer.MIN_VALUE && x == prev + 1) { prev = x; continue; }
            if (start != Integer.MIN_VALUE) out.add(new int[] { start, prev });
            start = prev = x;
        }
        if (start != Integer.MIN_VALUE) out.add(new int[] { start, prev });
        return out;
    }

    /** A steady, bright color per nation. */
    private static int colorFor(String key) {
        float hue = (key.hashCode() & 0xffff) / 65536f;
        return java.awt.Color.HSBtoRGB(hue, 0.75f, 0.95f) & 0xffffff;
    }
}
