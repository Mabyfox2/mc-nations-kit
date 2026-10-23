package net.mabyfox.usefultrades;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Adds extra, guaranteed trades to villagers on top of their vanilla trades.
 *
 * Trades are synced whenever a player opens a villager's trade screen: any trade this
 * plugin added before is swapped for the current config version, and trades for every
 * level the villager has reached are added. So price edits apply to existing villagers,
 * and villagers that were already levelled up before the plugin was installed get the
 * trades too. Runs before InfiniteVillagerTrading so those trades become unlimited as well.
 *
 * Wandering traders use the "wandering_trader" section and get all of its trades (they have no levels).
 * Explorer map trades are drawn once per villager, the first time the trade appears, and then kept.
 *
 * 1.2.0: every trade costs "cost-multiplier" times as much (vanilla and ours), and the plugin also
 * carries the server's other "less grinding" tweaks: {@link MobDrops}, {@link AnvilLevels} and {@link MoneyBar}.
 * 1.2.2: {@link VillagerFixes} (steady prices, no grudges) and enchant level caps.
 */
public final class UsefulTrades extends JavaPlugin implements Listener {

    private static final String[] LEVEL_NAMES = {"", "Novice", "Apprentice", "Journeyman", "Expert", "Master"};
    static final String WANDERING_TRADER = "wandering_trader";
    /** Stored instead of a signature when a map trade found no structure, so we don't search again on every open. */
    private static final String MISSING = "missing:";

    private final Map<String, List<TradeDef>> trades = new HashMap<>();
    private NamespacedKey addedKey;
    /** Hashes of the vanilla trades on a villager whose cost was already multiplied. */
    private NamespacedKey scaledKey;
    private int costMultiplier = 2;

    private MobDrops mobDrops;
    private AnvilLevels anvilLevels;
    private MoneyBar moneyBar;
    private VillagerFixes villagerFixes;

    @Override
    public void onEnable() {
        addedKey = new NamespacedKey(this, "added_trades");
        scaledKey = new NamespacedKey(this, "scaled_trades");
        saveDefaultConfig();
        mobDrops = new MobDrops();
        anvilLevels = new AnvilLevels();
        moneyBar = new MoneyBar(this);
        villagerFixes = new VillagerFixes(this);
        loadTrades();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(mobDrops, this);
        getServer().getPluginManager().registerEvents(anvilLevels, this);
        getServer().getPluginManager().registerEvents(moneyBar, this);
        getServer().getPluginManager().registerEvents(villagerFixes, this);
        moneyBar.start();
    }

    // ---------------------------------------------------------------- config

    private int loadTrades() {
        reloadConfig();
        costMultiplier = Math.max(1, Math.min(10, getConfig().getInt("cost-multiplier", 2)));
        mobDrops.load(getConfig().getConfigurationSection("mob-drops"), getLogger());
        anvilLevels.load(getConfig().getConfigurationSection("anvil"), getLogger());
        moneyBar.load(getConfig().getConfigurationSection("money-bar"));
        villagerFixes.load(getConfig().getConfigurationSection("villagers"));
        trades.clear();
        int count = 0;
        ConfigurationSection root = getConfig().getConfigurationSection("trades");
        if (root == null) {
            getLogger().warning("config.yml has no 'trades' section, nothing to add.");
            return 0;
        }
        for (String prof : root.getKeys(false)) {
            String profKey = prof.toLowerCase(Locale.ROOT);
            NamespacedKey key = NamespacedKey.fromString(profKey);
            if (key == null || (!key.getKey().equals(WANDERING_TRADER) && Registry.VILLAGER_PROFESSION.get(key) == null)) {
                getLogger().warning("Unknown villager profession '" + prof + "' in config.yml, skipping it.");
                continue;
            }
            ConfigurationSection ps = root.getConfigurationSection(prof);
            if (ps == null) continue;
            List<TradeDef> list = new ArrayList<>();
            for (String id : ps.getKeys(false)) {
                try {
                    list.add(TradeDef.parse(key.getKey() + "/" + id, ps.getConfigurationSection(id), costMultiplier));
                    count++;
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Skipping trade " + prof + "." + id + ": " + ex.getMessage());
                }
            }
            list.sort((a, b) -> Integer.compare(a.level(), b.level())); // stable: keeps file order within a level
            trades.put(key.getKey(), List.copyOf(list));
        }
        getLogger().info("Loaded " + count + " extra trades for " + trades.size() + " professions; trades cost x" + costMultiplier + ".");
        return count;
    }

    // ---------------------------------------------------------------- syncing

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTradeScreenOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof AbstractVillager merchant) {
            sync(merchant);
        }
    }

    /** The config section for this villager's job, or "wandering_trader". */
    private static String kindOf(AbstractVillager merchant) {
        if (merchant instanceof WanderingTrader) return WANDERING_TRADER;
        if (merchant instanceof Villager villager) return villager.getProfession().getKey().getKey();
        return null;
    }

    /** Makes the villager's extra trades match the config for its profession and level. */
    void sync(AbstractVillager merchant) {
        String kind = kindOf(merchant);
        if (kind == null) return;
        List<TradeDef> defs = trades.getOrDefault(kind, List.of());
        PersistentDataContainer pdc = merchant.getPersistentDataContainer();
        Map<String, String> before = decode(pdc.get(addedKey, PersistentDataType.STRING));
        scaleVanillaTrades(merchant, before);
        if (defs.isEmpty() && before.isEmpty()) return;

        int level = merchant instanceof Villager villager ? villager.getVillagerLevel() : 5;
        List<MerchantRecipe> recipes = new ArrayList<>(merchant.getRecipes());

        // Take out the trades we added last time. Ours sit at the end, so search backwards.
        Map<String, MerchantRecipe> old = new HashMap<>();
        for (Map.Entry<String, String> added : before.entrySet()) {
            if (added.getValue().startsWith(MISSING)) continue;
            for (int i = recipes.size() - 1; i >= 0; i--) {
                if (signature(recipes.get(i)).equals(added.getValue())) {
                    old.put(added.getKey(), recipes.remove(i));
                    break;
                }
            }
        }

        Map<String, String> after = new LinkedHashMap<>();
        List<MerchantRecipe> ours = new ArrayList<>();
        for (TradeDef def : defs) {
            if (def.level() > level) continue;
            MerchantRecipe recipe = def.toRecipe();
            String sig = signature(recipe);
            MerchantRecipe previous = old.get(def.id());
            if (def.isMap()) {
                if (previous != null && itemSignature(previous.getResult()).equals(itemSignature(recipe.getResult()))) {
                    recipe = def.toRecipe(previous.getResult()); // keep the map it already sells
                } else if (before.getOrDefault(def.id(), "").equals(MISSING + sig)) {
                    after.put(def.id(), MISSING + sig);
                    continue;
                } else {
                    ItemStack map = ExplorerMaps.create(def.map(), def.mapIcon(), merchant.getLocation());
                    if (map == null) {
                        Location at = merchant.getLocation();
                        getLogger().warning("No " + def.map() + " within " + ExplorerMaps.SEARCH_RADIUS + " chunks of the "
                                + kind + " at " + at.getBlockX() + ", " + at.getBlockZ() + "; it won't sell that map.");
                        after.put(def.id(), MISSING + sig);
                        continue;
                    }
                    recipe = def.toRecipe(map);
                }
            }
            recipe.setUses(Math.min(previous == null ? 0 : previous.getUses(), recipe.getMaxUses()));
            ours.add(recipe);
            after.put(def.id(), sig);
        }

        // Nothing changed and every trade is still there: leave the villager alone.
        long stillThere = before.values().stream().filter(sig -> !sig.startsWith(MISSING)).count();
        if (after.equals(before) && old.size() == stillThere) return;

        recipes.addAll(ours);
        merchant.setRecipes(recipes);
        if (after.isEmpty()) {
            pdc.remove(addedKey);
        } else {
            pdc.set(addedKey, PersistentDataType.STRING, encode(after));
        }
    }

    // ---------------------------------------------------------------- trade prices

    /** A new vanilla trade (villager levelled up or got a job): multiply its cost right away. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNewTrade(VillagerAcquireTradeEvent event) {
        if (costMultiplier <= 1) return;
        MerchantRecipe recipe = event.getRecipe();
        scale(recipe, costMultiplier);
        event.setRecipe(recipe);
        PersistentDataContainer pdc = event.getEntity().getPersistentDataContainer();
        Map<String, Integer> scaled = decodeScaled(pdc.get(scaledKey, PersistentDataType.STRING));
        scaled.put(hash(recipe), costMultiplier);
        pdc.set(scaledKey, PersistentDataType.STRING, encodeScaled(scaled));
    }

    /**
     * Brings every vanilla trade to "cost-multiplier" times its normal cost: multiplies trades that aren't yet
     * (villagers from before 1.2.0, or trades that appeared without the event above), and undoes an older
     * multiplier when the setting changed (1.2.1: x2 back to x1). Our own trades get their price from config.
     * The villager remembers which trades are multiplied, and by how much, as "hash@multiplier" entries.
     */
    private void scaleVanillaTrades(AbstractVillager merchant, Map<String, String> ours) {
        PersistentDataContainer pdc = merchant.getPersistentDataContainer();
        if (costMultiplier <= 1 && !pdc.has(scaledKey, PersistentDataType.STRING)) return;
        // Read the trades first: a villager that never traded makes its trades now, and onNewTrade records them.
        List<MerchantRecipe> recipes = new ArrayList<>(merchant.getRecipes());
        String stored = pdc.get(scaledKey, PersistentDataType.STRING);
        Map<String, Integer> scaled = decodeScaled(stored);
        Set<String> oursSigs = new HashSet<>(ours.values());
        Map<String, Integer> now = new LinkedHashMap<>();
        boolean changed = false;
        for (MerchantRecipe recipe : recipes) {
            if (oursSigs.contains(signature(recipe))) continue;
            int applied = scaled.getOrDefault(hash(recipe), 1);
            if (applied != costMultiplier) {
                if (applied > 1) unscale(recipe, applied);
                if (costMultiplier > 1) scale(recipe, costMultiplier);
                changed = true;
            }
            if (costMultiplier > 1) now.put(hash(recipe), costMultiplier);
        }
        if (changed) merchant.setRecipes(recipes);
        if (now.isEmpty()) {
            if (stored != null) pdc.remove(scaledKey);
        } else {
            String joined = encodeScaled(now);
            if (!joined.equals(stored)) pdc.set(scaledKey, PersistentDataType.STRING, joined);
        }
    }

    /** Undoes {@link #scale}. A cost that was capped at a full stack comes back a little cheaper than it was. */
    static void unscale(MerchantRecipe recipe, int multiplier) {
        List<ItemStack> costs = new ArrayList<>();
        for (ItemStack cost : recipe.getIngredients()) {
            if (cost == null || cost.getType().isAir()) {
                costs.add(cost);
                continue;
            }
            ItemStack copy = cost.clone();
            copy.setAmount(Math.max(1, (copy.getAmount() + multiplier - 1) / multiplier));
            costs.add(copy);
        }
        recipe.setIngredients(costs);
    }

    /** "hash@multiplier,..."; a bare hash is from 1.2.0, which always doubled. */
    private static Map<String, Integer> decodeScaled(String stored) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) return map;
        for (String entry : stored.split(",")) {
            int at = entry.indexOf('@');
            if (at < 0) {
                map.put(entry, 2);
                continue;
            }
            try {
                map.put(entry.substring(0, at), Integer.parseInt(entry.substring(at + 1)));
            } catch (NumberFormatException ignored) {
                // unreadable entry: treat that trade as not multiplied
            }
        }
        return map;
    }

    private static String encodeScaled(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((h, m) -> sb.append(sb.isEmpty() ? "" : ",").append(h).append('@').append(m));
        return sb.toString();
    }

    /** Multiplies both costs of a trade, never past a full stack. */
    static void scale(MerchantRecipe recipe, int multiplier) {
        List<ItemStack> costs = new ArrayList<>();
        for (ItemStack cost : recipe.getIngredients()) costs.add(times(cost, multiplier));
        recipe.setIngredients(costs);
    }

    static ItemStack times(ItemStack item, int multiplier) {
        if (item == null || item.getType().isAir()) return item;
        ItemStack copy = item.clone();
        copy.setAmount(Math.min(copy.getMaxStackSize(), copy.getAmount() * multiplier));
        return copy;
    }

    private static String hash(MerchantRecipe recipe) {
        return Integer.toHexString(signature(recipe).hashCode());
    }

    /**
     * Identifies a trade by what it sells and costs. Leaves out uses and max uses on purpose:
     * InfiniteVillagerTrading rewrites those every time the screen opens.
     */
    static String signature(MerchantRecipe recipe) {
        StringBuilder sb = new StringBuilder(itemSignature(recipe.getResult()));
        for (ItemStack ingredient : recipe.getIngredients()) {
            sb.append(" | ").append(itemSignature(ingredient));
        }
        sb.append(" | xp=").append(recipe.getVillagerExperience())
          .append(" | pm=").append(recipe.getPriceMultiplier());
        return sb.toString();
    }

    private static String itemSignature(ItemStack item) {
        if (item == null || item.getType().isAir()) return "air";
        StringBuilder sb = new StringBuilder(item.getType().getKey().asString()).append('x').append(item.getAmount());
        String map = ExplorerMaps.structureOf(item);
        if (map != null) sb.append("+map:").append(map);
        if (item.hasItemMeta() && item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            Map<String, Integer> sorted = new TreeMap<>();
            for (Map.Entry<Enchantment, Integer> e : meta.getStoredEnchants().entrySet()) {
                sorted.put(e.getKey().getKey().asString(), e.getValue());
            }
            sorted.forEach((k, v) -> sb.append('+').append(k).append(':').append(v));
        }
        return sb.toString();
    }

    private static Map<String, String> decode(String stored) {
        Map<String, String> map = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) return map;
        for (String line : stored.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) map.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return map;
    }

    private static String encode(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((id, sig) -> sb.append(id).append('\t').append(sig).append('\n'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                int count = loadTrades();
                sender.sendMessage("UsefulTrades reloaded: " + count + " extra trades, trades cost x" + costMultiplier
                        + ". Villagers update the next time someone opens their trades.");
            }
            case "list" -> {
                if (trades.isEmpty()) sender.sendMessage("UsefulTrades: no extra trades configured.");
                trades.forEach((prof, defs) -> {
                    sender.sendMessage(prof + ":");
                    for (TradeDef d : defs) {
                        String when = prof.equals(WANDERING_TRADER) ? "Always" : LEVEL_NAMES[d.level()];
                        sender.sendMessage("  " + when + ": " + describe(d.toRecipe()));
                    }
                });
            }
            case "selftest" -> selfTest(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Spawns one throwaway villager per configured profession (or a wandering trader) at world spawn,
     * walks it through levels 1-5, checks the trades after each step, then removes it.
     * Safe to run on a live server, but map trades search for real structures, which can take a moment.
     */
    private void selfTest(CommandSender sender) {
        World world = getServer().getWorlds().getFirst();
        Location at = world.getSpawnLocation().clone().add(0.5, 0, 0.5);
        at.getChunk().load(true);
        int failures = 0;

        for (Map.Entry<String, List<TradeDef>> entry : trades.entrySet()) {
            boolean wandering = entry.getKey().equals(WANDERING_TRADER);
            Class<? extends AbstractVillager> type = wandering ? WanderingTrader.class : Villager.class;
            AbstractVillager villager = world.spawn(at, type, v -> {
                v.setAI(false);
                v.setSilent(true);
                v.setInvulnerable(true);
                v.setPersistent(false);
            });
            try {
                if (villager instanceof Villager v) {
                    v.setProfession(Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(entry.getKey())));
                }
                int vanillaCount = villager.getRecipes().size();
                for (int level = wandering ? 5 : 1; level <= 5; level++) {
                    if (villager instanceof Villager v) v.setVillagerLevel(level);
                    final int lvl = level;
                    long expected = entry.getValue().stream().filter(d -> d.level() <= lvl).count();

                    sync(villager);
                    int afterFirst = villager.getRecipes().size();
                    List<String> costsFirst = villager.getRecipes().stream().map(UsefulTrades::costs).toList();

                    // Pretend InfiniteVillagerTrading ran, then sync again: must not duplicate.
                    List<MerchantRecipe> tampered = new ArrayList<>();
                    for (MerchantRecipe r : villager.getRecipes()) {
                        r.setMaxUses(Integer.MAX_VALUE);
                        r.setUses(0);
                        tampered.add(r);
                    }
                    villager.setRecipes(tampered);
                    sync(villager);
                    int afterSecond = villager.getRecipes().size();
                    List<String> costsSecond = villager.getRecipes().stream().map(UsefulTrades::costs).toList();

                    // Vanilla trades must be multiplied exactly once: every cost a multiple (or a full stack), and stable.
                    boolean costsOk = costsFirst.equals(costsSecond);
                    List<MerchantRecipe> now = villager.getRecipes();
                    for (int i = 0; i < Math.min(vanillaCount, now.size()); i++) {
                        for (ItemStack cost : now.get(i).getIngredients()) {
                            if (cost == null || cost.getType().isAir()) continue;
                            if (cost.getAmount() % costMultiplier != 0 && cost.getAmount() != cost.getMaxStackSize()) costsOk = false;
                        }
                    }

                    boolean ok = afterFirst == vanillaCount + expected && afterSecond == afterFirst && costsOk;
                    if (!ok) failures++;
                    sender.sendMessage(String.format("[selftest] %s %s: %d vanilla + %d extra = %d, after re-sync %d, costs x%d %s -> %s",
                            entry.getKey(), wandering ? "" : LEVEL_NAMES[level], vanillaCount, expected, afterFirst, afterSecond,
                            costMultiplier, costsOk ? "once" : "WRONG", ok ? "OK" : "FAIL"));
                }
                // Pretend the owner edited every price in config.yml: old versions must be swapped, not duplicated.
                List<TradeDef> original = entry.getValue();
                List<TradeDef> edited = new ArrayList<>();
                for (TradeDef d : original) {
                    ItemStack newPrice = d.price().clone();
                    newPrice.setAmount(newPrice.getAmount() < newPrice.getMaxStackSize() ? newPrice.getAmount() + 1 : newPrice.getAmount() - 1);
                    edited.add(new TradeDef(d.id(), d.level(), d.result(), newPrice, d.price2(), d.maxUses(), d.villagerXp(), d.playerXp(), d.priceMultiplier(), d.map(), d.mapIcon()));
                }
                int beforeEdit = villager.getRecipes().size();
                trades.put(entry.getKey(), List.copyOf(edited));
                try {
                    sync(villager);
                    List<String> live = villager.getRecipes().stream().map(UsefulTrades::signature).toList();
                    boolean swapped = villager.getRecipes().size() == beforeEdit
                            && edited.stream().allMatch(d -> live.contains(signature(d.toRecipe())))
                            && original.stream().noneMatch(d -> live.contains(signature(d.toRecipe())));
                    if (!swapped) failures++;
                    sender.sendMessage("[selftest] " + entry.getKey() + " price edit: " + beforeEdit + " -> "
                            + villager.getRecipes().size() + " trades, old prices replaced -> " + (swapped ? "OK" : "FAIL"));
                } finally {
                    trades.put(entry.getKey(), original);
                    sync(villager);
                }
                // Change the cost multiplier up by one and back (like x2 -> x1 in 1.2.1): vanilla costs follow and return.
                int m0 = costMultiplier;
                // getRecipes() is a live view, so copy the amounts out.
                List<int[]> start = amounts(villager.getRecipes());
                costMultiplier = m0 + 1;
                try {
                    sync(villager);
                } finally {
                    costMultiplier = m0;
                }
                List<int[]> mid = amounts(villager.getRecipes());
                sync(villager);
                List<int[]> end = amounts(villager.getRecipes());
                boolean follows = true;
                for (int i = 0; i < Math.min(vanillaCount, start.size()); i++) {
                    int[] a = start.get(i), b = mid.get(i), c = end.get(i);
                    for (int k = 0; k < a.length; k += 2) {
                        int was = a[k], max = a[k + 1];
                        if (was % m0 != 0 || was / m0 * (m0 + 1) > max) continue; // capped at a full stack: can't come back exactly
                        if (b[k] != was / m0 * (m0 + 1) || c[k] != was) follows = false;
                    }
                }
                if (m0 == 1) {
                    // A villager doubled by 1.2.0 (bare hashes in its PDC) must come back to normal prices.
                    List<MerchantRecipe> doubled = new ArrayList<>(villager.getRecipes());
                    List<String> bare = new ArrayList<>();
                    for (int i = 0; i < Math.min(vanillaCount, doubled.size()); i++) {
                        scale(doubled.get(i), 2);
                        bare.add(hash(doubled.get(i)));
                    }
                    villager.setRecipes(doubled);
                    if (bare.isEmpty()) villager.getPersistentDataContainer().remove(scaledKey);
                    else villager.getPersistentDataContainer().set(scaledKey, PersistentDataType.STRING, String.join(",", bare));
                    sync(villager);
                    List<int[]> back = amounts(villager.getRecipes());
                    for (int i = 0; i < Math.min(vanillaCount, start.size()); i++) {
                        int[] a = start.get(i), b = back.get(i);
                        for (int k = 0; k < a.length; k += 2) {
                            if (a[k] * 2 > a[k + 1]) continue;
                            if (b[k] != a[k]) follows = false;
                        }
                    }
                    if (villager.getPersistentDataContainer().has(scaledKey, PersistentDataType.STRING)) follows = false;
                }
                if (!follows) failures++;
                sender.sendMessage("[selftest] " + entry.getKey() + " multiplier x" + m0 + " -> x" + (m0 + 1) + " -> x" + m0
                        + ": vanilla costs " + (follows ? "followed" : "WRONG") + " -> " + (follows ? "OK" : "FAIL"));
                for (MerchantRecipe r : villager.getRecipes()) {
                    sender.sendMessage("[selftest]   " + entry.getKey() + " trade: " + describe(r));
                }
            } finally {
                villager.remove();
            }
        }
        failures += anvilLevels.selfTest(sender);
        failures += mobDrops.selfTest(sender);
        failures += moneyBar.selfTest(sender);
        failures += villagerFixes.selfTest(sender, at);
        sender.sendMessage(failures == 0 ? "[selftest] ALL OK" : "[selftest] " + failures + " check(s) FAILED");
    }

    /** Per trade: amount and max stack of each cost, as {amount, max, amount, max}. */
    private static List<int[]> amounts(List<MerchantRecipe> recipes) {
        List<int[]> out = new ArrayList<>();
        for (MerchantRecipe r : recipes) {
            List<ItemStack> costs = r.getIngredients();
            int[] a = new int[costs.size() * 2];
            for (int k = 0; k < costs.size(); k++) {
                a[2 * k] = costs.get(k).getAmount();
                a[2 * k + 1] = costs.get(k).getMaxStackSize();
            }
            out.add(a);
        }
        return out;
    }

    private static String costs(MerchantRecipe r) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack cost : r.getIngredients()) sb.append(cost == null ? 0 : cost.getAmount()).append(' ');
        return sb.toString();
    }

    private static String describe(MerchantRecipe r) {
        StringBuilder sb = new StringBuilder();
        List<ItemStack> ingredients = r.getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(describe(ingredients.get(i)));
        }
        return sb.append(" -> ").append(describe(r.getResult())).toString();
    }

    private static String describe(ItemStack item) {
        String text = item.getAmount() + " " + item.getType().getKey().getKey();
        String map = ExplorerMaps.structureOf(item);
        if (map != null) text = ExplorerMaps.name(map);
        if (item.hasItemMeta() && item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            StringBuilder sb = new StringBuilder(text).append(" (");
            meta.getStoredEnchants().forEach((e, lvl) -> sb.append(e.getKey().getKey()).append(' ').append(lvl).append(','));
            sb.setLength(sb.length() - 1);
            text = sb.append(')').toString();
        }
        return text;
    }
}
