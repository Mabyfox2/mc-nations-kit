package net.mabyfox.usefultrades;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Locale;

/**
 * One extra trade from config.yml.
 *
 * @param id              "profession/key", used to track the trade on each villager
 * @param level           villager level (1 Novice .. 5 Master) at which the trade appears
 * @param result          what the villager sells
 * @param price           first cost
 * @param price2          optional second cost (null if none)
 * @param maxUses         trades before the villager needs to restock
 * @param villagerXp      villager experience gained per trade
 * @param playerXp        whether the player gets experience orbs
 * @param priceMultiplier how much demand raises the price (vanilla uses 0.05 or 0.2)
 * @param map             structure id for an explorer map trade, or null; result is then a placeholder
 *                        and the real map is drawn per villager (see {@link ExplorerMaps})
 * @param mapIcon         map marker for the structure, e.g. "red_x" (null unless map is set)
 */
record TradeDef(String id, int level, ItemStack result, ItemStack price, ItemStack price2,
                int maxUses, int villagerXp, boolean playerXp, float priceMultiplier,
                String map, String mapIcon) {

    /** @param costMultiplier multiplies both prices (capped at a full stack), see "cost-multiplier" in config.yml */
    static TradeDef parse(String id, ConfigurationSection s, int costMultiplier) {
        if (s == null) throw new IllegalArgumentException("not a section");

        int level = s.getInt("level", 1);
        if (level < 1 || level > 5) throw new IllegalArgumentException("level must be 1-5, got " + level);

        ItemStack result;
        String map = null;
        String mapIcon = null;
        if (s.isString("map")) {
            map = s.getString("map").trim().toLowerCase(Locale.ROOT);
            if (ExplorerMaps.structure(map) == null) throw new IllegalArgumentException("unknown structure '" + map + "'");
            mapIcon = s.getString("map-icon", ExplorerMaps.defaultIcon(map)).trim().toLowerCase(Locale.ROOT);
            if (ExplorerMaps.icon(mapIcon) == null) throw new IllegalArgumentException("unknown map-icon '" + mapIcon + "'");
            result = ExplorerMaps.placeholder(map);
        } else if (s.isString("book")) {
            result = book(s.getString("book"));
        } else if (s.isString("item")) {
            result = item(s.getString("item"));
        } else {
            throw new IllegalArgumentException("needs 'item', 'book' or 'map'");
        }

        if (!s.isString("price")) throw new IllegalArgumentException("missing 'price'");
        ItemStack price = UsefulTrades.times(item(s.getString("price")), costMultiplier);
        ItemStack price2 = s.isString("price2") ? UsefulTrades.times(item(s.getString("price2")), costMultiplier) : null;

        int maxUses = s.getInt("max-uses", 12);
        if (maxUses < 1) throw new IllegalArgumentException("max-uses must be at least 1");
        int villagerXp = Math.max(0, s.getInt("villager-xp", 2));
        boolean playerXp = s.getBoolean("player-xp", true);
        float priceMultiplier = (float) s.getDouble("price-multiplier", 0.05);

        return new TradeDef(id, level, result, price, price2, maxUses, villagerXp, playerXp, priceMultiplier, map, mapIcon);
    }

    boolean isMap() {
        return map != null;
    }

    MerchantRecipe toRecipe() {
        return toRecipe(result);
    }

    /** The trade, selling {@code sold} instead of the configured result (used for real explorer maps). */
    MerchantRecipe toRecipe(ItemStack sold) {
        MerchantRecipe r = new MerchantRecipe(sold.clone(), 0, maxUses, playerXp, villagerXp, priceMultiplier);
        r.addIngredient(price.clone());
        if (price2 != null) r.addIngredient(price2.clone());
        return r;
    }

    /** Parses "24 emerald", "emerald" or "minecraft:emerald 24". */
    static ItemStack item(String spec) {
        String[] parts = spec.trim().split("\\s+");
        int amount = 1;
        String name;
        if (parts.length == 1) {
            name = parts[0];
        } else if (parts.length == 2 && isInt(parts[0])) {
            amount = Integer.parseInt(parts[0]);
            name = parts[1];
        } else if (parts.length == 2 && isInt(parts[1])) {
            amount = Integer.parseInt(parts[1]);
            name = parts[0];
        } else {
            throw new IllegalArgumentException("can't read item '" + spec + "' (use e.g. \"24 emerald\")");
        }
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("unknown item '" + name + "'");
        }
        if (amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("amount for " + name + " must be 1-" + material.getMaxStackSize() + ", got " + amount);
        }
        return new ItemStack(material, amount);
    }

    /** Parses "mending", "efficiency 5" or "minecraft:efficiency 5" into an enchanted book. */
    static ItemStack book(String spec) {
        String[] parts = spec.trim().split("\\s+");
        if (parts.length < 1 || parts.length > 2) throw new IllegalArgumentException("can't read book '" + spec + "'");
        int level = 1;
        if (parts.length == 2) {
            if (!isInt(parts[1])) throw new IllegalArgumentException("book level must be a number in '" + spec + "'");
            level = Integer.parseInt(parts[1]);
        }
        if (level < 1 || level > 255) throw new IllegalArgumentException("book level must be 1-255, got " + level);

        NamespacedKey key = NamespacedKey.fromString(parts[0].toLowerCase(Locale.ROOT));
        Enchantment enchantment = key == null ? null
                : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
        if (enchantment == null) throw new IllegalArgumentException("unknown enchantment '" + parts[0] + "'");

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(enchantment, level, true);
        book.setItemMeta(meta);
        return book;
    }

    private static boolean isInt(String s) {
        return s.matches("\\d{1,4}");
    }
}
