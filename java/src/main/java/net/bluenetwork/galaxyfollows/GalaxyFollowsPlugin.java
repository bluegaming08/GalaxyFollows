package net.bluenetwork.galaxyfollows;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GalaxyFollows: votifier-style follow rewards for BlueGalaxy.
 *
 * SETUP (no keys, no pairing - the plugin is open source, there is
 * nothing secret to configure):
 *  1. put YOUR BlueGalaxy username in config.yml (username:)
 *  2. set your reward commands
 *  3. /galaxyfollows reload
 *
 * Careful mode (follow-hold-hours > 0): a follow must survive the hold
 * window before it earns anything. The plugin re-verifies with
 * /v1/follow/status before each reward; an unfollowed follow never pays.
 */
public final class GalaxyFollowsPlugin extends JavaPlugin implements Listener {

    private final ApiClient api = new ApiClient();

    /** Event ids we have handled but not yet acked (acked on next poll). */
    private final List<String> ackQueue = new ArrayList<>();

    /** Event ids currently being handled in this poll cycle. */
    private final Map<String, String> inflight = new ConcurrentHashMap<>();

    /** Exact names of online players (maintained by join/quit, read async). */
    private final java.util.Set<String> onlineNames = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private BukkitTask pollTask;
    private long lastPollOk = 0;
    private long rewardedTotal = 0;
    private long droppedUnfollows = 0;

    // ---------------------------------------------------------------- enable

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) onlineNames.add(p.getName());
        restartPoller();
        if (isUsernameSet()) {
            getLogger().info("GalaxyFollows enabled. Rewarding followers of '"
                    + username + "', polling every " + pollInterval + "s, hold window "
                    + holdHours + "h.");
        } else {
            getLogger().warning("Set your BlueGalaxy username in plugins/GalaxyFollows/config.yml, then run /galaxyfollows reload.");
        }
    }

    @Override
    public void onDisable() {
        stopPoller();
    }

    // ---------------------------------------------------------------- config

    private volatile String username = "";
    private volatile List<String> rewardCommands = List.of();
    private volatile String rewardMessage = "";
    private volatile String prefix = "";
    private volatile long pollInterval = 30;
    private volatile int maxAgeDays = 30;
    private volatile int holdHours = 24;
    private volatile boolean enabled = true;

    private boolean isUsernameSet() {
        return username != null && !username.isBlank()
                && !username.equals("CHANGE_ME") && !username.equals("change_me");
    }

    private void readConfig() {
        reloadConfig();
        enabled = getConfig().getBoolean("galaxyfollows.enabled", true);
        prefix = getConfig().getString("galaxyfollows.prefix", "");
        username = getConfig().getString("galaxyfollows.username", "").trim().toLowerCase();
        if (username.startsWith("@")) username = username.substring(1);
        rewardCommands = List.copyOf(getConfig().getStringList("galaxyfollows.reward-commands"));
        rewardMessage = getConfig().getString("galaxyfollows.reward-message", "");
        pollInterval = Math.max(10, getConfig().getLong("galaxyfollows.poll-interval-seconds", 30));
        maxAgeDays = Math.max(1, getConfig().getInt("galaxyfollows.max-age-days", 30));
        holdHours = Math.max(0, getConfig().getInt("galaxyfollows.follow-hold-hours", 24));
    }

    private void restartPoller() {
        stopPoller();
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::poll, 100L, pollInterval * 20L);
    }

    private void stopPoller() {
        if (pollTask != null) { pollTask.cancel(); pollTask = null; }
    }

    // ---------------------------------------------------------------- polling

    /** One poll cycle: ack handled events, collect pending, deliver. */
    private void poll() {
        if (!enabled || !isUsernameSet()) return;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("watch", List.of(username));
        body.put("max_age_days", maxAgeDays);
        synchronized (ackQueue) {
            if (!ackQueue.isEmpty()) {
                body.put("ack", List.copyOf(ackQueue));
                ackQueue.clear();
            }
        }

        api.post("/v1/follow/poll", body).thenAccept(result -> {
            if (!result.success()) {
                getLogger().warning("poll failed: " + result.error()
                        + (result.status() == 404 ? " (is your BlueGalaxy username correct?)" : ""));
                return;
            }
            lastPollOk = System.currentTimeMillis();
            List<Object> unknown = result.arr("unknown_handles");
            if (unknown != null && !unknown.isEmpty()) {
                getLogger().warning("not a BlueGalaxy account: " + unknown
                        + " (check username in the config)");
            }
            List<Object> events = result.arr("events");
            if (events == null) return;
            for (Object o : events) {
                if (!(o instanceof Map<?, ?> m)) continue;
                handleEvent(asMap(m));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    // ---------------------------------------------------------------- rewards

    private void handleEvent(Map<String, Object> ev) {
        String id = Json.str(ev, "id");
        String mcUsername = Json.str(ev, "mc_username");
        String watchedHandle = Json.str(ev, "watched_handle");
        String followerHandle = Json.str(ev, "follower_handle");
        String followedAt = Json.str(ev, "followed_at");
        if (id == null) return;

        // follower has no linked Minecraft account (system accounts):
        // ack and skip, they can never be rewarded in-game.
        if (mcUsername == null || mcUsername.isBlank()) {
            synchronized (ackQueue) { ackQueue.add(id); }
            return;
        }

        // one delivery attempt per event per poll cycle
        if (inflight.putIfAbsent(id, mcUsername) != null) return;

        if (holdHours > 0) {
            long ageMs = followedAt == null ? Long.MAX_VALUE : (System.currentTimeMillis() - parseIsoMs(followedAt));
            if (ageMs < holdHours * 3600_000L) {
                // follow is younger than the hold window: leave it pending
                // server-side, re-check on a later poll.
                inflight.remove(id);
                return;
            }
            // due for the careful-mode check: still following?
            checkStillFollowing(id, mcUsername, watchedHandle, followerHandle);
            return;
        }

        dispatchIfOnline(id, mcUsername, followerHandle, watchedHandle);
    }

    /**
     * Careful mode: ask BlueGalaxy whether the follow still exists.
     * Unfollowed during the hold window -> ack, never reward.
     */
    private void checkStillFollowing(String id, String mcUsername,
                                     String watchedHandle, String followerHandle) {
        if (watchedHandle == null || followerHandle == null) {
            // cannot verify: do not pay, do not burn - retry next poll
            inflight.remove(id);
            return;
        }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("account", watchedHandle);
        check.put("follower", followerHandle);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("checks", List.of(check));

        api.post("/v1/follow/status", body).thenAccept(result -> {
            if (!result.success()) {
                // network problem: retry next poll, never drop on a hiccup
                inflight.remove(id);
                return;
            }
            boolean stillFollowing = false;
            if (result.arr("results") != null) {
                for (Object o : result.arr("results")) {
                    if (o instanceof Map<?, ?> m && Boolean.TRUE.equals(asMap(m).get("following"))) {
                        stillFollowing = true;
                    }
                }
            }
            if (!stillFollowing) {
                droppedUnfollows++;
                getLogger().info("Follow was undone before the reward (" + followerHandle
                        + " -> " + watchedHandle + "): no reward, event closed.");
                synchronized (ackQueue) { ackQueue.add(id); }
                inflight.remove(id);
                return;
            }
            dispatchIfOnline(id, mcUsername, followerHandle, watchedHandle);
        });
    }

    private void dispatchIfOnline(String id, String mcUsername,
                                  String followerHandle, String watchedHandle) {
        // async thread: consult the join/quit-maintained set, never the
        // Bukkit API (not thread-safe off the main thread).
        if (!onlineNames.contains(mcUsername)) {
            // not on the server right now: leave pending server-side,
            // the join listener retries when they show up.
            inflight.remove(id);
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayerExact(mcUsername);
            if (player == null || !player.isOnline()) { // race: left meanwhile
                inflight.remove(id);
                return;
            }
            deliver(id, player, followerHandle, watchedHandle);
        });
    }

    /** Runs on the MAIN thread: dispatch commands + message, then ack. */
    private void deliver(String eventId, Player player, String followerHandle, String watchedHandle) {
        try {
            for (String template : rewardCommands) {
                String cmd = template
                        .replace("{player}", player.getName())
                        .replace("{handle}", followerHandle == null ? "" : followerHandle)
                        .replace("{account}", watchedHandle == null ? "" : watchedHandle);
                if (cmd.isBlank()) continue;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            if (rewardMessage != null && !rewardMessage.isBlank()) {
                String msg = rewardMessage
                        .replace("{player}", player.getName())
                        .replace("{handle}", followerHandle == null ? "" : followerHandle)
                        .replace("{account}", watchedHandle == null ? "" : watchedHandle);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + msg));
            }
            rewardedTotal++;
            getLogger().info("Reward delivered: " + player.getName()
                    + " followed " + (watchedHandle == null ? "?" : watchedHandle) + " on BlueGalaxy.");
        } catch (Throwable t) {
            getLogger().warning("reward dispatch failed for " + player.getName() + ": " + t.getMessage());
        } finally {
            synchronized (ackQueue) { ackQueue.add(eventId); }
            inflight.remove(eventId);
        }
    }

    // ---------------------------------------------------------------- events

    /** A join might be a pending follower: poll right away (async). */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        onlineNames.add(event.getPlayer().getName());
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::poll, 40L); // 2s after join
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        onlineNames.remove(event.getPlayer().getName());
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        if ("reload".equals(sub)) {
            readConfig();
            restartPoller();
            sendPrefixed(sender, ChatColor.GREEN + "Config reloaded. Rewarding followers of: "
                    + (isUsernameSet() ? username : "NOT SET (fix username in config.yml)"));
            return true;
        }
        long ago = lastPollOk == 0 ? -1 : (System.currentTimeMillis() - lastPollOk) / 1000;
        sendPrefixed(sender, "GalaxyFollows v" + getDescription().getVersion()
                + " | username: " + (isUsernameSet() ? username : "NOT SET (edit config.yml)")
                + " | last poll: " + (ago < 0 ? "never" : ago + "s ago")
                + " | hold: " + holdHours + "h");
        sendPrefixed(sender, "Rewards delivered: " + rewardedTotal
                + " | unfollows caught: " + droppedUnfollows
                + " | pending acks: " + ackQueue.size());
        return true;
    }

    private void sendPrefixed(CommandSender to, String msg) {
        to.sendMessage(ChatColor.translateAlternateColorCodes('&',
                (prefix == null || prefix.isBlank() ? "" : prefix) + msg));
    }

    // ---------------------------------------------------------------- utils

    /** Parse ISO-8601 timestamps from Postgres (up to 6 fractional digits). */
    static long parseIsoMs(String iso) {
        try {
            String s = iso.trim();
            // normalize fractional seconds to exactly 3 digits
            int dot = s.indexOf('.');
            if (dot >= 0) {
                int end = dot + 1;
                while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
                String frac = s.substring(dot + 1, end);
                String cut = frac.substring(0, Math.min(3, frac.length()));
                while (cut.length() < 3) cut += "0";
                s = s.substring(0, dot) + "." + cut + s.substring(end);
            }
            DateTimeFormatter fmt = new DateTimeFormatterBuilder()
                    .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toFormatter();
            return OffsetDateTime.parse(s, fmt).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis(); // unknown age: treat as due
        }
    }
}
