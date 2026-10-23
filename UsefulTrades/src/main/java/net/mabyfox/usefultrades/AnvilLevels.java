package net.mabyfox.usefultrades;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Anvils combine enchantments past vanilla's max level: two of the same level make the next level
 * (Fortune 3 + Fortune 3 = Fortune 4), up to "max-level". Enchantments that only have one level
 * (Mending, Silk Touch...) stay at 1. The anvil never says "Too Expensive": the cost tops out at "max-cost".
 */
final class AnvilLevels implements Listener {

    /** Vanilla hides the result once the cost reaches this; we raise it and cap the cost ourselves. */
    private static final int NO_LIMIT = 1_000_000;

    private int maxLevel = 10;
    private int maxCost = 39;
    private final Map<String, Integer> caps = new HashMap<>();

    record Upgrade(ItemStack result, int extraCost) {}

    void load(ConfigurationSection s, Logger log) {
        caps.clear();
        if (s == null) {
            maxLevel = 10;
            maxCost = 39;
            caps.put("quick_charge", 4);
            caps.put("lure", 5);
            return;
        }
        maxLevel = Math.max(1, Math.min(255, s.getInt("max-level", 10)));
        maxCost = Math.max(1, Math.min(39, s.getInt("max-cost", 39)));
        ConfigurationSection c = s.getConfigurationSection("level-caps");
        if (c != null) {
            for (String key : c.getKeys(false)) {
                String name = key.toLowerCase(Locale.ROOT);
                NamespacedKey id = NamespacedKey.fromString(name);
                if (id == null || RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(id) == null) {
                    log.warning("anvil.level-caps: unknown enchantment '" + key + "'");
                }
                caps.put(id == null ? name : id.getKey(), Math.max(1, c.getInt(key)));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getView() instanceof AnvilView view) view.setMaximumRepairCost(NO_LIMIT);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareAnvilEvent event) {
        AnvilView view = event.getView();
        view.setMaximumRepairCost(NO_LIMIT);
        AnvilInventory inv = event.getInventory();
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) return;
        Upgrade up = upgrade(inv.getFirstItem(), inv.getSecondItem(), result);
        if (up != null) {
            event.setResult(up.result());
            view.setRepairCost(view.getRepairCost() + up.extraCost());
        }
        if (view.getRepairCost() > maxCost) view.setRepairCost(maxCost);
    }

    /**
     * Raises the levels vanilla capped in {@code result} (made from left + right).
     * Returns null when nothing changes.
     */
    Upgrade upgrade(ItemStack left, ItemStack right, ItemStack result) {
        if (left == null || right == null || result == null) return null;
        Map<Enchantment, Integer> l = enchants(left);
        Map<Enchantment, Integer> r = enchants(right);
        Map<Enchantment, Integer> res = enchants(result);
        boolean rightIsBook = right.getType() == Material.ENCHANTED_BOOK;
        ItemStack out = result.clone();
        int extraCost = 0;
        for (Map.Entry<Enchantment, Integer> e : r.entrySet()) {
            Enchantment ench = e.getKey();
            Integer have = res.get(ench);
            if (have == null || ench.getMaxLevel() <= 1) continue; // not applied by vanilla, or has no levels
            int lv = l.getOrDefault(ench, 0);
            int rv = e.getValue();
            int want = lv == rv ? lv + 1 : Math.max(lv, rv);
            int cap = Math.max(caps.getOrDefault(ench.getKey().getKey(), maxLevel), Math.max(lv, rv));
            want = Math.min(want, cap);
            if (want <= have) continue;
            setLevel(out, ench, want);
            extraCost += (want - have) * Math.max(1, rightIsBook ? ench.getAnvilCost() / 2 : ench.getAnvilCost());
        }
        return extraCost == 0 ? null : new Upgrade(out, extraCost);
    }

    private static Map<Enchantment, Integer> enchants(ItemStack item) {
        if (item == null || item.getType().isAir()) return Map.of();
        if (item.getItemMeta() instanceof EnchantmentStorageMeta meta) return meta.getStoredEnchants();
        return item.getEnchantments();
    }

    private static void setLevel(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta book) {
            book.removeStoredEnchant(ench);
            book.addStoredEnchant(ench, level, true);
        } else {
            meta.removeEnchant(ench);
            meta.addEnchant(ench, level, true);
        }
        item.setItemMeta(meta);
    }

    int selfTest(CommandSender sender) {
        int failures = 0;
        // Two Fortune 3 books: vanilla gives Fortune 3, we give Fortune 4.
        failures += check(sender, "fortune 3 + fortune 3 book", level(upgrade(
                TradeDef.book("fortune 3"), TradeDef.book("fortune 3"), TradeDef.book("fortune 3"))), 4);
        // Two Fortune 10 books stay at the max-level cap (vanilla would drop it to 3).
        failures += check(sender, "fortune " + maxLevel + " + fortune " + maxLevel + " book", level(upgrade(
                TradeDef.book("fortune " + maxLevel), TradeDef.book("fortune " + maxLevel), TradeDef.book("fortune 3"))), maxLevel);
        // At the Fortune cap, two books stay there (Fortune 3 + Fortune 3 still works below it).
        int fc = caps.getOrDefault("fortune", maxLevel);
        failures += check(sender, "fortune " + fc + " + fortune " + fc + " book (cap " + fc + ")", level(upgrade(
                TradeDef.book("fortune " + fc), TradeDef.book("fortune " + fc), TradeDef.book("fortune 3"))), fc > 3 ? fc : 0);
        // Mending has no levels: nothing changes.
        failures += check(sender, "mending + mending book", level(upgrade(
                TradeDef.book("mending"), TradeDef.book("mending"), TradeDef.book("mending"))), 0);
        // A Fortune 3 pickaxe + a Fortune 3 book: Fortune 4 pickaxe.
        ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
        Enchantment fortune = TradeDef.book("fortune 3").getItemMeta() instanceof EnchantmentStorageMeta m
                ? m.getStoredEnchants().keySet().iterator().next() : null;
        pick.addUnsafeEnchantment(fortune, 3);
        Upgrade up = upgrade(pick, TradeDef.book("fortune 3"), pick.clone());
        failures += check(sender, "fortune 3 pickaxe + fortune 3 book",
                up == null ? 0 : up.result().getEnchantmentLevel(fortune), 4);
        return failures;
    }

    private static int level(Upgrade up) {
        if (up == null) return 0;
        Map<Enchantment, Integer> e = enchants(up.result());
        return e.isEmpty() ? 0 : e.values().iterator().next();
    }

    private static int check(CommandSender sender, String what, int got, int want) {
        boolean ok = got == want;
        sender.sendMessage("[selftest] anvil, " + what + ": " + (got == 0 ? "no change" : "level " + got)
                + " (want " + (want == 0 ? "no change" : "level " + want) + ") -> " + (ok ? "OK" : "FAIL"));
        return ok ? 0 : 1;
    }
}
