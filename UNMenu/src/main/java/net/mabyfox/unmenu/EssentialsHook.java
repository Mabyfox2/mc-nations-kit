package net.mabyfox.unmenu;

import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.User;
import net.ess3.api.events.TPARequestEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything that touches EssentialsX. Only loaded when EssentialsX is installed. */
final class EssentialsHook implements Listener {

    private final UNMenu plugin;
    private final IEssentials ess;

    EssentialsHook(UNMenu plugin) {
        this.plugin = plugin;
        this.ess = (IEssentials) plugin.getServer().getPluginManager().getPlugin("Essentials");
    }

    List<String> homes(Player p) {
        User u = ess.getUser(p);
        if (u == null) return Collections.emptyList();
        List<String> homes = new ArrayList<>(u.getHomes());
        homes.sort(String.CASE_INSENSITIVE_ORDER);
        return homes;
    }

    int homeLimit(Player p) {
        User u = ess.getUser(p);
        return u == null ? 0 : ess.getSettings().getHomeLimit(u);
    }

    List<String> warps() {
        try {
            List<String> w = new ArrayList<>(ess.getWarps().getList());
            w.sort(String.CASE_INSENSITIVE_ORDER);
            return w;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTpa(TPARequestEvent e) {
        if (!e.getRequester().isPlayer() || e.getTarget() == null) return;
        Player target = e.getTarget().getBase();
        if (target != null) plugin.tpaPopup(target, e.getRequester().getPlayer().getName(), e.isTeleportHere());
    }
}
