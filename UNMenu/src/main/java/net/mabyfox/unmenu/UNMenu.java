package net.mabyfox.unmenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pop-up button menus for Bedrock players (PlayStation, phone, tablet, Windows) through Floodgate forms.
 * Every button runs the normal command as the player, so permissions, costs and teleport warmups
 * work exactly as if they had typed it. Java players keep their normal commands.
 */
public final class UNMenu extends JavaPlugin implements Listener {

    static final String P = "§6[Menu]§r ";
    private static final String TITLE = "UN of MC With Gang";

    private FloodgateApi floodgate;
    private NamespacedKey itemKey;
    private File playersFile;
    private final Set<UUID> hidden = new HashSet<>();          // players who removed the menu compass
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private EssentialsHook ess;                                 // null without EssentialsX
    private VaultHook vault;                                    // null without Vault

    /** A nation as UNBank saved it in plugins/UNBank/data.yml. */
    record Nation(String name, UUID leader, UUID treasurer, List<UUID> members, double balance, double tax) {
        boolean manages(UUID id) { return id.equals(leader) || id.equals(treasurer); }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        floodgate = FloodgateApi.getInstance();
        itemKey = new NamespacedKey(this, "menu_item");
        playersFile = new File(getDataFolder(), "players.yml");
        for (String s : YamlConfiguration.loadConfiguration(playersFile).getStringList("compass-off")) {
            try { hidden.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        if (getServer().getPluginManager().getPlugin("Essentials") != null) {
            try { ess = new EssentialsHook(this); getServer().getPluginManager().registerEvents(ess, this); }
            catch (Throwable t) { getLogger().warning("EssentialsX hook failed, homes list and teleport pop-ups are off: " + t); }
        }
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try { vault = VaultHook.find(getServer()); }
            catch (Throwable t) { getLogger().warning("Vault hook failed, balances won't show: " + t); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers()) giveCompass(p, false);
        getLogger().info("Bedrock menus ready (/menu or the Server Menu compass).");
    }

    private void saveHidden() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : hidden) ids.add(id.toString());
        y.set("compass-off", ids);
        try { y.save(playersFile); } catch (IOException e) { getLogger().warning("Could not save players.yml: " + e.getMessage()); }
    }

    boolean bedrock(Player p) { return floodgate.isFloodgatePlayer(p.getUniqueId()); }

    // ---------------------------------------------------------------- /menu

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("The menu is for players."); return true; }
        if (!bedrock(p)) {
            p.sendMessage(P + "The button menu is for Bedrock players (PlayStation, phone, tablet). "
                    + "On Java use /nation help, /border, /home, /tpa and the other usual commands.");
            return true;
        }
        openMain(p);
        return true;
    }

    // ---------------------------------------------------------------- form helpers

    /** Runs on the main thread, only if the player is still online. Form answers arrive on a network thread. */
    void sync(Player p, Runnable r) {
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) r.run(); });
    }

    static void run(Player p, String command) { p.performCommand(command); }

    /** A list of buttons, each with what it does. */
    final class Buttons {
        private final SimpleForm.Builder form;
        private final List<Runnable> actions = new ArrayList<>();

        Buttons(String title, String content) { form = SimpleForm.builder().title(title).content(content); }

        Buttons add(String text, String icon, Runnable action) {
            if (icon == null) form.button(text); else form.button(text, FormImage.Type.PATH, icon);
            actions.add(action);
            return this;
        }

        void send(Player p) {
            form.validResultHandler(r -> sync(p, () -> {
                int i = r.clickedButtonId();
                if (i >= 0 && i < actions.size()) actions.get(i).run();
            }));
            floodgate.sendForm(p.getUniqueId(), form.build());
        }
    }

    void ask(Player p, CustomForm.Builder form, Consumer<CustomFormResponse> then) {
        form.validResultHandler(r -> sync(p, () -> then.accept(r)));
        floodgate.sendForm(p.getUniqueId(), form.build());
    }

    void confirm(Player p, String question, String yes, String no, Runnable onYes, Runnable onNo) {
        ModalForm.Builder f = ModalForm.builder().title(TITLE).content(question).button1(yes).button2(no)
                .validResultHandler(r -> sync(p, r.clickedFirst() ? onYes : onNo));
        floodgate.sendForm(p.getUniqueId(), f.build());
    }

    void confirm(Player p, String question, String yes, Runnable onYes) {
        confirm(p, question, "§c" + yes, "Cancel", onYes, () -> { });
    }

    /** Other online players, sorted by name. Tells the player when nobody else is on. */
    List<String> others(Player p) {
        List<String> names = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) names.add(o.getName());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) p.sendMessage(P + "Nobody else is online right now.");
        return names;
    }

    /** Pick one name from a list, then do something with it. */
    void pick(Player p, String title, String label, List<String> names, Consumer<String> then) {
        if (names.isEmpty()) return;
        ask(p, CustomForm.builder().title(title).dropdown(label, names), r -> {
            int i = r.asDropdown(0);
            if (i >= 0 && i < names.size()) then.accept(names.get(i));
        });
    }

    /** Ask for an amount of money (typed), then do something with it. */
    void askAmount(Player p, String title, String label, Consumer<String> then) {
        ask(p, CustomForm.builder().title(title).input(label, "for example 100"), r -> {
            String v = cleanAmount(r.asInput(0));
            if (v.isEmpty()) p.sendMessage(P + "§cType an amount, like 100."); else then.accept(v);
        });
    }

    static String cleanAmount(String raw) { return raw == null ? "" : raw.replaceAll("[^0-9.]", ""); }

    static String oneLine(String raw) { return raw == null ? "" : raw.replaceAll("[\\r\\n]+", " ").trim(); }

    String money(double v) {
        if (vault != null) try { return vault.format(v); } catch (Throwable ignored) { }
        return String.format(Locale.ROOT, "$%,.2f", v);
    }

    String balance(Player p) {
        if (vault == null) return "?";
        try { return vault.format(vault.balance(p)); } catch (Throwable t) { return "?"; }
    }

    // ---------------------------------------------------------------- UNBank data

    private File pluginFile(String plugin, String file) {
        Plugin pl = getServer().getPluginManager().getPlugin(plugin);
        File dir = pl != null ? pl.getDataFolder() : new File(getDataFolder().getParentFile(), plugin);
        return new File(dir, file);
    }

    Map<String, Nation> nations() {
        Map<String, Nation> out = new LinkedHashMap<>();
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(pluginFile("UNBank", "data.yml")).getConfigurationSection("nations");
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                List<UUID> members = new ArrayList<>();
                for (String m : s.getStringList("members")) members.add(UUID.fromString(m));
                String t = s.getString("treasurer", "");
                out.put(key, new Nation(s.getString("name", key), UUID.fromString(s.getString("leader", "")),
                        t.isEmpty() ? null : UUID.fromString(t), members, s.getDouble("balance"), s.getDouble("tax")));
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    static Nation nationOf(Map<String, Nation> all, UUID id) {
        for (Nation n : all.values()) if (n.members().contains(id)) return n;
        return null;
    }

    double setting(String plugin, String path, double def) {
        return YamlConfiguration.loadConfiguration(pluginFile(plugin, "config.yml")).getDouble(path, def);
    }

    static String nameOf(UUID id) {
        if (id == null) return "none";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    /** The nation key a player has been invited to, read from UNBank (invites aren't saved to disk). */
    @SuppressWarnings("unchecked")
    String pendingInvite(UUID id) {
        try {
            Plugin ub = getServer().getPluginManager().getPlugin("UNBank");
            if (ub == null) return null;
            Field f = ub.getClass().getDeclaredField("invites");
            f.setAccessible(true);
            return ((Map<UUID, String>) f.get(ub)).get(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- main menu

    void openMain(Player p) {
        UUID id = p.getUniqueId();
        Nation n = nationOf(nations(), id);
        String role = n == null ? "" : id.equals(n.leader()) ? " (leader)" : id.equals(n.treasurer()) ? " (treasurer)" : "";
        String content = "§7Balance: §a" + balance(p) + "\n§7Nation: §e" + (n == null ? "none yet" : n.name() + role);
        Buttons b = new Buttons(TITLE, content)
                .add("My nation\n§8treasury, members, invites", "textures/items/book_written", () -> openNation(p))
                .add("Land and borders\n§8claim land, border map, wars", "textures/items/map_filled", () -> openLand(p))
                .add("Money\n§8pay, sell, balance", "textures/items/emerald", () -> openMoney(p))
                .add("Homes\n§8go home, set a home", "textures/items/bed_red", () -> openHomes(p))
                .add("Teleport\n§8go to a friend, accept requests", "textures/items/ender_pearl", () -> openTeleport(p))
                .add("Chat\n§8message someone, who's online", "textures/items/paper", () -> openChat(p));
        if (hidden.contains(id)) {
            b.add("Give me the menu compass", "textures/items/compass_item", () -> {
                hidden.remove(id); saveHidden(); giveCompass(p, true);
            });
        } else {
            b.add("Remove the menu compass\n§8/menu still works", null, () -> {
                hidden.add(id); saveHidden(); removeCompass(p);
                p.sendMessage(P + "Compass removed. Type /menu to open the menu, and get the compass back from there.");
            });
        }
        b.send(p);
    }

    private Runnable back(Player p) { return () -> openMain(p); }

    // ---------------------------------------------------------------- nation

    void openNation(Player p) {
        UUID id = p.getUniqueId();
        Map<String, Nation> all = nations();
        Nation n = nationOf(all, id);
        List<String> nationNames = new ArrayList<>();
        for (Nation x : all.values()) nationNames.add(x.name());

        if (n == null) {
            Buttons b = new Buttons("Nations", "You're not in a nation yet.\n\nTo join one, ask its leader to invite you. "
                    + "A pop-up will let you join when they do.");
            String inviteKey = pendingInvite(id);
            Nation invited = inviteKey == null ? null : all.get(inviteKey);
            if (invited != null)
                b.add("§2Join " + invited.name() + "\n§8you're invited", "textures/items/book_written",
                        () -> run(p, "nation join " + invited.name()));
            b.add("Start a nation\n§8costs " + money(setting("UNBank", "nation-create-cost", 500)), "textures/items/iron_sword",
                    () -> ask(p, CustomForm.builder().title("Start a nation")
                            .label("Pick a name: 2 to 24 letters, numbers or _ (no spaces).")
                            .input("Nation name", "Atlantis"), r -> {
                        String name = oneLine(r.asInput(1)).replace(' ', '_');
                        if (name.isEmpty()) p.sendMessage(P + "§cType a name for your nation.");
                        else run(p, "nation create " + name);
                    }));
            b.add("All nations", "textures/items/book_normal", () -> run(p, "nation list"));
            if (!nationNames.isEmpty())
                b.add("Look up a nation", null, () -> pick(p, "Look up a nation", "Nation", nationNames, x -> run(p, "nation info " + x)));
            b.add("< Back", null, back(p)).send(p);
            return;
        }

        boolean leader = id.equals(n.leader());
        List<String> members = new ArrayList<>();
        for (UUID m : n.members()) if (!m.equals(id) && Bukkit.getOfflinePlayer(m).getName() != null) members.add(nameOf(m));
        members.sort(String.CASE_INSENSITIVE_ORDER);

        String content = "§e" + n.name() + "§r\nLeader: " + nameOf(n.leader()) + "\nTreasurer: " + nameOf(n.treasurer())
                + "\nMembers: " + n.members().size() + "\nTreasury: §a" + money(n.balance()) + "§r\nDaily tax: " + money(n.tax());
        Buttons b = new Buttons(n.name(), content)
                .add("Nation details", "textures/items/book_written", () -> run(p, "nation info"))
                .add("Put money in the treasury", "textures/items/gold_ingot",
                        () -> askAmount(p, "Put money in", "How much?", v -> run(p, "nation deposit " + v)));
        if (n.manages(id)) {
            b.add("Take money out of the treasury", "textures/items/gold_ingot",
                    () -> askAmount(p, "Take money out", "How much?", v -> run(p, "nation withdraw " + v)));
            b.add("Pay a player from the treasury", "textures/items/emerald", () -> {
                List<String> names = others(p);
                if (names.isEmpty()) return;
                ask(p, CustomForm.builder().title("Pay from the treasury").dropdown("Player", names).input("Amount", "for example 100"), r -> {
                    String v = cleanAmount(r.asInput(1));
                    if (v.isEmpty()) p.sendMessage(P + "§cType an amount, like 100.");
                    else run(p, "nation pay " + names.get(r.asDropdown(0)) + " " + v);
                });
            });
        }
        if (leader) {
            b.add("Invite a player", "textures/items/paper", () -> {
                List<String> free = new ArrayList<>();
                for (String o : others(p)) {
                    Player t = Bukkit.getPlayerExact(o);
                    if (t != null && nationOf(all, t.getUniqueId()) == null) free.add(o);
                }
                if (free.isEmpty()) { p.sendMessage(P + "Everyone online is already in a nation."); return; }
                pick(p, "Invite a player", "Player", free, x -> { run(p, "nation invite " + x); inviteSent(x); });
            });
            if (!members.isEmpty()) {
                b.add("Choose the treasurer", null, () -> pick(p, "Choose the treasurer",
                        "The treasurer can spend the treasury and claim land", members, x -> run(p, "nation treasurer " + x)));
                b.add("Remove a member", null, () -> pick(p, "Remove a member", "Member", members,
                        x -> confirm(p, "Remove " + x + " from " + n.name() + "?", "Remove", () -> run(p, "nation kick " + x))));
                b.add("Hand over leadership", null, () -> pick(p, "Hand over leadership", "New leader", members,
                        x -> confirm(p, "Make " + x + " the leader of " + n.name() + "? You can't undo this yourself.", "Make leader",
                                () -> run(p, "nation setleader " + x))));
            }
            int max = (int) setting("UNBank", "max-daily-tax", 20);
            b.add("Set the daily tax\n§8now " + money(n.tax()), null, () -> ask(p, CustomForm.builder().title("Daily tax")
                    .label("Taken from each member the first time they join each day. 10% goes to the UN.")
                    .slider("Tax", 0, max, 1, (float) Math.min(max, Math.round(n.tax()))), r -> run(p, "nation tax " + Math.round(r.asSlider(1)))));
            b.add("§cDisband the nation", "textures/blocks/barrier", () -> confirm(p, "Delete " + n.name()
                    + " for good? The treasury (" + money(n.balance()) + ") goes to you and all its land is freed.", "Disband",
                    () -> run(p, "nation disband confirm")));
        } else {
            b.add("Leave the nation", null, () -> confirm(p, "Leave " + n.name() + "?", "Leave", () -> run(p, "nation leave")));
        }
        b.add("All nations", "textures/items/book_normal", () -> run(p, "nation list"));
        b.add("< Back", null, back(p)).send(p);
    }

    /** Called after a nation invite: gives Bedrock players a Join pop-up. */
    void inviteSent(String targetName) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player t = Bukkit.getPlayerExact(targetName);
            if (t == null || !bedrock(t)) return;
            String key = pendingInvite(t.getUniqueId());
            Nation n = key == null ? null : nations().get(key);
            if (n == null) return;
            confirm(t, "You've been invited to join " + n.name() + " (leader " + nameOf(n.leader()) + ").",
                    "§2Join " + n.name(), "Not now", () -> run(t, "nation join " + n.name()),
                    () -> t.sendMessage(P + "No problem. You can still join from /menu > My nation while the invite lasts."));
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTypedCommand(PlayerCommandPreprocessEvent e) {
        String[] a = e.getMessage().trim().split("\\s+");
        if (a.length >= 3 && a[1].equalsIgnoreCase("invite")
                && (a[0].equalsIgnoreCase("/nation") || a[0].equalsIgnoreCase("/n") || a[0].equalsIgnoreCase("/unbank:nation")))
            inviteSent(a[2]);
    }

    // ---------------------------------------------------------------- land

    void openLand(Player p) {
        UUID id = p.getUniqueId();
        Nation n = nationOf(nations(), id);
        String link = getConfig().getString("map-link", "");
        Buttons b = new Buttons("Land and borders", "Claimed land is safe from outsiders unless the UN approves a war."
                + (link.isEmpty() ? "" : "\n\nLive map in a web browser:\n§b" + link));
        b.add("Border map around me", "textures/items/map_filled", () -> run(p, "border map"));
        if (n != null && n.manages(id)) {
            b.add("Claim this chunk\n§8costs " + money(setting("UNBorders", "claim-cost", 50)), "textures/items/sign", () -> run(p, "claim"));
            b.add("Unclaim this chunk", null, () -> confirm(p, "Give the chunk you're standing in back to the wild?", "Unclaim", () -> run(p, "unclaim")));
        }
        b.add("Every nation's land", "textures/items/book_normal", () -> run(p, "border list"));
        b.add("Wars right now", "textures/items/iron_sword", () -> run(p, "border wars"));
        b.add("< Back", null, back(p)).send(p);
    }

    // ---------------------------------------------------------------- money

    void openMoney(Player p) {
        new Buttons("Money", "§7Balance: §a" + balance(p))
                .add("Pay a player", "textures/items/emerald", () -> {
                    List<String> names = others(p);
                    if (names.isEmpty()) return;
                    ask(p, CustomForm.builder().title("Pay a player").dropdown("Player", names).input("Amount", "for example 50"), r -> {
                        String v = cleanAmount(r.asInput(1));
                        if (v.isEmpty()) p.sendMessage(P + "§cType an amount, like 50.");
                        else run(p, "pay " + names.get(r.asDropdown(0)) + " " + v);
                    });
                })
                .add("What's this item worth?\n§8the item in your hand", "textures/items/gold_ingot", () -> run(p, "worth"))
                .add("Sell the item in my hand\n§8sells all of that item you carry", "textures/items/diamond",
                        () -> confirm(p, "Sell every " + heldName(p) + " in your inventory?", "Sell", () -> run(p, "sell hand")))
                .add("Sell everything I can", null, () -> confirm(p, "Sell every sellable item you carry? That includes ores, "
                        + "diamonds, logs, crops and mob drops.", "Sell all", () -> run(p, "sell inventory")))
                .add("Richest players", "textures/items/book_normal", () -> run(p, "baltop"))
                .add("UN treasury", null, () -> run(p, "un balance"))
                .add("< Back", null, back(p)).send(p);
    }

    static String heldName(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) return "item (you're not holding anything)";
        return it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ---------------------------------------------------------------- homes

    void openHomes(Player p) {
        List<String> homes = ess == null ? Collections.emptyList() : ess.homes(p);
        int limit = ess == null ? 0 : ess.homeLimit(p);
        String content = homes.isEmpty() ? "You don't have a home yet. Stand where you want one and pick Set a home here."
                : "You have " + homes.size() + (limit > 0 ? " of " + limit : "") + " homes.";
        Buttons b = new Buttons("Homes", content);
        for (String h : homes) b.add("Go to " + h, "textures/items/bed_red", () -> run(p, "home " + h));
        b.add("Set a home here", "textures/items/sign", () -> {
            String suggest = homes.isEmpty() ? "home" : "home" + (homes.size() + 1);
            ask(p, CustomForm.builder().title("Set a home here").label("Using a name you already have moves that home here.")
                    .input("Home name", suggest, suggest), r -> {
                String name = oneLine(r.asInput(1)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                run(p, "sethome " + (name.isEmpty() ? suggest : name));
            });
        });
        if (!homes.isEmpty())
            b.add("Delete a home", null, () -> pick(p, "Delete a home", "Home", homes,
                    h -> confirm(p, "Delete your home " + h + "?", "Delete", () -> run(p, "delhome " + h))));
        b.add("< Back", null, back(p)).send(p);
    }

    // ---------------------------------------------------------------- teleport

    void openTeleport(Player p) {
        Buttons b = new Buttons("Teleport", "Teleports ask the other player first and take 3 seconds. Don't move or get hit while you wait.")
                .add("Go to a player", "textures/items/ender_pearl",
                        () -> pick(p, "Go to a player", "Who?", others(p), x -> run(p, "tpa " + x)))
                .add("Bring a player to me", "textures/items/ender_pearl",
                        () -> pick(p, "Bring a player to me", "Who?", others(p), x -> run(p, "tpahere " + x)))
                .add("Accept a teleport request", null, () -> run(p, "tpaccept"))
                .add("Deny a teleport request", null, () -> run(p, "tpdeny"))
                .add("Cancel my request", null, () -> run(p, "tpacancel"))
                .add("Go back to my last spot", null, () -> run(p, "back"));
        List<String> warps = ess == null ? Collections.emptyList() : ess.warps();
        if (!warps.isEmpty())
            b.add("Warps", "textures/items/map_filled", () -> pick(p, "Warps", "Where to?", warps, w -> run(p, "warp " + w)));
        b.add("< Back", null, back(p)).send(p);
    }

    /** Called by the EssentialsX hook when someone sends this player a /tpa or /tpahere. */
    void tpaPopup(Player target, String from, boolean here) {
        if (!bedrock(target)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!target.isOnline()) return;
            confirm(target, here ? from + " wants you to teleport to them." : from + " wants to teleport to you.",
                    "§2Accept", "Deny", () -> run(target, "tpaccept " + from), () -> run(target, "tpdeny " + from));
        }, 2L);
    }

    // ---------------------------------------------------------------- chat

    void openChat(Player p) {
        new Buttons("Chat", "")
                .add("Message a player", "textures/items/paper", () -> {
                    List<String> names = others(p);
                    if (names.isEmpty()) return;
                    ask(p, CustomForm.builder().title("Message a player").dropdown("To", names).input("Message"), r -> {
                        String msg = oneLine(r.asInput(1));
                        if (!msg.isEmpty()) run(p, "msg " + names.get(r.asDropdown(0)) + " " + msg);
                    });
                })
                .add("Reply to my last message", null, () -> ask(p, CustomForm.builder().title("Reply").input("Message"), r -> {
                    String msg = oneLine(r.asInput(0));
                    if (!msg.isEmpty()) run(p, "r " + msg);
                }))
                .add("Who's online", "textures/items/book_normal", () -> run(p, "list"))
                .add("Server rules", null, () -> run(p, "rules"))
                .add("Ask the admin for help", null, () -> ask(p, CustomForm.builder().title("Ask the admin").input("What do you need?"), r -> {
                    String msg = oneLine(r.asInput(0));
                    if (!msg.isEmpty()) run(p, "helpop " + msg);
                }))
                .add("Go AFK / back from AFK", null, () -> run(p, "afk"))
                .add("< Back", null, back(p)).send(p);
    }

    // ---------------------------------------------------------------- the menu compass

    ItemStack compass() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Server Menu", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        m.lore(List.of(Component.text("Use it (or type /menu) to open", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("nations, money, homes and teleports.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isCompass(ItemStack it) {
        if (it == null || it.getType() != Material.COMPASS || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    void giveCompass(Player p, boolean tell) {
        if (!bedrock(p) || hidden.contains(p.getUniqueId()) || !getConfig().getBoolean("give-compass", true)) return;
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) if (isCompass(it)) return;
        ItemStack last = inv.getItem(8);
        int slot = last == null || last.getType().isAir() ? 8 : inv.firstEmpty();
        if (slot < 0) { if (tell) p.sendMessage(P + "Your inventory is full. Type /menu to open the menu."); return; }
        inv.setItem(slot, compass());
        if (tell) p.sendMessage(P + "Use the §6Server Menu§r compass in your hotbar (or type /menu) to open the menu.");
    }

    void removeCompass(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] items = inv.getContents();
        for (int i = 0; i < items.length; i++) if (isCompass(items[i])) inv.setItem(i, null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) giveCompass(p, true); }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastOpen.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (!isCompass(e.getItem())) return;
        Action a = e.getAction();
        if (a == Action.PHYSICAL || a == Action.LEFT_CLICK_BLOCK) return;
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Material m = e.getClickedBlock().getType();
            if (m != Material.LODESTONE && m.isInteractable() && !e.getPlayer().isSneaking()) return; // doors, chests, buttons work as normal
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastOpen.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        lastOpen.put(p.getUniqueId(), now);
        if (bedrock(p)) openMain(p);
        else p.sendMessage(P + "The button menu is for Bedrock players. On Java use the usual commands.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!isCompass(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(P + "To get rid of the menu compass, open the menu and pick Remove the menu compass.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        // Never drop it or put it in a grave; it comes back on respawn.
        e.getDrops().removeIf(this::isCompass);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) giveCompass(p, false); });
    }

    /** Keeps the compass out of chests, anvils, crafting tables and every other container. */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) return;   // just the player's own inventory
        boolean involved = isCompass(e.getCurrentItem()) || isCompass(e.getCursor());
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0)
            involved |= isCompass(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        if (e.getClick() == ClickType.SWAP_OFFHAND)
            involved |= isCompass(e.getWhoClicked().getInventory().getItemInOffHand());
        if (involved) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || !isCompass(e.getOldCursor())) return;
        for (int slot : e.getRawSlots()) if (slot < top.getSize()) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent e) {
        if ((e.getRightClicked() instanceof ItemFrame || e.getRightClicked() instanceof Allay)
                && isCompass(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (isCompass(e.getPlayerItem())) e.setCancelled(true);
    }
}
