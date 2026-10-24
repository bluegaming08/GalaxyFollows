package cn.nukkit;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.ConsoleCommandSender;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginManager;
import cn.nukkit.scheduler.ServerScheduler;
import java.util.Map;
import java.util.UUID;
/** COMPILE-ONLY STUB - the real class comes from PowerNukkitX/Nukkit at runtime. */
public class Server {
    public static Server getInstance() { throw new UnsupportedOperationException(); }
    public PluginManager getPluginManager() { throw new UnsupportedOperationException(); }
    public ServerScheduler getScheduler() { throw new UnsupportedOperationException(); }
    public void dispatchCommand(CommandSender sender, String command) { throw new UnsupportedOperationException(); }
    public Map<UUID, Player> getOnlinePlayers() { throw new UnsupportedOperationException(); }
    public Player getPlayerExact(String name) { throw new UnsupportedOperationException(); }
}
