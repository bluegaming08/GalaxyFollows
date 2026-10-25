package net.bluenetwork.galaxyfollows;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandExecutor;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.command.ConsoleCommandSender;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.scheduler.ServerScheduler;
import org.powernukkitx.scheduler.TaskHandler;
import org.powernukkitx.utils.TextFormat;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GalaxyFollows for Bedrock, PowerNukkitX 3.x edition (org.powernukkitx API).
 *
 * Same as the Paper edition: no keys, no pairing - the plugin identifies
 * itself only with the site username it rewards. username: in config.yml,
 * pick your reward commands, done.
 */
public final class GalaxyFollowsPlugin extends PluginBase implements Listener, CommandExecutor {

    private static final String API_URL =
            "https://tybyyeeapetvzjenzjid.supabase.co/functions/v1/plugin";

    /** Event ids handled but not yet acked (acked on next poll). */
    private final List<String> ackQueue = new ArrayList<>();

    /** Pending events whose follower has no linked Minecraft account (warn once). */
    private final java.util.Set<String> warnedNoMc =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Event ids currently being handled in this poll cycle. */
    private final Map<String, String> inflight = new ConcurrentHashMap<>();

    /** Exact names of online players (join/quit maintained, read async). */
    private final java.util.Set<String> onlineNames =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private long lastPollOk = 0;
    private long rewardedTotal = 0;
    private long droppedUnfollows = 0;
    private TaskHandler pollTask = null;

    private volatile String username = "";
    private volatile List<String> rewardCommands = List.of();
    private volatile String rewardMessage = "";
    private volatile String prefix = "";
    private volatile long pollInterval = 30;
    private volatile int maxAgeDays = 30;
    private volatile int holdHours = 24;
    private volatile boolean enabled = true;

    // ------------------------------------------------------------------ life

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers().values()) onlineNames.add(p.getName());
        restartPoller();
        if (isUsernameSet()) {
            getLogger().info("GalaxyFollows enabled. Rewarding followers of '" + username
                    + "', polling every " + pollInterval + "s, hold window " + holdHours + "h.");
        } else {
            getLogger().warning("Set your BlueGalaxy username in the GalaxyFollows config.yml, then run /galaxyfollows reload.");
        }
    }

    @Override
    public void onDisable() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    private void restartPoller() {
        if (pollTask != null) pollTask.cancel();
        pollTask = getServer().getScheduler().scheduleRepeatingTask(this, this::poll,
                (int) (pollInterval * 20), true);
    }

    // ------------------------------------------------------------------ config

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
        rewardCommands = new ArrayList<>(getConfig().getStringList("galaxyfollows.reward-commands"));
        rewardMessage = getConfig().getString("galaxyfollows.reward-message", "");
        pollInterval = Math.max(10, (long) getConfig().getInt("galaxyfollows.poll-interval-seconds", 30));
        maxAgeDays = Math.max(1, getConfig().getInt("galaxyfollows.max-age-days", 30));
        holdHours = Math.max(0, getConfig().getInt("galaxyfollows.follow-hold-hours", 24));
    }

    // ------------------------------------------------------------------ polling

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

        Result result = post("/v1/follow/poll", body);
        if (!result.success) {
            getLogger().warning("poll failed: " + result.error
                    + (result.status == 404 ? " (is your BlueGalaxy username correct?)" : ""));
            return;
        }
        lastPollOk = System.currentTimeMillis();
        List<String> unknown = strArr(result.json.get("unknown_handles"));
        if (!unknown.isEmpty()) {
            getLogger().warning("not a BlueGalaxy account: " + unknown + " (check username in the config)");
        }
        List<Map<String, Object>> events = objArr(result.json.get("events"));
        for (Map<String, Object> ev : events) handleEvent(ev);
    }

    private void handleEvent(Map<String, Object> ev) {
        String id = str(ev.get("id"));
        String mcUsername = str(ev.get("mc_username"));
        String watchedHandle = str(ev.get("watched_handle"));
        String followerHandle = str(ev.get("follower_handle"));
        String followedAt = str(ev.get("followed_at"));
        if (id == null) return;

        if (mcUsername == null || mcUsername.isBlank()) {
            // follower has no linked Minecraft account yet: keep the event
            // pending - it becomes rewardable the moment they link one. Never
            // ack it (acking would burn the reward forever).
            if (warnedNoMc.add(id)) {
                getLogger().warning("Follower '@" + (followerHandle == null ? "?" : followerHandle)
                        + "' has no Minecraft account linked on BlueGalaxy yet. Their follow reward"
                        + " will be delivered automatically once they link one (event stays pending).");
            }
            return;
        }
        warnedNoMc.remove(id);
        if (inflight.putIfAbsent(id, mcUsername) != null) return;

        if (holdHours > 0) {
            long ageMs = followedAt == null ? Long.MAX_VALUE
                    : (System.currentTimeMillis() - parseIsoMs(followedAt));
            if (ageMs < holdHours * 3600_000L) {
                inflight.remove(id);
                return;
            }
            checkStillFollowing(id, mcUsername, watchedHandle, followerHandle);
            return;
        }
        dispatchIfOnline(id, mcUsername, followerHandle, watchedHandle);
    }

    private void checkStillFollowing(String id, String mcUsername,
                                     String watchedHandle, String followerHandle) {
        if (watchedHandle == null || followerHandle == null) {
            inflight.remove(id);
            return;
        }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("account", watchedHandle);
        check.put("follower", followerHandle);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("checks", List.of(check));

        Result result = post("/v1/follow/status", body);
        if (!result.success) {
            inflight.remove(id);
            return;
        }
        boolean stillFollowing = false;
        for (Map<String, Object> r : objArr(result.json.get("results"))) {
            if (Boolean.TRUE.equals(r.get("following"))) stillFollowing = true;
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
    }

    private void dispatchIfOnline(String id, String mcUsername,
                                  String followerHandle, String watchedHandle) {
        if (!onlineNames.contains(mcUsername)) {
            inflight.remove(id);
            return;
        }
        // async task -> hop to the main thread to dispatch the reward
        getServer().getScheduler().scheduleTask(this, () -> {
            Player player = getServer().getPlayerExact(mcUsername);
            if (player == null || !player.isOnline()) {
                inflight.remove(id);
                return;
            }
            deliver(id, player, followerHandle, watchedHandle);
        }, true);
    }

    private void deliver(String eventId, Player player, String followerHandle, String watchedHandle) {
        try {
            for (String template : rewardCommands) {
                String cmd = template
                        .replace("{player}", player.getName())
                        .replace("{handle}", followerHandle == null ? "" : followerHandle)
                        .replace("{account}", watchedHandle == null ? "" : watchedHandle);
                if (cmd.isBlank()) continue;
                getServer().executeCommand(new ConsoleCommandSender(), cmd);
            }
            if (rewardMessage != null && !rewardMessage.isBlank()) {
                String msg = rewardMessage
                        .replace("{player}", player.getName())
                        .replace("{handle}", followerHandle == null ? "" : followerHandle)
                        .replace("{account}", watchedHandle == null ? "" : watchedHandle);
                player.sendMessage(color(prefix + msg));
            }
            rewardedTotal++;
            getLogger().info("Reward delivered: " + player.getName() + " followed "
                    + (watchedHandle == null ? "?" : watchedHandle) + " on BlueGalaxy.");
        } catch (Throwable t) {
            getLogger().warning("reward dispatch failed for " + player.getName() + ": " + t.getMessage());
        } finally {
            synchronized (ackQueue) { ackQueue.add(eventId); }
            inflight.remove(eventId);
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        onlineNames.add(event.getPlayer().getName());
        getServer().getScheduler().scheduleDelayedTask(this, this::poll, 40, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        onlineNames.remove(event.getPlayer().getName());
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        if ("reload".equals(sub)) {
            readConfig();
            restartPoller();
            sendPrefixed(sender, "&aConfig reloaded. Rewarding followers of: "
                    + (isUsernameSet() ? username : "NOT SET (fix username in the config)"));
            return true;
        }
        long ago = lastPollOk == 0 ? -1 : (System.currentTimeMillis() - lastPollOk) / 1000;
        sendPrefixed(sender, "GalaxyFollows v" + getDescription().getVersion()
                + " | username: " + (isUsernameSet() ? username : "NOT SET")
                + " | last poll: " + (ago < 0 ? "never" : ago + "s ago")
                + " | hold: " + holdHours + "h");
        sendPrefixed(sender, "Rewards delivered: " + rewardedTotal
                + " | unfollows caught: " + droppedUnfollows
                + " | pending acks: " + ackQueue.size()
                + " | waiting on MC link: " + warnedNoMc.size());
        return true;
    }

    private void sendPrefixed(CommandSender to, String msg) {
        to.sendMessage(color((prefix == null || prefix.isBlank() ? "" : prefix) + msg));
    }

    // ------------------------------------------------------------------ http + utils

    private record Result(boolean success, int status, Map<String, Object> json, String error) {}

    private static String color(String s) {
        return s == null ? "" : TextFormat.colorize('&', s);
    }

    private Result post(String path, Object bodyObj) {
        try {
            String body = Json.write(bodyObj);
            HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> json = null;
            if (resp.body() != null && resp.body().trim().startsWith("{")) {
                try { json = Json.parseObject(resp.body()); } catch (RuntimeException ignored) {}
            }
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return new Result(true, code, json, null);
            String err = json != null ? str(json.get("error")) : null;
            return new Result(false, code, json, err != null ? err : ("HTTP " + code));
        } catch (Exception ex) {
            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            return new Result(false, 0, null, msg == null ? "network error" : msg);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strArr(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objArr(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    /** Parse ISO-8601 timestamps from Postgres (up to 6 fractional digits). */
    static long parseIsoMs(String iso) {
        try {
            String s = iso.trim();
            int dot = s.indexOf('.');
            if (dot >= 0) {
                int end = dot + 1;
                while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
                String frac = s.substring(dot + 1, end);
                String cut = frac.substring(0, Math.min(3, frac.length()));
                while (cut.length() < 3) cut += "0";
                s = s.substring(0, dot) + "." + cut + s.substring(end);
            }
            return java.time.OffsetDateTime.parse(s,
                    java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
