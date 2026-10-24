package cn.nukkit.scheduler;
import cn.nukkit.plugin.Plugin;
/** COMPILE-ONLY STUB. */
public class ServerScheduler {
    public int scheduleRepeatingTask(Plugin p, Runnable r, int period, boolean async) { throw new UnsupportedOperationException(); }
    public int scheduleTask(Plugin p, Runnable r, boolean async) { throw new UnsupportedOperationException(); }
    public int scheduleDelayedTask(Plugin p, Runnable r, int delay, boolean async) { throw new UnsupportedOperationException(); }
    public void cancelTask(int id) { throw new UnsupportedOperationException(); }
}
