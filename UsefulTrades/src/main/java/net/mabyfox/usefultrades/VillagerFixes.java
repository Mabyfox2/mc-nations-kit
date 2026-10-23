package net.mabyfox.usefultrades;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps villagers friendly and their prices steady.
 *
 * steady-prices: InfiniteVillagerTrading gives every trade endless uses (2147483647). Vanilla's "demand"
 * number then overflows when the villager restocks, swinging between about -2 billion and +80..230, and
 * at +80 a trade costs 5x to 40x its base price (most hit the 64 cap). Trades always show their base price instead.
 *
 * no-grudges (off unless turned on): hitting or killing a villager doesn't make villagers raise prices or turn iron golems on
 * anyone. The bad gossip is wiped from the villagers nearby one tick later, and old grudges are wiped as
 * villagers load. Good gossip (the zombie-cure discount, trading) stays.
 */
final class VillagerFixes implements Listener {

    private static final ReputationType[] GRUDGES = {ReputationType.MINOR_NEGATIVE, ReputationType.MAJOR_NEGATIVE};
    /** Villagers that saw a villager get hurt or killed are within this many blocks of it. */
    private static final double WITNESS_RANGE = 32;

    private final Plugin plugin;
    private boolean steadyPrices = true;
    private boolean noGrudges = false;

    VillagerFixes(Plugin plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection s) {
        steadyPrices = s == null || s.getBoolean("steady-prices", true);
        noGrudges = s != null && s.getBoolean("no-grudges", false);
        if (!noGrudges) return;
        for (World world : Bukkit.getWorlds()) {
            for (Villager v : world.getEntitiesByClass(Villager.class)) forgive(v);
        }
    }

    // Runs after InfiniteVillagerTrading (NORMAL), and before the trade list is sent to the player.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTradeScreenOpen(InventoryOpenEvent event) {
        if (steadyPrices && event.getInventory().getHolder() instanceof AbstractVillager merchant) steady(merchant);
    }

    /** Sets every trade's demand back to 0, so it costs its base price. Returns how many changed. */
    static int steady(AbstractVillager merchant) {
        int changed = 0;
        for (MerchantRecipe recipe : merchant.getRecipes()) { // live view: edits apply to the villager
            if (recipe.getDemand() != 0) {
                recipe.setDemand(0);
                changed++;
            }
        }
        return changed;
    }

    // Before the villager works out its prices for this player.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (noGrudges && event.getRightClicked() instanceof Villager v) forgive(v);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (noGrudges && event.getEntity() instanceof Villager) forgiveAround(event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (noGrudges && event.getEntity() instanceof Villager) forgiveAround(event.getEntity().getLocation());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!noGrudges) return;
        for (Entity e : event.getEntities()) {
            if (e instanceof Villager v) forgive(v);
        }
    }

    /** Vanilla adds the gossip right after the damage event, so wipe it on the next tick. */
    private void forgiveAround(Location at) {
        Location where = at.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!where.isWorldLoaded()) return;
            for (Villager v : where.getNearbyEntitiesByType(Villager.class, WITNESS_RANGE)) forgive(v);
        });
    }

    /** Removes the villager's bad gossip about everyone. Returns true if it had any. */
    static boolean forgive(Villager v) {
        boolean changed = false;
        for (Map.Entry<UUID, Reputation> e : v.getReputations().entrySet()) {
            Reputation rep = e.getValue();
            boolean bad = false;
            for (ReputationType type : GRUDGES) {
                if (rep.getReputation(type) != 0) {
                    rep.setReputation(type, 0);
                    bad = true;
                }
            }
            if (bad) {
                v.setReputation(e.getKey(), rep);
                changed = true;
            }
        }
        return changed;
    }

    int selfTest(CommandSender sender, Location at) {
        int failures = 0;
        Villager v = at.getWorld().spawn(at, Villager.class, s -> {
            s.setAI(false);
            s.setSilent(true);
            s.setInvulnerable(true);
            s.setPersistent(false);
        });
        try {
            // A grudge (4 hits, 1 witnessed kill) plus a zombie-cure discount, about one player.
            UUID player = UUID.randomUUID();
            Reputation rep = new Reputation();
            rep.setReputation(ReputationType.MINOR_NEGATIVE, 100);
            rep.setReputation(ReputationType.MAJOR_NEGATIVE, 25);
            rep.setReputation(ReputationType.MAJOR_POSITIVE, 20);
            v.setReputation(player, rep);
            forgive(v);
            Reputation after = v.getReputation(player);
            int minor = after.getReputation(ReputationType.MINOR_NEGATIVE);
            int major = after.getReputation(ReputationType.MAJOR_NEGATIVE);
            int cure = after.getReputation(ReputationType.MAJOR_POSITIVE);
            boolean ok = minor == 0 && major == 0 && cure == 20;
            sender.sendMessage("[selftest] villager grudge: hits " + minor + ", kill " + major + ", cure discount " + cure
                    + " (want 0, 0, 20) -> " + (ok ? "OK" : "FAIL"));
            if (!ok) failures++;

            // A trade whose demand overflowed to +84: 4 iron would cost 20; after steady() it's back to 4.
            MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD), 0, Integer.MAX_VALUE, true, 1, 0.05f);
            recipe.setIngredients(List.of(new ItemStack(Material.IRON_INGOT, 4)));
            recipe.setDemand(84);
            v.setRecipes(List.of(recipe));
            int changed = steady(v);
            int demand = v.getRecipes().getFirst().getDemand();
            ok = changed == 1 && demand == 0;
            sender.sendMessage("[selftest] steady prices: demand 84 -> " + demand + " (want 0) -> " + (ok ? "OK" : "FAIL"));
            if (!ok) failures++;
        } finally {
            v.remove();
        }
        return failures;
    }
}
