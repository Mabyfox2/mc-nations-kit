package net.mabyfox.unbank;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Nations with shared treasuries, a daily tax, a daily login reward and a UN treasury
 * run by the Secretary-General. Player money lives in EssentialsX (through Vault);
 * nation and UN balances live in this plugin's data.yml.
 */
public final class UNBank extends JavaPlugin implements Listener {

    private static final String P = "§6[UN Bank]§r ";

    private Economy econ;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<String, Nation> nations = new LinkedHashMap<>();   // key = lower-case name
    private final Map<UUID, String> invites = new HashMap<>();           // player -> nation key
    private double unBalance;

    static final class Nation {
        String name;
        UUID leader;
        UUID treasurer;
        final List<UUID> members = new ArrayList<>();
        double balance;
        double tax;
        boolean canManage(UUID id) { return id.equals(leader) || id.equals(treasurer); }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No economy found. Install Vault (or VaultUnlocked) and EssentialsX. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        econ = rsp.getProvider();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Using economy " + econ.getName() + ", " + nations.size() + " nations loaded.");
    }

    @Override
    public void onDisable() {
        if (data != null) saveData();
    }

    private void loadData() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        nations.clear();
        unBalance = data.getDouble("un.balance", 0);
        ConfigurationSection sec = data.getConfigurationSection("nations");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                Nation n = new Nation();
                n.name = s.getString("name", key);
                n.leader = UUID.fromString(s.getString("leader"));
                String t = s.getString("treasurer");
                n.treasurer = t == null || t.isEmpty() ? null : UUID.fromString(t);
                for (String m : s.getStringList("members")) n.members.add(UUID.fromString(m));
                n.balance = s.getDouble("balance", 0);
                n.tax = s.getDouble("tax", 0);
                nations.put(key, n);
            }
        }
    }

    private void saveData() {
        data.set("un.balance", unBalance);
        data.set("nations", null);
        for (Map.Entry<String, Nation> e : nations.entrySet()) {
            Nation n = e.getValue();
            String base = "nations." + e.getKey() + ".";
            data.set(base + "name", n.name);
            data.set(base + "leader", n.leader.toString());
            data.set(base + "treasurer", n.treasurer == null ? "" : n.treasurer.toString());
            List<String> m = new ArrayList<>();
            for (UUID id : n.members) m.add(id.toString());
            data.set(base + "members", m);
            data.set(base + "balance", n.balance);
            data.set(base + "tax", n.tax);
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Nation nationOf(UUID id) {
        for (Nation n : nations.values()) if (n.members.contains(id)) return n;
        return null;
    }

    private String key(Nation n) { return n.name.toLowerCase(Locale.ROOT); }

    private String money(double amount) { return econ.format(amount); }

    private static String nameOf(UUID id) {
        if (id == null) return "none";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString() : n;
    }

    /** Parses a positive amount rounded to cents, or returns -1 and tells the sender. */
    private static double amount(CommandSender s, String raw) {
        try {
            double v = Math.round(Double.parseDouble(raw) * 100) / 100.0;
            if (v > 0 && !Double.isInfinite(v)) return v;
        } catch (NumberFormatException ignored) { }
        s.sendMessage(P + "§c'" + raw + "' isn't a valid amount.");
        return -1;
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    private void tellNation(Nation n, String msg) {
        for (UUID id : n.members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(P + msg);
        }
    }

    // ---------------------------------------------------------------- daily login

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String today = LocalDate.now().toString();
        String path = "players." + p.getUniqueId() + ".last-day";
        if (today.equals(data.getString(path))) return;
        data.set(path, today);

        double reward = getConfig().getDouble("daily-reward", 25);
        if (reward > 0) {
            econ.depositPlayer(p, reward);
            p.sendMessage(P + "§aDaily login reward: " + money(reward));
        }

        Nation n = nationOf(p.getUniqueId());
        if (n != null && n.tax > 0) {
            double paid = Math.min(n.tax, econ.getBalance(p));
            if (paid > 0 && econ.withdrawPlayer(p, paid).transactionSuccess()) {
                double dues = Math.round(paid * getConfig().getDouble("un-dues-percent", 10)) / 100.0;
                n.balance += paid - dues;
                unBalance += dues;
                p.sendMessage(P + "Paid " + money(paid) + " daily tax to " + n.name
                        + (paid < n.tax ? " §c(you couldn't cover the full " + money(n.tax) + ")" : "") + "§r.");
            }
        }
        saveData();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (econ == null) return true;
        boolean ok = command.getName().equalsIgnoreCase("un") ? unCommand(sender, args) : nationCommand(sender, args);
        if (ok) saveData();
        return true;
    }

    private boolean nationCommand(CommandSender sender, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            if (nations.isEmpty()) { sender.sendMessage(P + "No nations yet. Found one with /nation create <name>."); return false; }
            sender.sendMessage(P + "Nations:");
            for (Nation n : nations.values())
                sender.sendMessage("  §e" + n.name + "§r - leader " + nameOf(n.leader) + ", " + n.members.size() + " members");
            return false;
        }
        if (sub.equals("info")) {
            Nation n = a.length > 1 ? nations.get(a[1].toLowerCase(Locale.ROOT))
                    : sender instanceof Player pl ? nationOf(pl.getUniqueId()) : null;
            if (n == null) { sender.sendMessage(P + "§cNo such nation. Try /nation list."); return false; }
            List<String> names = new ArrayList<>();
            for (UUID id : n.members) names.add(nameOf(id));
            sender.sendMessage(P + "§e" + n.name);
            sender.sendMessage("  Leader: " + nameOf(n.leader) + "   Treasurer: " + nameOf(n.treasurer));
            sender.sendMessage("  Treasury: " + money(n.balance) + "   Daily tax: " + money(n.tax));
            sender.sendMessage("  Members: " + String.join(", ", names));
            return false;
        }
        if (!(sender instanceof Player p)) {
            if (!sub.equals("help")) sender.sendMessage(P + "Only players can use that. The console can use /un.");
            else nationHelp(sender);
            return false;
        }
        UUID me = p.getUniqueId();
        Nation mine = nationOf(me);

        switch (sub) {
            case "create" -> {
                if (a.length < 2) { p.sendMessage(P + "Usage: /nation create <name>"); return false; }
                if (mine != null) { p.sendMessage(P + "§cLeave " + mine.name + " first."); return false; }
                String name = a[1];
                if (!name.matches("[A-Za-z0-9_]{2,24}")) { p.sendMessage(P + "§cNames are 2-24 letters, numbers or _."); return false; }
                if (nations.containsKey(name.toLowerCase(Locale.ROOT))) { p.sendMessage(P + "§cThat name is taken."); return false; }
                double cost = getConfig().getDouble("nation-create-cost", 500);
                if (!econ.has(p, cost)) { p.sendMessage(P + "§cFounding a nation costs " + money(cost) + "."); return false; }
                econ.withdrawPlayer(p, cost);
                unBalance += cost;
                Nation n = new Nation();
                n.name = name;
                n.leader = me;
                n.members.add(me);
                nations.put(key(n), n);
                Bukkit.broadcastMessage(P + "§a" + p.getName() + " founded the nation of " + name + "!");
                return true;
            }
            case "join" -> {
                if (a.length < 2) { p.sendMessage(P + "Usage: /nation join <name>"); return false; }
                if (mine != null) { p.sendMessage(P + "§cLeave " + mine.name + " first."); return false; }
                String k = a[1].toLowerCase(Locale.ROOT);
                Nation n = nations.get(k);
                if (n == null || !k.equals(invites.get(me))) { p.sendMessage(P + "§cYou need an invite from that nation's leader."); return false; }
                invites.remove(me);
                n.members.add(me);
                tellNation(n, "§a" + p.getName() + " joined " + n.name + ".");
                return true;
            }
            case "help" -> { nationHelp(p); return false; }
            default -> { }
        }

        if (mine == null) { p.sendMessage(P + "§cYou're not in a nation. /nation create <name> or ask a leader to invite you."); return false; }
        boolean leader = me.equals(mine.leader);

        switch (sub) {
            case "leave" -> {
                if (leader) { p.sendMessage(P + "§cLeaders can't leave. Use /nation setleader <player> or /nation disband."); return false; }
                mine.members.remove(me);
                if (me.equals(mine.treasurer)) mine.treasurer = null;
                p.sendMessage(P + "You left " + mine.name + ".");
                tellNation(mine, p.getName() + " left the nation.");
                return true;
            }
            case "deposit" -> {
                if (a.length < 2) { p.sendMessage(P + "Usage: /nation deposit <amount>"); return false; }
                double v = amount(p, a[1]);
                if (v < 0) return false;
                if (!econ.has(p, v)) { p.sendMessage(P + "§cYou don't have " + money(v) + "."); return false; }
                econ.withdrawPlayer(p, v);
                mine.balance += v;
                tellNation(mine, p.getName() + " deposited " + money(v) + ". Treasury: " + money(mine.balance));
                return true;
            }
            default -> { }
        }

        // Everything below needs the leader (or the treasurer for money moves).
        switch (sub) {
            case "withdraw", "pay" -> {
                if (!mine.canManage(me)) { p.sendMessage(P + "§cOnly the leader or treasurer can spend the treasury."); return false; }
                boolean pay = sub.equals("pay");
                if (a.length < (pay ? 3 : 2)) { p.sendMessage(P + "Usage: /nation " + (pay ? "pay <player> <amount>" : "withdraw <amount>")); return false; }
                OfflinePlayer to = pay ? findPlayer(a[1]) : p;
                if (to == null) { p.sendMessage(P + "§cNo player called " + a[1] + "."); return false; }
                double v = amount(p, a[pay ? 2 : 1]);
                if (v < 0) return false;
                if (mine.balance < v) { p.sendMessage(P + "§cThe treasury only has " + money(mine.balance) + "."); return false; }
                mine.balance -= v;
                econ.depositPlayer(to, v);
                tellNation(mine, p.getName() + " paid " + money(v) + " to " + to.getName() + ". Treasury: " + money(mine.balance));
                return true;
            }
            default -> { }
        }
        if (!leader) { p.sendMessage(P + "§cOnly the leader of " + mine.name + " can do that."); return false; }

        switch (sub) {
            case "invite" -> {
                if (a.length < 2) { p.sendMessage(P + "Usage: /nation invite <player>"); return false; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { p.sendMessage(P + "§c" + a[1] + " needs to be online."); return false; }
                invites.put(t.getUniqueId(), key(mine));
                t.sendMessage(P + "§a" + p.getName() + " invited you to " + mine.name + ". Type /nation join " + mine.name);
                p.sendMessage(P + "Invited " + t.getName() + ".");
                return false;
            }
            case "kick" -> {
                if (a.length < 2) { p.sendMessage(P + "Usage: /nation kick <player>"); return false; }
                OfflinePlayer t = findPlayer(a[1]);
                if (t == null || !mine.members.contains(t.getUniqueId()) || t.getUniqueId().equals(me)) {
                    p.sendMessage(P + "§c" + a[1] + " isn't a member you can kick."); return false;
                }
                mine.members.remove(t.getUniqueId());
                if (t.getUniqueId().equals(mine.treasurer)) mine.treasurer = null;
                tellNation(mine, t.getName() + " was removed from " + mine.name + ".");
                return true;
            }
            case "tax" -> {
                double max = getConfig().getDouble("max-daily-tax", 20);
                double v;
                try { v = Math.round(Double.parseDouble(a.length > 1 ? a[1] : "x") * 100) / 100.0; }
                catch (NumberFormatException e) { p.sendMessage(P + "Usage: /nation tax <0-" + max + ">"); return false; }
                if (v < 0 || v > max) { p.sendMessage(P + "§cDaily tax must be between 0 and " + money(max) + "."); return false; }
                mine.tax = v;
                tellNation(mine, "Daily tax is now " + money(v) + ", taken the first time each member logs in each day.");
                return true;
            }
            case "treasurer", "setleader" -> {
                if (a.length < 2) { p.sendMessage(P + "Usage: /nation " + sub + " <player>"); return false; }
                OfflinePlayer t = findPlayer(a[1]);
                if (t == null || !mine.members.contains(t.getUniqueId())) { p.sendMessage(P + "§c" + a[1] + " isn't in your nation."); return false; }
                if (sub.equals("treasurer")) mine.treasurer = t.getUniqueId();
                else mine.leader = t.getUniqueId();
                tellNation(mine, t.getName() + " is now the " + (sub.equals("treasurer") ? "treasurer" : "leader") + " of " + mine.name + ".");
                return true;
            }
            case "disband" -> {
                if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) {
                    p.sendMessage(P + "§cThis deletes " + mine.name + ". The treasury (" + money(mine.balance) + ") goes to you. Type /nation disband confirm");
                    return false;
                }
                econ.depositPlayer(p, mine.balance);
                nations.remove(key(mine));
                Bukkit.broadcastMessage(P + "The nation of " + mine.name + " has been disbanded.");
                return true;
            }
            default -> { nationHelp(p); return false; }
        }
    }

    private void nationHelp(CommandSender s) {
        s.sendMessage(P + "Nation commands:");
        s.sendMessage("  /nation create <name> §7- found a nation (" + money(getConfig().getDouble("nation-create-cost", 500)) + ")");
        s.sendMessage("  /nation join <name> | leave | info [name] | list");
        s.sendMessage("  /nation deposit <amount> §7- put money in the treasury");
        s.sendMessage("  §7Leader/treasurer:§r /nation withdraw <amount> | pay <player> <amount>");
        s.sendMessage("  §7Leader:§r /nation invite | kick | treasurer | setleader <player>, tax <amount>, disband");
    }

    /** Resolves a nation or player name for UN commands. */
    private Object target(String name) {
        Nation n = nations.get(name.toLowerCase(Locale.ROOT));
        return n != null ? n : findPlayer(name);
    }

    private double balanceOf(Object t) { return t instanceof Nation n ? n.balance : econ.getBalance((OfflinePlayer) t); }

    private void take(Object t, double v) {
        if (t instanceof Nation n) n.balance -= v;
        else econ.withdrawPlayer((OfflinePlayer) t, v);
    }

    private void give(Object t, double v) {
        if (t instanceof Nation n) n.balance += v;
        else econ.depositPlayer((OfflinePlayer) t, v);
    }

    private static String label(Object t) { return t instanceof Nation n ? n.name : ((OfflinePlayer) t).getName(); }

    private boolean unCommand(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "balance" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("balance")) { s.sendMessage(P + "UN treasury: " + money(unBalance)); return false; }
        if (!s.hasPermission("unbank.sg")) { s.sendMessage(P + "§cOnly the Secretary-General can do that."); return false; }

        switch (sub) {
            case "war" -> {
                if (a.length < 3) { s.sendMessage(P + "Usage: /un war <attacking nation> <defending nation>"); return false; }
                Nation att = nations.get(a[1].toLowerCase(Locale.ROOT));
                Nation def = nations.get(a[2].toLowerCase(Locale.ROOT));
                if (att == null || def == null || att == def) { s.sendMessage(P + "§cName two different nations."); return false; }
                double fee = getConfig().getDouble("war-fee", 1000);
                if (att.balance < fee) { s.sendMessage(P + "§c" + att.name + "'s treasury has " + money(att.balance) + ", the war fee is " + money(fee) + "."); return false; }
                att.balance -= fee;
                unBalance += fee;
                Bukkit.broadcastMessage(P + "§c§lWAR: §r§c" + att.name + " has declared war on " + def.name
                        + ". Approved by the Secretary-General. Bases may now be raided.");
                return true;
            }
            case "peace" -> {
                if (a.length < 3) { s.sendMessage(P + "Usage: /un peace <nation> <nation>"); return false; }
                Bukkit.broadcastMessage(P + "§a§lPEACE: §r§a" + a[1] + " and " + a[2] + " are at peace. Raiding between them must stop.");
                return false;
            }
            case "fine", "award" -> {
                if (a.length < 3) { s.sendMessage(P + "Usage: /un " + sub + " <nation or player> <amount>"); return false; }
                Object t = target(a[1]);
                if (t == null) { s.sendMessage(P + "§cNo nation or player called " + a[1] + "."); return false; }
                double v = amount(s, a[2]);
                if (v < 0) return false;
                if (sub.equals("fine")) {
                    double took = Math.min(v, balanceOf(t));
                    take(t, took);
                    unBalance += took;
                    Bukkit.broadcastMessage(P + "The UN fined " + label(t) + " " + money(took) + ".");
                } else {
                    if (unBalance < v) { s.sendMessage(P + "§cThe UN treasury only has " + money(unBalance) + "."); return false; }
                    unBalance -= v;
                    give(t, v);
                    Bukkit.broadcastMessage(P + "The UN awarded " + label(t) + " " + money(v) + ".");
                }
                return true;
            }
            case "reparations" -> {
                if (a.length < 4) { s.sendMessage(P + "Usage: /un reparations <from> <to> <amount>"); return false; }
                Object from = target(a[1]), to = target(a[2]);
                if (from == null || to == null) { s.sendMessage(P + "§cCheck both names."); return false; }
                double v = amount(s, a[3]);
                if (v < 0) return false;
                double moved = Math.min(v, balanceOf(from));
                take(from, moved);
                give(to, moved);
                Bukkit.broadcastMessage(P + label(from) + " paid " + money(moved) + " in reparations to " + label(to) + ".");
                return true;
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage(P + "Config reloaded.");
                return false;
            }
            default -> {
                s.sendMessage(P + "UN commands: /un balance | war <attacker> <defender> | peace <a> <b> | fine <who> <amount> | award <who> <amount> | reparations <from> <to> <amount> | reload");
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(command.getName().equalsIgnoreCase("un")
                    ? List.of("balance", "war", "peace", "fine", "award", "reparations", "reload")
                    : List.of("create", "join", "leave", "info", "list", "deposit", "withdraw", "pay",
                              "invite", "kick", "tax", "treasurer", "setleader", "disband"));
        } else {
            for (Nation n : nations.values()) options.add(n.name);
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
