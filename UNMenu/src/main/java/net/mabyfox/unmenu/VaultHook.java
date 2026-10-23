package net.mabyfox.unmenu;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Reads balances through Vault. Only loaded when Vault (or VaultUnlocked) is installed. */
final class VaultHook {

    private final Economy econ;

    private VaultHook(Economy econ) { this.econ = econ; }

    static VaultHook find(Server server) {
        RegisteredServiceProvider<Economy> rsp = server.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : new VaultHook(rsp.getProvider());
    }

    double balance(Player p) { return econ.getBalance(p); }

    String format(double v) { return econ.format(v); }
}
