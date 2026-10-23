package net.mabyfox.unborders;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wither;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Nation borders for the United Nations of MC With Gang.
 * Nations (from UNBank) claim chunks. Outsiders can't build, break, open or burn anything there,
 * except members of a nation the Secretary-General has put at war with the owner.
 * Claims show on BlueMap when it's installed.
 */
public final class UNBorders extends JavaPlugin implements Listener {

    static final String P = "§2[Borders]§r ";

    /** What we know about a nation, read from UNBank's data.yml. */
    static final class Nation {
        String key;
        String name;
        UUID leader;
        UUID treasurer;
        final Set<UUID> members = new HashSet<>();
        boolean canClaim(UUID id) { return id.equals(leader) || id.equals(treasurer); }
    }

    private Economy econ;
    private File unbankData;
    private long unbankStamp = -1;
    private final Map<String, Nation> nations = new LinkedHashMap<>();   // key = lower-case name
    private final Map<UUID, String> memberOf = new HashMap<>();

    private File claimsFile;
    private final Map<String, String> claims = new HashMap<>();          // "world;x;z" -> nation key
    private final Set<String> wars = new TreeSet<>();                     // "a|b", a < b
    private final Set<UUID> bypass = new HashSet<>();
    private final Map<UUID, Long> lastWarned = new HashMap<>();
    private final Map<UUID, String> unclaimAllConfirm = new HashMap<>();

    private BlueMapLayer map;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider();
        else getLogger().warning("No economy found, so claiming land is free.");

        Plugin unbank = getServer().getPluginManager().getPlugin("UNBank");
        unbankData = new File(unbank != null ? unbank.getDataFolder() : new File(getDataFolder().getParentFile(), "UNBank"), "data.yml");
        refreshNations();

        claimsFile = new File(getDataFolder(), "claims.yml");
        loadClaims();
        dropOrphanClaims();

        getServer().getPluginManager().registerEvents(this, this);
        // UNBank saves data.yml whenever a nation changes, so pick up new members and nations every few seconds.
        getServer().getScheduler().runTaskTimer(this, () -> { if (refreshNations()) { dropOrphanClaims(); redrawMap(); } }, 100L, 100L);

        if (getServer().getPluginManager().getPlugin("BlueMap") != null) {
            try {
                map = new BlueMapLayer(this);
                map.start();
            } catch (Throwable t) {
                map = null;
                getLogger().warning("Couldn't hook into BlueMap, borders won't show on the map: " + t);
            }
        }
        getLogger().info(nations.size() + " nations, " + claims.size() + " claimed chunks, " + wars.size() + " wars.");
    }

    @Override
    public void onDisable() {
        if (claimsFile != null) saveClaims();
    }

    /** Re-reads UNBank's data.yml if it changed. Returns true when it did. */
    boolean refreshNations() {
        long stamp = unbankData.exists() ? unbankData.lastModified() : 0;
        if (stamp == unbankStamp) return false;
        unbankStamp = stamp;
        nations.clear();
        memberOf.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(unbankData);
        ConfigurationSection sec = y.getConfigurationSection("nations");
        if (sec == null) return true;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Nation n = new Nation();
                n.key = key.toLowerCase(Locale.ROOT);
                n.name = s.getString("name", key);
                n.leader = UUID.fromString(s.getString("leader", ""));
                String t = s.getString("treasurer", "");
                n.treasurer = t == null || t.isEmpty() ? null : UUID.fromString(t);
                for (String m : s.getStringList("members")) {
                    UUID id = UUID.fromString(m);
                    n.members.add(id);
                    memberOf.put(id, n.key);
                }
                nations.put(n.key, n);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Skipping nation " + key + " in UNBank data.yml: " + ex.getMessage());
            }
        }
        return true;
    }

    /** Claims and wars of nations that no longer exist (disbanded) are removed. */
    private void dropOrphanClaims() {
        if (!unbankData.exists()) return;   // UNBank not set up yet; don't wipe anything
        boolean changed = claims.values().removeIf(k -> !nations.containsKey(k));
        changed |= wars.removeIf(w -> { String[] p = w.split("\\|"); return !nations.containsKey(p[0]) || !nations.containsKey(p[1]); });
        if (changed) saveClaims();
    }

    private void loadClaims() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(claimsFile);
        claims.clear();
        ConfigurationSection sec = y.getConfigurationSection("claims");
        if (sec != null) for (String nation : sec.getKeys(false))
            for (String c : sec.getStringList(nation)) claims.put(c, nation);
        wars.clear();
        wars.addAll(y.getStringList("wars"));
    }

    void saveClaims() {
        YamlConfiguration y = new YamlConfiguration();
        Map<String, List<String>> byNation = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : claims.entrySet())
            byNation.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        for (Map.Entry<String, List<String>> e : byNation.entrySet()) y.set("claims." + e.getKey(), e.getValue());
        y.set("wars", new ArrayList<>(wars));
        try {
            y.save(claimsFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save claims.yml: " + ex.getMessage());
        }
    }

    private void redrawMap() {
        if (map != null) map.redraw();
    }

    // ---------------------------------------------------------------- lookups (also used by BlueMapLayer)

    static String chunkKey(World w, int cx, int cz) { return w.getName() + ";" + cx + ";" + cz; }
    static String chunkKey(Chunk c) { return chunkKey(c.getWorld(), c.getX(), c.getZ()); }
    static String chunkKey(Location l) { return chunkKey(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4); }
    static String chunkKey(Block b) { return chunkKey(b.getWorld(), b.getX() >> 4, b.getZ() >> 4); }

    Map<String, String> claims() { return claims; }
    Nation nation(String key) { return key == null ? null : nations.get(key); }

    private Nation nationOf(UUID id) { return nation(memberOf.get(id)); }

    private static String warKey(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    boolean atWar(String a, String b) { return a != null && b != null && wars.contains(warKey(a, b)); }

    boolean atAnyWar(String n) {
        if (n == null) return false;
        for (String w : wars) { String[] p = w.split("\\|"); if (p[0].equals(n) || p[1].equals(n)) return true; }
        return false;
    }

    /** May this player change things in the chunk with this key? */
    private boolean allowed(Player p, String chunk) {
        String owner = claims.get(chunk);
        if (owner == null || bypass.contains(p.getUniqueId())) return true;
        String mine = memberOf.get(p.getUniqueId());
        return owner.equals(mine) || atWar(owner, mine);
    }

    /** Checks and, if denied, tells the player (at most once every two seconds). */
    private boolean check(Player p, String chunk) {
        if (allowed(p, chunk)) return true;
        long now = System.currentTimeMillis();
        Long last = lastWarned.get(p.getUniqueId());
        if (last == null || now - last > 2000) {
            lastWarned.put(p.getUniqueId(), now);
            Nation n = nation(claims.get(chunk));
            p.sendMessage(P + "§cThis is " + (n == null ? "another nation" : n.name) + "'s land. You can only raid it during an approved war.");
        }
        return false;
    }

    /** Explosions and fire may damage claimed land only while its nation is at war. */
    private boolean protectedFromWorld(String chunk) {
        String owner = claims.get(chunk);
        return owner != null && !atAnyWar(owner);
    }

    private static Player playerFrom(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!check(e.getPlayer(), chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!check(e.getPlayer(), chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!check(e.getPlayer(), chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!check(e.getPlayer(), chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL) return;
        String chunk = chunkKey(b);
        if (allowed(e.getPlayer(), chunk)) return;
        if (e.getAction() == Action.PHYSICAL) {   // trampling crops, pressure plates
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setCancelled(true);
            return;
        }
        // Chests, doors, buttons, beds and so on. Using the item in hand (eating, throwing) still works;
        // anything the item would place or change is caught by its own event.
        e.setUseInteractedBlock(Event.Result.DENY);
        Material hand = e.getItem() == null ? Material.AIR : e.getItem().getType();
        if (hand == Material.FLINT_AND_STEEL || hand == Material.FIRE_CHARGE || hand.name().endsWith("_SPAWN_EGG")) {
            e.setUseItemInHand(Event.Result.DENY);
        }
        check(e.getPlayer(), chunk);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (e.getPlayer() != null && !check(e.getPlayer(), chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (t instanceof ItemFrame || t instanceof InventoryHolder) {
            if (!check(e.getPlayer(), chunkKey(t.getLocation()))) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!check(e.getPlayer(), chunkKey(e.getRightClicked().getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (victim instanceof Player || victim instanceof Monster) return;   // PvP and mob fights are untouched
        Player p = playerFrom(e.getDamager());
        if (p != null && !check(p, chunkKey(victim.getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        String chunk = chunkKey(e.getEntity().getLocation());
        if (e instanceof HangingBreakByEntityEvent be) {
            Player p = playerFrom(be.getRemover());
            if (p != null) { if (!check(p, chunk)) e.setCancelled(true); return; }
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && protectedFromWorld(chunk)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Hanging h = e.getEntity();
        if (e.getPlayer() != null && !check(e.getPlayer(), chunkKey(h.getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {   // armor stands, boats, minecarts, end crystals
        if (e.getPlayer() != null && !check(e.getPlayer(), chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        String chunk = chunkKey(e.getBlock());
        if (e.getPlayer() != null) { if (!check(e.getPlayer(), chunk)) e.setCancelled(true); return; }
        if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD && protectedFromWorld(chunk)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (protectedFromWorld(chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getNewState().getType() == Material.FIRE && protectedFromWorld(chunkKey(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> protectedFromWorld(chunkKey(b)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {   // beds and respawn anchors
        e.blockList().removeIf(b -> protectedFromWorld(chunkKey(b)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMobGrief(EntityChangeBlockEvent e) {
        if ((e.getEntity() instanceof Enderman || e.getEntity() instanceof Wither) && protectedFromWorld(chunkKey(e.getBlock())))
            e.setCancelled(true);
    }

    /** Lava, water and pistons can't reach into a nation's land from outside it. */
    private boolean crosses(Block from, Block to) {
        String owner = claims.get(chunkKey(to));
        return owner != null && !owner.equals(claims.get(chunkKey(from)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (crosses(e.getBlock(), e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        Block piston = e.getBlock();
        if (crosses(piston, piston.getRelative(e.getDirection()))) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks()) {
            if (crosses(piston, b) || crosses(piston, b.getRelative(e.getDirection()))) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (crosses(e.getBlock(), b)) { e.setCancelled(true); return; }
    }

    // ---------------------------------------------------------------- border crossing message

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld())) return;
        String a = claims.get(chunkKey(from)), b = claims.get(chunkKey(to));
        if (a == null ? b == null : a.equals(b)) return;
        Player p = e.getPlayer();
        Nation n = nation(b);
        String line;
        if (n == null) line = "§7Wilderness";
        else {
            String mine = memberOf.get(p.getUniqueId());
            String tag = n.key.equals(mine) ? " §a(home)" : atWar(n.key, mine) ? " §c(at war: raiding allowed)" : "";
            line = "§eEntering " + n.name + tag;
        }
        p.sendTitle("", line, 5, 30, 10);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastWarned.remove(e.getPlayer().getUniqueId());
        unclaimAllConfirm.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- following UNBank's /un war and /un peace

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        watchUnCommand(e.getPlayer(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        watchUnCommand(e.getSender(), "/" + e.getCommand());
    }

    /**
     * UNBank announces wars but doesn't keep track of them, so we watch the Secretary-General's commands.
     * /un war only counts if UNBank accepted it (it saves data.yml after taking the war fee).
     */
    private void watchUnCommand(CommandSender sender, String message) {
        String[] a = message.trim().split("\\s+");
        if (a.length < 4) return;
        String cmd = a[0].toLowerCase(Locale.ROOT);
        if (!cmd.equals("/un") && !cmd.equals("/unbank:un")) return;
        if (!sender.hasPermission("unbank.sg")) return;
        String sub = a[1].toLowerCase(Locale.ROOT);
        String x = a[2].toLowerCase(Locale.ROOT), y = a[3].toLowerCase(Locale.ROOT);
        if (sub.equals("war")) {
            long before = unbankData.lastModified();
            Bukkit.getScheduler().runTask(this, () -> {
                if (unbankData.lastModified() == before) return;   // UNBank refused it (not enough money, bad names)
                refreshNations();
                startWar(x, y);
            });
        } else if (sub.equals("peace")) {
            Bukkit.getScheduler().runTask(this, () -> endWar(x, y));
        }
    }

    private boolean startWar(String x, String y) {
        Nation a = nation(x), b = nation(y);
        if (a == null || b == null || a == b) return false;
        if (wars.add(warKey(a.key, b.key))) {
            saveClaims();
            Bukkit.broadcastMessage(P + "§cThe borders of " + a.name + " and " + b.name + " are open to each other until peace is made.");
            redrawMap();
        }
        return true;
    }

    private boolean endWar(String x, String y) {
        Nation a = nation(x), b = nation(y);
        if (a == null || b == null) return false;
        if (wars.remove(warKey(a.key, b.key))) {
            saveClaims();
            Bukkit.broadcastMessage(P + "§aThe borders of " + a.name + " and " + b.name + " are protected again.");
            redrawMap();
        }
        return true;
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        refreshNations();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "claim" -> claim(sender);
            case "unclaim" -> unclaim(sender, args);
            default -> border(sender, args);
        }
        return true;
    }

    private int limitFor(Nation n) {
        return getConfig().getInt("base-chunks", 16) + getConfig().getInt("chunks-per-member", 8) * n.members.size();
    }

    private int countFor(String key) {
        int c = 0;
        for (String v : claims.values()) if (v.equals(key)) c++;
        return c;
    }

    private void claim(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(P + "Only players can claim land."); return; }
        Nation n = nationOf(p.getUniqueId());
        if (n == null) { p.sendMessage(P + "§cYou need to be in a nation. See /nation help."); return; }
        if (!n.canClaim(p.getUniqueId())) { p.sendMessage(P + "§cOnly the leader or treasurer of " + n.name + " can claim land."); return; }
        World w = p.getWorld();
        if (getConfig().getStringList("disabled-worlds").contains(w.getName())) { p.sendMessage(P + "§cLand can't be claimed in this world."); return; }
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        String here = chunkKey(w, cx, cz);
        String owner = claims.get(here);
        if (n.key.equals(owner)) { p.sendMessage(P + "This chunk is already yours."); return; }
        if (owner != null) { p.sendMessage(P + "§cThis chunk belongs to " + nation(owner).name + "."); return; }

        int have = countFor(n.key), limit = limitFor(n);
        if (have >= limit) { p.sendMessage(P + "§c" + n.name + " has all " + limit + " chunks it can hold. More members raise the limit."); return; }

        int gap = getConfig().getInt("gap-between-nations", 1);
        boolean touchesOwn = false;
        for (int dx = -Math.max(gap, 1); dx <= Math.max(gap, 1); dx++) {
            for (int dz = -Math.max(gap, 1); dz <= Math.max(gap, 1); dz++) {
                String o = claims.get(chunkKey(w, cx + dx, cz + dz));
                if (o == null) continue;
                if (o.equals(n.key)) { if (Math.abs(dx) + Math.abs(dz) == 1) touchesOwn = true; }
                else if (Math.abs(dx) <= gap && Math.abs(dz) <= gap) {
                    p.sendMessage(P + "§cToo close to " + nation(o).name + ". Leave " + gap + " chunk" + (gap == 1 ? "" : "s") + " of wilderness between nations.");
                    return;
                }
            }
        }
        if (have > 0 && !touchesOwn && getConfig().getBoolean("must-connect", true)) {
            p.sendMessage(P + "§cNew land has to touch land " + n.name + " already owns (side by side, not corner to corner).");
            return;
        }

        double cost = getConfig().getDouble("claim-cost", 50);
        if (econ != null && cost > 0) {
            if (!econ.has(p, cost)) { p.sendMessage(P + "§cClaiming a chunk costs " + econ.format(cost) + "."); return; }
            if (!econ.withdrawPlayer(p, cost).transactionSuccess()) { p.sendMessage(P + "§cPayment failed."); return; }
        }
        claims.put(here, n.key);
        saveClaims();
        redrawMap();
        p.sendMessage(P + "§aClaimed this chunk for " + n.name
                + (econ != null && cost > 0 ? " for " + econ.format(cost) : "") + ". " + (have + 1) + "/" + limit + " chunks used.");
    }

    private void unclaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(P + "Only players can unclaim land."); return; }
        Nation n = nationOf(p.getUniqueId());
        if (n == null || !n.canClaim(p.getUniqueId())) { p.sendMessage(P + "§cOnly a nation's leader or treasurer can unclaim its land."); return; }
        if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                p.sendMessage(P + "§cThis gives up all " + countFor(n.key) + " chunks of " + n.name + " with no refund. Type /unclaim all confirm");
                return;
            }
            claims.values().removeIf(n.key::equals);
            saveClaims();
            redrawMap();
            p.sendMessage(P + "All of " + n.name + "'s land is wilderness now.");
            return;
        }
        String here = chunkKey(p.getLocation());
        if (!n.key.equals(claims.get(here))) { p.sendMessage(P + "§cThis chunk isn't " + n.name + "'s."); return; }
        claims.remove(here);
        saveClaims();
        redrawMap();
        p.sendMessage(P + "This chunk is wilderness now (no refund).");
    }

    private void border(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "here" : a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("unborders.admin");
        switch (sub) {
            case "here" -> {
                if (!(s instanceof Player p)) { borderHelp(s); return; }
                Nation n = nation(claims.get(chunkKey(p.getLocation())));
                p.sendMessage(P + (n == null ? "You're in the wilderness. Anyone can build here." : "This land belongs to §e" + n.name + "§r."));
                borderHelp(p);
            }
            case "map" -> showMap(s);
            case "list" -> {
                if (nations.isEmpty()) { s.sendMessage(P + "No nations yet."); return; }
                s.sendMessage(P + "Nation land:");
                for (Nation n : nations.values())
                    s.sendMessage("  §e" + n.name + "§r: " + countFor(n.key) + "/" + limitFor(n) + " chunks" + (atAnyWar(n.key) ? " §c(at war)" : ""));
            }
            case "wars" -> {
                if (wars.isEmpty()) { s.sendMessage(P + "No wars right now. All borders are protected."); return; }
                s.sendMessage(P + "Wars (these nations can raid each other):");
                for (String w : wars) { String[] p = w.split("\\|"); s.sendMessage("  §c" + nation(p[0]).name + " vs " + nation(p[1]).name); }
            }
            case "war", "peace" -> {
                if (!admin) { s.sendMessage(P + "§cOnly the Secretary-General can do that. Wars start with /un war."); return; }
                if (a.length < 3) { s.sendMessage(P + "Usage: /border " + sub + " <nation> <nation>"); return; }
                String x = a[1].toLowerCase(Locale.ROOT), y = a[2].toLowerCase(Locale.ROOT);
                boolean ok = sub.equals("war") ? startWar(x, y) : endWar(x, y);
                if (!ok) s.sendMessage(P + "§cName two different nations. See /border list.");
                else s.sendMessage(P + "Done. This doesn't charge the war fee; /un war does.");
            }
            case "bypass" -> {
                if (!admin || !(s instanceof Player p)) { s.sendMessage(P + "§cOnly the Secretary-General can do that."); return; }
                boolean on = bypass.add(p.getUniqueId());
                if (!on) bypass.remove(p.getUniqueId());
                p.sendMessage(P + "Admin bypass " + (on ? "§aON§r: you can build anywhere." : "§cOFF§r: borders apply to you again."));
            }
            case "remove" -> {
                if (!admin || !(s instanceof Player p)) { s.sendMessage(P + "§cOnly the Secretary-General can do that."); return; }
                String owner = claims.remove(chunkKey(p.getLocation()));
                if (owner == null) { p.sendMessage(P + "This chunk isn't claimed."); return; }
                saveClaims();
                redrawMap();
                p.sendMessage(P + "Removed this chunk from " + (nation(owner) == null ? owner : nation(owner).name) + ".");
            }
            case "reload" -> {
                if (!admin) { s.sendMessage(P + "§cOnly the Secretary-General can do that."); return; }
                reloadConfig();
                unbankStamp = -1;
                refreshNations();
                redrawMap();
                s.sendMessage(P + "Reloaded.");
            }
            default -> borderHelp(s);
        }
    }

    private void showMap(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage(P + "Only players can see the map."); return; }
        String mine = memberOf.get(p.getUniqueId());
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        Map<String, Character> letters = new LinkedHashMap<>();
        p.sendMessage(P + "Land around you (north is up, each square is a chunk):");
        for (int dz = -4; dz <= 4; dz++) {
            StringBuilder row = new StringBuilder("  ");
            for (int dx = -8; dx <= 8; dx++) {
                if (dx == 0 && dz == 0) { row.append("§b@"); continue; }
                String o = claims.get(chunkKey(p.getWorld(), cx + dx, cz + dz));
                if (o == null) { row.append("§8-"); continue; }
                char c = letters.computeIfAbsent(o, k -> Character.toUpperCase(nation(k) == null ? '?' : nation(k).name.charAt(0)));
                row.append(o.equals(mine) ? "§a" : atWar(o, mine) ? "§c" : "§e").append(c);
            }
            p.sendMessage(row.toString());
        }
        StringBuilder key = new StringBuilder("  §b@§r you  §8-§r wild");
        for (Map.Entry<String, Character> e : letters.entrySet()) key.append("  ").append(e.getValue()).append(" ").append(nation(e.getKey()).name);
        p.sendMessage(key.toString());
    }

    private void borderHelp(CommandSender s) {
        double cost = getConfig().getDouble("claim-cost", 50);
        s.sendMessage(P + "Border commands:");
        s.sendMessage("  /claim §7- leader/treasurer claims the chunk you're in" + (econ != null && cost > 0 ? " (" + econ.format(cost) + ")" : ""));
        s.sendMessage("  /unclaim [all] §7- give a chunk (or everything) back to the wild");
        s.sendMessage("  /border map | list | wars §7- see land and wars");
        if (s.hasPermission("unborders.admin"))
            s.sendMessage("  §7Secretary-General:§r /border war|peace <a> <b>, bypass, remove, reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("unclaim")) {
            if (args.length == 1) options.add("all");
        } else if (cmd.equals("border")) {
            if (args.length == 1) {
                options.addAll(List.of("map", "list", "wars"));
                if (sender.hasPermission("unborders.admin")) options.addAll(List.of("war", "peace", "bypass", "remove", "reload"));
            } else if (args.length <= 3) {
                for (Nation n : nations.values()) options.add(n.name);
            }
        }
        if (args.length == 0) return options;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
