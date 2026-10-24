package cn.nukkit.plugin;
import cn.nukkit.Server;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.PluginLogger;
/** COMPILE-ONLY STUB. */
public abstract class PluginBase implements Plugin {
    public void onEnable() {}
    public void onDisable() {}
    public PluginLogger getLogger() { throw new UnsupportedOperationException(); }
    public Config getConfig() { throw new UnsupportedOperationException(); }
    public void saveDefaultConfig() { throw new UnsupportedOperationException(); }
    public void reloadConfig() { throw new UnsupportedOperationException(); }
    public Server getServer() { throw new UnsupportedOperationException(); }
    public PluginDescription getDescription() { throw new UnsupportedOperationException(); }
}
