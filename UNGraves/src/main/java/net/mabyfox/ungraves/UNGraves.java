package net.mabyfox.ungraves;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Graves for the United Nations of MC With Gang.
 * When keep inventory is off, a dead player's items and XP wait in a grave (a skeleton skull with a name label)
 * instead of dropping and despawning. Only the owner can open it, except during an approved war, when members
 * of the enemy nation can loot it. Does nothing while keep inventory is on.
 * Nations are read from UNBank's data.yml and wars from UNBorders' claims.yml.
 */
public final class UNGraves extends JavaPlugin implements Listener {

    static final String P = "§8[Graves]§r ";
    static final String LABEL_TAG = "ungraves_label";
    static final int MENU_SIZE = 54;

    static final class Grave {
        String id;
        UUID owner;
        String ownerName;
        String world;
        int x, y, z;
        long created;
        int xp;
        UUID label;
        final Map<Integer, ItemStack> slotted = new LinkedHashMap<>();   // inventory slot it was in -> item
        final List<ItemStack> loose = new ArrayList<>();                 // items with no known slot
        UUID viewer;                                                     // who has it open in a menu right now

        boolean isEmpty() { return slotted.isEmpty() && loose.isEmpty(); }
        int count() { return slotted.size() + loose.size(); }
        String key() { return UNGraves.key(world, x, y, z); }
    }

    /** The menu an enemy (or the Secretary-General) loots a grave through. */
    static final class GraveMenu implements InventoryHolder {
        final Grave grave;
        final int[] fromSlot = new int[MENU_SIZE];                       // menu slot -> inventory slot, or -1
        final List<Map.Entry<Integer, ItemStack>> hidden = new ArrayList<>();   // didn't fit in the menu
        Inventory inv;

        GraveMenu(Grave grave) { this.grave = grave; Arrays.fill(fromSlot, -1); }

        @Override
        public Inventory getInventory() { return inv; }
    }

    /** What a looter may do inside the grave menu: only take things out. */
    private static final Set<InventoryAction> TAKE = EnumSet.of(
            InventoryAction.NOTHING, InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME,
            InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.DROP_ALL_SLOT,
            InventoryAction.DROP_ONE_SLOT, InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR,
            InventoryAction.PICKUP_FROM_BUNDLE, InventoryAction.PICKUP_ALL_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE);

    private final Map<String, Grave> graves = new LinkedHashMap<>();    // "world;x;y;z" -> grave
    private final Map<Grave, GraveMenu> menus = new HashMap<>();
    private final Map<UUID, Grave> tellOnRespawn = new HashMap<>();
    private File file;

    private File unbankData, bordersData;
    private long unbankStamp = -1, bordersStamp = -1;
    private final Map<UUID, String> memberOf = new HashMap<>();          // player -> nation key
    private final Map<String, String> nationNames = new HashMap<>();
    private final Set<String> wars = new HashSet<>();                    // "a|b", a < b

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "graves.yml");
        File plugins = getDataFolder().getParentFile();
        unbankData = new File(plugins, "UNBank/data.yml");
        bordersData = new File(plugins, "UNBorders/claims.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(graves.size() + " graves. Graves only appear when keep inventory is off.");
    }

    @Override
    public void onDisable() {
        for (GraveMenu m : new ArrayList<>(menus.values())) {
            menus.remove(m.grave);
            syncMenu(m);
            m.grave.viewer = null;
            for (Object v : new ArrayList<>(m.inv.getViewers())) ((Player) v).closeInventory();
        }
        if (file != null) save();
    }

    // ---------------------------------------------------------------- dying

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory() || e.getDrops().isEmpty()) return;
        Player p = e.getEntity();
        Block spot = findSpot(p.getLocation());
        if (spot == null) {
            getLogger().warning("No room for " + p.getName() + "'s grave, so their items dropped as normal.");
            return;
        }

        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack it : e.getDrops()) if (it != null && !it.isEmpty()) drops.add(it.clone());
        Grave g = new Grave();
        // Remember which slot each item was in, so the owner gets their hotbar and armor back the way it was.
        ItemStack[] contents = p.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack it = contents[slot];
            if (it == null || it.isEmpty()) continue;
            for (int i = 0; i < drops.size(); i++) {
                if (drops.get(i).equals(it)) { g.slotted.put(slot, drops.remove(i)); break; }
            }
        }
        g.loose.addAll(drops);
        if (g.isEmpty()) return;

        e.getDrops().clear();
        g.xp = e.getDroppedExp();
        e.setDroppedExp(0);
        do g.id = UUID.randomUUID().toString().substring(0, 8); while (idTaken(g.id));
        g.owner = p.getUniqueId();
        g.ownerName = p.getName();
        g.created = System.currentTimeMillis();
        place(g, spot);
        graves.put(g.key(), g);
        save();
        tellOnRespawn.put(p.getUniqueId(), g);
        getLogger().info(p.getName() + " died. " + g.count() + " stacks went into a grave at " + where(g) + ".");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Grave g = tellOnRespawn.remove(e.getPlayer().getUniqueId());
        if (g == null || graves.get(g.key()) != g) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> p.sendMessage(P + "§eYour items are safe in a grave at §f" + where(g)
                + "§e. Right-click or punch it to get them back. Only you can open it, unless your nation is at war. §7/graves shows where it is."), 10L);
    }

    private boolean idTaken(String id) {
        for (Grave g : graves.values()) if (g.id.equals(id)) return true;
        return false;
    }

    /** The death spot if it's free, else the nearest free block close by. Void deaths use the ground above, or the world spawn. */
    private Block findSpot(Location death) {
        World w = death.getWorld();
        int min = w.getMinHeight(), max = w.getMaxHeight() - 1;
        int x = death.getBlockX(), y = death.getBlockY(), z = death.getBlockZ();
        if (y < min) {
            Block top = w.getHighestBlockAt(x, z);
            if (top.getY() < min || top.getType().isAir()) {
                Location s = w.getSpawnLocation();
                x = s.getBlockX(); y = s.getBlockY(); z = s.getBlockZ();
            } else {
                y = top.getY() + 1;
            }
        }
        y = Math.max(min, Math.min(max, y));
        for (int r = 0; r <= 2; r++)
            for (int dy = 0; dy <= 3; dy++)
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r || y + dy > max) continue;
                        Block b = w.getBlockAt(x + dx, y + dy, z + dz);
                        if (free(b)) return b;
                    }
        for (int yy = y; yy <= max; yy++) {
            Block b = w.getBlockAt(x, yy, z);
            if (free(b)) return b;
        }
        return null;
    }

    private boolean free(Block b) {
        return !graves.containsKey(key(b)) && (b.isEmpty() || b.isLiquid() || b.isReplaceable());
    }

    private void place(Grave g, Block spot) {
        g.world = spot.getWorld().getName();
        g.x = spot.getX(); g.y = spot.getY(); g.z = spot.getZ();
        spot.setType(Material.SKELETON_SKULL);
        // A floating name works on Java and Bedrock alike (player heads don't, since skins don't cross over).
        ArmorStand label = spot.getWorld().spawn(spot.getLocation().add(0.5, 0.5, 0.5), ArmorStand.class, a -> {
            a.setMarker(true);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(true);
            a.setCustomName("§f" + g.ownerName + "'s grave");
            a.setCustomNameVisible(true);
            a.addScoreboardTag(LABEL_TAG);
        });
        g.label = label.getUniqueId();
    }

    private void remove(Grave g) {
        graves.remove(g.key());
        World w = Bukkit.getWorld(g.world);
        if (w != null) {
            Block b = w.getBlockAt(g.x, g.y, g.z);
            if (b.getType() == Material.SKELETON_SKULL) b.setType(Material.AIR);
            Entity l = g.label == null ? null : Bukkit.getEntity(g.label);
            if (l != null) l.remove();
            for (ArmorStand a : w.getNearbyEntitiesByType(ArmorStand.class, b.getLocation().add(0.5, 0.5, 0.5), 0.6))
                if (a.getScoreboardTags().contains(LABEL_TAG)) a.remove();
        }
        save();
    }

    // ---------------------------------------------------------------- opening

    // Not ignoreCancelled: UNBorders blocks clicks on other nations' land, but graves follow their own rules.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Grave g = graves.get(key(e.getClickedBlock()));
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) open(e.getPlayer(), g);
    }

    private void open(Player p, Grave g) {
        if (g.viewer != null && !g.viewer.equals(p.getUniqueId())) {
            p.sendMessage(P + "§cSomeone else has this grave open right now.");
            return;
        }
        if (p.getUniqueId().equals(g.owner)) {
            restore(p, g);
            return;
        }
        String enemy = enemyNation(p.getUniqueId(), g.owner);
        if (enemy == null && !p.hasPermission("ungraves.admin")) {
            p.sendMessage(P + "§cThis is " + g.ownerName + "'s grave. Only they can open it, unless the UN puts your nations at war.");
            return;
        }
        giveXp(p, g);
        if (!openMenu(p, g, enemy != null)) return;
        if (enemy != null) {
            Player owner = Bukkit.getPlayer(g.owner);
            if (owner != null) owner.sendMessage(P + "§c" + p.getName() + " of " + nationNames.getOrDefault(enemy, enemy)
                    + " is looting your grave at " + where(g) + "!");
        }
    }

    /** The owner gets everything back in the slots it came from. Whatever doesn't fit stays in the grave. */
    private void restore(Player p, Grave g) {
        PlayerInventory inv = p.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> en : g.slotted.entrySet()) {
            int slot = en.getKey();
            ItemStack there = slot < inv.getSize() ? inv.getItem(slot) : null;
            if (slot < inv.getSize() && (there == null || there.isEmpty())) inv.setItem(slot, en.getValue());
            else rest.add(en.getValue());
        }
        rest.addAll(g.loose);
        g.slotted.clear();
        g.loose.clear();
        for (ItemStack it : rest) g.loose.addAll(inv.addItem(it).values());
        giveXp(p, g);
        if (g.isEmpty()) {
            remove(g);
            p.sendMessage(P + "§aYou got your items back.");
        } else {
            save();
            p.sendMessage(P + "§eYour inventory is full. The rest is still in the grave.");
        }
    }

    private void giveXp(Player p, Grave g) {
        if (g.xp <= 0) return;
        p.giveExp(g.xp);
        g.xp = 0;
    }

    private boolean openMenu(Player p, Grave g, boolean raid) {
        GraveMenu m = new GraveMenu(g);
        m.inv = Bukkit.createInventory(m, MENU_SIZE, (raid ? "Looting " : "") + g.ownerName + "'s grave");
        int i = 0;
        List<Map.Entry<Integer, ItemStack>> all = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> en : g.slotted.entrySet()) all.add(Map.entry(en.getKey(), en.getValue()));
        for (ItemStack it : g.loose) all.add(Map.entry(-1, it));
        for (Map.Entry<Integer, ItemStack> en : all) {
            if (i < MENU_SIZE) { m.inv.setItem(i, en.getValue()); m.fromSlot[i++] = en.getKey(); }
            else m.hidden.add(en);
        }
        g.viewer = p.getUniqueId();
        menus.put(g, m);
        if (p.openInventory(m.inv) == null) {   // another plugin stopped it
            menus.remove(g);
            g.viewer = null;
            return false;
        }
        return true;
    }

    /** Copies what's left in the menu back into the grave. */
    private void syncMenu(GraveMenu m) {
        Grave g = m.grave;
        g.slotted.clear();
        g.loose.clear();
        for (int i = 0; i < m.inv.getSize(); i++) {
            ItemStack it = m.inv.getItem(i);
            if (it != null && !it.isEmpty()) keep(g, m.fromSlot[i], it.clone());
        }
        for (Map.Entry<Integer, ItemStack> en : m.hidden) keep(g, en.getKey(), en.getValue());
    }

    private static void keep(Grave g, int slot, ItemStack it) {
        if (slot >= 0 && !g.slotted.containsKey(slot)) g.slotted.put(slot, it);
        else g.loose.add(it);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GraveMenu m) || e.getClickedInventory() == null) return;
        boolean top = e.getRawSlot() < e.getView().getTopInventory().getSize();
        if (top ? !TAKE.contains(e.getAction()) : e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);   // a grave can be emptied, not filled
            return;
        }
        if (top) Bukkit.getScheduler().runTask(this, () -> {
            if (menus.get(m.grave) != m) return;
            syncMenu(m);
            save();
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GraveMenu)) return;
        int size = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) if (raw < size) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof GraveMenu m) || menus.get(m.grave) != m) return;
        menus.remove(m.grave);
        syncMenu(m);
        m.grave.viewer = null;
        if (m.grave.isEmpty()) remove(m.grave);
        else save();
    }

    // ---------------------------------------------------------------- keeping graves in one piece

    private boolean isGrave(Block b) { return graves.containsKey(key(b)); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isGrave(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isGrave); }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isGrave); }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(this::isGrave)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(this::isGrave)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { if (isGrave(e.getToBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (isGrave(e.getBlock())) e.setCancelled(true); }

    // ---------------------------------------------------------------- nations and wars (UNBank + UNBorders)

    private void refreshNations() {
        long s1 = unbankData.exists() ? unbankData.lastModified() : 0;
        if (s1 != unbankStamp) {
            unbankStamp = s1;
            memberOf.clear();
            nationNames.clear();
            ConfigurationSection sec = YamlConfiguration.loadConfiguration(unbankData).getConfigurationSection("nations");
            if (sec != null) for (String k : sec.getKeys(false)) {
                String key = k.toLowerCase(Locale.ROOT);
                nationNames.put(key, sec.getString(k + ".name", k));
                for (String m : sec.getStringList(k + ".members")) {
                    try { memberOf.put(UUID.fromString(m), key); } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        long s2 = bordersData.exists() ? bordersData.lastModified() : 0;
        if (s2 != bordersStamp) {
            bordersStamp = s2;
            wars.clear();
            wars.addAll(YamlConfiguration.loadConfiguration(bordersData).getStringList("wars"));
        }
    }

    /** The looter's nation key if it's at war with the owner's nation, else null. */
    private String enemyNation(UUID looter, UUID owner) {
        refreshNations();
        String a = memberOf.get(looter), b = memberOf.get(owner);
        if (a == null || b == null || a.equals(b)) return null;
        return wars.contains(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a) ? a : null;
    }

    // ---------------------------------------------------------------- /graves

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        boolean admin = s.hasPermission("ungraves.admin");
        if (a.length > 0 && !admin) {
            s.sendMessage(P + "Type /graves to see where your graves are.");
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("test")) {
            if (!(s instanceof Player p)) { s.sendMessage(P + "Run this in game."); return true; }
            testGrave(p);
            return true;
        }
        UUID who = null;
        String title = "All graves:";
        if (a.length == 0) {
            if (!(s instanceof Player p)) { s.sendMessage(P + "Usage: /graves <player|all>"); return true; }
            who = p.getUniqueId();
            title = "Your graves:";
        } else if (!a[0].equalsIgnoreCase("all")) {
            OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(a[0]);
            if (o == null) { s.sendMessage(P + "§cNo player called " + a[0] + " has joined."); return true; }
            who = o.getUniqueId();
            title = o.getName() + "'s graves:";
        }
        List<Grave> list = new ArrayList<>();
        for (Grave g : graves.values()) if (who == null || g.owner.equals(who)) list.add(g);
        if (list.isEmpty()) { s.sendMessage(P + "No graves."); return true; }
        s.sendMessage(P + title);
        for (Grave g : list)
            s.sendMessage("  §f" + where(g) + " §7- " + g.count() + " stacks, " + ago(g.created)
                    + (who == null ? ", " + g.ownerName : ""));
        if (admin) s.sendMessage("§7  Ops can open any grave. /graves test makes a practice grave with 3 dirt in front of you.");
        return true;
    }

    /** A practice grave holding 3 dirt, so the look and the opening can be checked while keep inventory is on. */
    private void testGrave(Player p) {
        Location front = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2));
        Block spot = findSpot(front);
        if (spot == null) { p.sendMessage(P + "§cNo room here."); return; }
        Grave g = new Grave();
        do g.id = UUID.randomUUID().toString().substring(0, 8); while (idTaken(g.id));
        g.owner = p.getUniqueId();
        g.ownerName = p.getName();
        g.created = System.currentTimeMillis();
        g.loose.add(new ItemStack(Material.DIRT, 3));
        place(g, spot);
        graves.put(g.key(), g);
        save();
        p.sendMessage(P + "§aPractice grave made at " + where(g) + ". Right-click or punch it to get the dirt back.");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1 && s.hasPermission("ungraves.admin")) {
            options.add("all");
            options.add("test");
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        }
        return options;
    }

    // ---------------------------------------------------------------- saving

    static String key(String world, int x, int y, int z) { return world + ";" + x + ";" + y + ";" + z; }
    static String key(Block b) { return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()); }

    private String where(Grave g) {
        World w = Bukkit.getWorld(g.world);
        String name = w == null ? g.world : switch (w.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
        return name + " " + g.x + ", " + g.y + ", " + g.z;
    }

    private static String ago(long when) {
        long min = (System.currentTimeMillis() - when) / 60000;
        if (min < 60) return min + " min ago";
        if (min < 60 * 48) return (min / 60) + " hours ago";
        return (min / 1440) + " days ago";
    }

    private void load() {
        graves.clear();
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("graves");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Grave g = new Grave();
                g.id = id;
                g.owner = UUID.fromString(s.getString("owner", ""));
                g.ownerName = s.getString("name", "?");
                g.world = s.getString("world", "world");
                g.x = s.getInt("x"); g.y = s.getInt("y"); g.z = s.getInt("z");
                g.created = s.getLong("created");
                g.xp = s.getInt("xp");
                String l = s.getString("label");
                g.label = l == null || l.isEmpty() ? null : UUID.fromString(l);
                for (String item : s.getStringList("items")) {
                    int colon = item.indexOf(':');
                    try {
                        keep(g, Integer.parseInt(item.substring(0, colon)),
                                ItemStack.deserializeBytes(Base64.getDecoder().decode(item.substring(colon + 1))));
                    } catch (RuntimeException ex) {
                        getLogger().severe("Couldn't read an item in grave " + id + " (" + ex + "). Saved copy: " + item);
                    }
                }
                graves.put(g.key(), g);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Skipping grave " + id + " in graves.yml: " + ex.getMessage());
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Grave g : graves.values()) {
            String b = "graves." + g.id + ".";
            y.set(b + "owner", g.owner.toString());
            y.set(b + "name", g.ownerName);
            y.set(b + "world", g.world);
            y.set(b + "x", g.x);
            y.set(b + "y", g.y);
            y.set(b + "z", g.z);
            y.set(b + "created", g.created);
            y.set(b + "xp", g.xp);
            y.set(b + "label", g.label == null ? "" : g.label.toString());
            List<String> items = new ArrayList<>();
            for (Map.Entry<Integer, ItemStack> en : g.slotted.entrySet())
                items.add(en.getKey() + ":" + Base64.getEncoder().encodeToString(en.getValue().serializeAsBytes()));
            for (ItemStack it : g.loose) items.add("-1:" + Base64.getEncoder().encodeToString(it.serializeAsBytes()));
            y.set(b + "items", items);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            getLogger().severe("Could not save graves.yml: " + ex.getMessage());
        }
    }
}
