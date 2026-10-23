package net.mabyfox.usefultrades;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.MapDecorations;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.StructureSearchResult;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explorer maps like the vanilla cartographer's, but for any structure: a map with an X on the
 * structure nearest to the villager. Maps for Nether structures (bastions, fortresses) are Nether
 * maps centred on the matching Nether spot (coordinates / 8), so they only work in the Nether.
 */
final class ExplorerMaps {

    /** Tags our map items with their structure id, so trade signatures can tell the maps apart. */
    static final NamespacedKey MAP_KEY = NamespacedKey.fromString("usefultrades:map");

    /** Same search distance vanilla cartographers use, in chunks. */
    static final int SEARCH_RADIUS = 100;

    private static final Set<String> NETHER_STRUCTURES =
            Set.of("bastion_remnant", "fortress", "nether_fossil", "ruined_portal_nether");

    private static final Map<String, String> NAMES = Map.of(
            "stronghold", "Stronghold Map",
            "ancient_city", "Ancient City Map",
            "bastion_remnant", "Bastion Map",
            "trial_chambers", "Trial Chambers Map");

    private ExplorerMaps() {
    }

    static Structure structure(String id) {
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE).get(key);
    }

    static MapCursor.Type icon(String id) {
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.MAP_DECORATION_TYPE.get(key);
    }

    static String defaultIcon(String structureId) {
        return key(structureId).equals("trial_chambers") ? "trial_chambers" : "red_x";
    }

    /** What the map is called in the trade screen, e.g. "Stronghold Map". */
    static String name(String structureId) {
        String key = key(structureId);
        return NAMES.getOrDefault(key, Arrays.stream(key.split("_"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" ")) + " Map");
    }

    /**
     * A stand-in map with no picture yet. It has the same signature as the real map, so syncing can
     * check whether a villager already has this trade without searching for the structure again.
     */
    static ItemStack placeholder(String structureId) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = map.getItemMeta();
        meta.itemName(Component.text(name(structureId)));
        meta.getPersistentDataContainer().set(MAP_KEY, PersistentDataType.STRING, structureId);
        map.setItemMeta(meta);
        return map;
    }

    /** The structure id stored on one of our maps, or null for any other item. */
    static String structureOf(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(MAP_KEY, PersistentDataType.STRING);
    }

    /**
     * Finds the structure nearest to {@code from} and draws a map to it.
     * Returns null when there's none within {@link #SEARCH_RADIUS} chunks (or no Nether for a Nether structure).
     */
    static ItemStack create(String structureId, String iconId, Location from) {
        Structure structure = structure(structureId);
        MapCursor.Type icon = icon(iconId);
        if (structure == null || icon == null) return null;

        World world = from.getWorld();
        Location origin = from.clone();
        if (NETHER_STRUCTURES.contains(key(structureId)) && world.getEnvironment() != World.Environment.NETHER) {
            world = Bukkit.getWorlds().stream()
                    .filter(w -> w.getEnvironment() == World.Environment.NETHER)
                    .findFirst().orElse(null);
            if (world == null) return null;
            origin = new Location(world, from.getX() / 8, 64, from.getZ() / 8);
        }

        // false = nearest one, even if another map already points there (vanilla skips those).
        StructureSearchResult found = world.locateNearestStructure(origin, structure, SEARCH_RADIUS, false);
        if (found == null) return null;
        Location target = found.getLocation();

        // Same scale and grid as vanilla explorer maps (scale 2, 512 blocks across).
        MapView view = Bukkit.createMap(world);
        view.setScale(MapView.Scale.NORMAL);
        int size = 128 << 2;
        view.setCenterX(Math.floorDiv(target.getBlockX() + 64, size) * size + size / 2 - 64);
        view.setCenterZ(Math.floorDiv(target.getBlockZ() + 64, size) * size + size / 2 - 64);
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);

        ItemStack map = placeholder(structureId);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        map.setItemMeta(meta);
        map.setData(DataComponentTypes.MAP_DECORATIONS, MapDecorations.mapDecorations(Map.of(
                "+", MapDecorations.decorationEntry(icon, target.getBlockX(), target.getBlockZ(), 180f))));
        return map;
    }

    private static String key(String structureId) {
        String id = structureId.toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
