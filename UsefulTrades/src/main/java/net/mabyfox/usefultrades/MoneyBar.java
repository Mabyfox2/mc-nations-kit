package net.mabyfox.usefultrades;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A small sidebar with the player's money, for Java and Bedrock (Geyser shows sidebars too).
 * On by default; /moneybar turns it off or on again for that player (remembered).
 *
 * Reads the balance from whatever economy is registered with Vault (EssentialsX here), through
 * reflection so the plugin doesn't need Vault to build or load.
 */
final class MoneyBar implements Listener, CommandExecutor {

    private static final String ENTRY = "Money";
    private static final String VAULT_ECONOMY = "net.milkbowl.vault.economy.Economy";

    private final UsefulTrades plugin;
    private final NamespacedKey hiddenKey;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private boolean enabled = true;
    private Component title = Component.text("UN of MC", NamedTextColor.GOLD, TextDecoration.BOLD);
    private Object economy;
    private Method getBalance;

    MoneyBar(UsefulTrades plugin) {
        this.plugin = plugin;
        this.hiddenKey = new NamespacedKey(plugin, "moneybar_hidden");
    }

    void load(ConfigurationSection s) {
        enabled = s == null || s.getBoolean("enabled", true);
        String t = s == null ? null : s.getString("title");
        if (t != null && !t.isBlank()) title = Component.text(t, NamedTextColor.GOLD, TextDecoration.BOLD);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (shows(p)) update(p);
            else hide(p);
        }
    }

    void start() {
        PluginCommand cmd = plugin.getCommand("moneybar");
        if (cmd != null) cmd.setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (shows(p)) update(p);
            }
        }, 40L, 40L);
    }

    private boolean shows(Player p) {
        return enabled && !p.getPersistentDataContainer().has(hiddenKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (shows(event.getPlayer())) update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players have a money bar.");
            return true;
        }
        if (!enabled) {
            p.sendMessage(Component.text("The money bar is turned off on this server. Use /bal to see your money.", NamedTextColor.YELLOW));
            return true;
        }
        if (shows(p)) {
            p.getPersistentDataContainer().set(hiddenKey, PersistentDataType.BYTE, (byte) 1);
            hide(p);
            p.sendMessage(Component.text("Money bar hidden. Type /moneybar to show it again.", NamedTextColor.YELLOW));
        } else {
            p.getPersistentDataContainer().remove(hiddenKey);
            update(p);
            p.sendMessage(Component.text("Money bar shown. Type /moneybar to hide it.", NamedTextColor.GREEN));
        }
        return true;
    }

    private void hide(Player p) {
        if (boards.remove(p.getUniqueId()) != null) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void update(Player p) {
        Double balance = balance(p);
        if (balance == null) return; // no economy yet
        Scoreboard board = boards.get(p.getUniqueId());
        if (board == null || p.getScoreboard() != board) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = board.registerNewObjective("money", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            boards.put(p.getUniqueId(), board);
            p.setScoreboard(board);
        }
        Objective obj = board.getObjective("money");
        if (obj == null) return;
        obj.displayName(title);
        Score score = obj.getScore(ENTRY);
        // Java shows the formatted amount; a client that ignores number formats shows the whole dollars instead.
        score.numberFormat(NumberFormat.fixed(Component.text(format(balance), NamedTextColor.GREEN)));
        score.setScore((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.floor(balance))));
    }

    static String format(double amount) {
        return String.format(Locale.US, "$%,.2f", amount);
    }

    /** The player's balance, or null if no Vault economy is registered (yet). */
    private Double balance(OfflinePlayer p) {
        try {
            if (!findEconomy()) return null;
            return ((Number) getBalance.invoke(economy, p)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            economy = null;
            return null;
        }
    }

    private boolean findEconomy() throws ReflectiveOperationException {
        if (economy != null) return true;
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (!service.getName().equals(VAULT_ECONOMY)) continue;
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(service);
            if (rsp == null) continue;
            getBalance = service.getMethod("getBalance", OfflinePlayer.class);
            economy = rsp.getProvider();
            return true;
        }
        return false;
    }

    int selfTest(CommandSender sender) {
        boolean formatOk = format(1234.5).equals("$1,234.50") && format(0.01).equals("$0.01");
        sender.sendMessage("[selftest] money bar format: " + format(1234.5) + ", " + format(0.01) + " -> " + (formatOk ? "OK" : "FAIL"));
        boolean econOk;
        try {
            econOk = findEconomy() && (!(sender instanceof Player p) || balance(p) != null);
        } catch (ReflectiveOperationException ex) {
            econOk = false;
        }
        sender.sendMessage("[selftest] money bar economy: " + (econOk ? "found" : "NOT FOUND") + " -> " + (econOk ? "OK" : "FAIL"));
        return (formatOk ? 0 : 1) + (econOk ? 0 : 1);
    }
}
