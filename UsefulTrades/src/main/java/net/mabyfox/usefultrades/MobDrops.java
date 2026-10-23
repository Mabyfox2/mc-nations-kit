package net.mabyfox.usefultrades;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Mobs killed by a player drop "multiplier" times their normal loot, so nobody has to farm thousands of animals.
 *
 * Only loot is multiplied: anything the mob was wearing, holding or carrying (a zombie that picked up
 * diamonds, a donkey's chest, a piglin's gold) drops once, so it can't be used to duplicate items.
 * Unstackable drops (armor, tools, totems) and the "never-multiply" list also drop once. XP is untouched.
 */
final class MobDrops implements Listener {

    private int multiplier = 3;
    private boolean onlyPlayerKills = true;
    private final Set<Material> never = new HashSet<>();

    void load(ConfigurationSection s, Logger log) {
        never.clear();
        if (s == null) {
            multiplier = 3;
            onlyPlayerKills = true;
            never.add(Material.NETHER_STAR);
            return;
        }
        multiplier = Math.max(1, Math.min(10, s.getInt("multiplier", 3)));
        onlyPlayerKills = s.getBoolean("only-player-kills", true);
        for (String name : s.getStringList("never-multiply")) {
            Material m = Material.matchMaterial(name.trim().toLowerCase(Locale.ROOT));
            if (m == null) log.warning("mob-drops.never-multiply: unknown item '" + name + "'");
            else never.add(m);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (multiplier <= 1) return;
        LivingEntity mob = event.getEntity();
        if (mob instanceof Player || mob instanceof ArmorStand) return;
        if (onlyPlayerKills && mob.getKiller() == null) return;
        event.getDrops().addAll(extraDrops(event.getDrops(), carriedBy(mob)));
    }

    /** Item types the mob had on it (equipment, inventory, a donkey's chest), which must not be multiplied. */
    private static Set<Material> carriedBy(LivingEntity mob) {
        Set<Material> carried = new HashSet<>();
        EntityEquipment eq = mob.getEquipment();
        if (eq != null) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    ItemStack item = eq.getItem(slot);
                    if (item != null && !item.getType().isAir()) carried.add(item.getType());
                } catch (IllegalArgumentException ignored) {
                    // this mob doesn't have that slot
                }
            }
        }
        if (mob instanceof InventoryHolder holder) {
            for (ItemStack item : holder.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) carried.add(item.getType());
            }
        }
        if (mob instanceof ChestedHorse horse && horse.isCarryingChest()) carried.add(Material.CHEST);
        return carried;
    }

    /** The extra stacks to add for these drops. */
    List<ItemStack> extraDrops(List<ItemStack> drops, Set<Material> carried) {
        List<ItemStack> extra = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            if (drop.getMaxStackSize() <= 1 || never.contains(drop.getType()) || carried.contains(drop.getType())) continue;
            int add = drop.getAmount() * (multiplier - 1);
            while (add > 0) {
                ItemStack stack = drop.clone();
                stack.setAmount(Math.min(add, drop.getMaxStackSize()));
                add -= stack.getAmount();
                extra.add(stack);
            }
        }
        return extra;
    }

    int selfTest(CommandSender sender) {
        int failures = 0;
        // A cow: 2 leather + 1 beef -> 3x each.
        List<ItemStack> cow = List.of(new ItemStack(Material.LEATHER, 2), new ItemStack(Material.BEEF, 1));
        failures += check(sender, "cow drops", count(extraDrops(cow, Set.of())), (multiplier - 1) * 3);
        // A zombie holding 5 diamonds it picked up: the diamonds drop once, its rotten flesh is multiplied.
        List<ItemStack> zombie = List.of(new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.ROTTEN_FLESH, 1));
        failures += check(sender, "zombie holding diamonds", count(extraDrops(zombie, Set.of(Material.DIAMOND))), multiplier - 1);
        // Unstackable gear never multiplies.
        failures += check(sender, "dropped sword", count(extraDrops(List.of(new ItemStack(Material.IRON_SWORD)), Set.of())), 0);
        return failures;
    }

    private static int count(List<ItemStack> items) {
        return items.stream().mapToInt(ItemStack::getAmount).sum();
    }

    private static int check(CommandSender sender, String what, int got, int want) {
        boolean ok = got == want;
        sender.sendMessage("[selftest] mob drops, " + what + ": " + got + " extra (want " + want + ") -> " + (ok ? "OK" : "FAIL"));
        return ok ? 0 : 1;
    }
}
