<?php

declare(strict_types=1);

namespace bluenetwork\galaxyfollows;

use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\event\Listener;
use pocketmine\player\Player;
use pocketmine\plugin\PluginBase;
use pocketmine\scheduler\ClosureTask;
use pocketmine\utils\TextFormat;
use function array_slice, count, curl_init, curl_exec, curl_close, date, in_array,
    json_decode, json_encode, md5, preg_match, sprintf, str_replace, strtolower,
    strtotime, substr, time, ucfirst;

/**
 * GalaxyFollows for Bedrock (PocketMine-MP 5).
 *
 * No keys, no pairing: the plugin identifies itself only with the site
 * username it rewards. Set username: in the config, pick your reward
 * commands, done. Same behavior as the Paper edition:
 *  - one reward per follower, forever
 *  - follow-hold-hours: a follow must survive the window before it pays
 *    (re-verified with the site right before the reward)
 */
final class Main extends PluginBase implements Listener
{
    private const API_URL = "https://tybyyeeapetvzjenzjid.supabase.co/functions/v1/plugin";

    /** @var list<string> event ids handled but not yet acked */
    private array $ackQueue = [];

    /** @var array<string, string> event ids currently being handled */
    private array $inflight = [];

    /** @var array<string, true> exact names of online players */
    private array $onlineNames = [];

    private int $lastPollOk = 0;
    private int $rewardedTotal = 0;
    private int $droppedUnfollows = 0;

    private bool $enabled = true;
    private string $prefix = "";
    private string $username = "";
    /** @var list<string> */
    private array $rewardCommands = [];
    private string $rewardMessage = "";
    private int $holdHours = 24;
    private int $pollInterval = 30;
    private int $maxAgeDays = 30;

    protected function onEnable(): void
    {
        $this->saveDefaultConfig();
        $this->readConfig();
        $this->getServer()->getPluginManager()->registerEvents($this, $this);
        foreach ($this->getServer()->getOnlinePlayers() as $p) {
            $this->onlineNames[$p->getName()] = true;
        }
        $this->schedulePoll();
        if ($this->usernameOk()) {
            $this->getLogger()->info("GalaxyFollows enabled. Rewarding followers of '{$this->username}', polling every {$this->pollInterval}s, hold window {$this->holdHours}h.");
        } else {
            $this->getLogger()->warning("Set your BlueGalaxy username in plugin_data/GalaxyFollows/config.yml, then run /galaxyfollows reload.");
        }
    }

    protected function onDisable(): void
    {
        $this->getScheduler()->cancelAllTasks();
    }

    #[\pocketmine\event\EventHandler]
    public function handleJoin(\pocketmine\event\player\PlayerJoinEvent $event): void
    {
        $this->onlineNames[$event->getPlayer()->getName()] = true;
        // a join might be a pending follower: poll 2s later
        $this->getScheduler()->scheduleDelayedTask(new ClosureTask(function(): void { $this->poll(); }), 40);
    }

    #[\pocketmine\event\EventHandler]
    public function handleQuit(\pocketmine\event\player\PlayerQuitEvent $event): void
    {
        unset($this->onlineNames[$event->getPlayer()->getName()]);
    }

    private function readConfig(): void
    {
        $c = $this->getConfig()->get("galaxyfollows", []);
        $this->enabled = (bool)($c["enabled"] ?? true);
        $this->prefix = (string)($c["prefix"] ?? "");
        $this->username = strtolower(trim((string)($c["username"] ?? "")));
        if (str_starts_with($this->username, "@")) {
            $this->username = substr($this->username, 1);
        }
        $this->rewardCommands = array_values((array)($c["reward-commands"] ?? []));
        $this->rewardMessage = (string)($c["reward-message"] ?? "");
        $this->holdHours = max(0, (int)($c["follow-hold-hours"] ?? 24));
        $this->pollInterval = max(10, (int)($c["poll-interval-seconds"] ?? 30));
        $this->maxAgeDays = max(1, (int)($c["max-age-days"] ?? 30));

    }

    private function schedulePoll(): void
    {
        $this->getScheduler()->scheduleRepeatingTask(new ClosureTask(function(): void { $this->poll(); }), $this->pollInterval * 20);
    }

    private function reload(): void
    {
        $this->getScheduler()->cancelAllTasks();
        $this->reloadConfig();
        $this->readConfig();
        $this->schedulePoll();
    }

    private function usernameOk(): bool
    {
        return $this->username !== "" && $this->username !== "change_me";
    }

    // ------------------------------------------------------------- polling

    public function poll(): void
    {
        if (!$this->enabled || !$this->usernameOk()) {
            return;
        }
        $body = [
            "username" => $this->username,
            "watch" => [$this->username],
            "max_age_days" => $this->maxAgeDays,
        ];
        if (count($this->ackQueue) > 0) {
            $body["ack"] = $this->ackQueue;
            $this->ackQueue = [];
        }
        $result = $this->post("/v1/follow/poll", $body);
        if ($result === null) {
            $this->getLogger()->warning("poll failed (network error, retrying next cycle)");
            return;
        }
        if (isset($result["error"])) {
            $hint = ($result["_status"] ?? 0) === 404 ? " (is your BlueGalaxy username correct?)" : "";
            $this->getLogger()->warning("poll failed: " . ($result["error"] ?? "unknown") . $hint);
            return;
        }
        $this->lastPollOk = time();
        foreach (($result["unknown_handles"] ?? []) as $h) {
            $this->getLogger()->warning("not a BlueGalaxy account: {$h} (check username in the config)");
        }
        foreach (($result["events"] ?? []) as $ev) {
            $this->handleEvent($ev);
        }
    }

    /**
     * @param array<string, mixed> $ev
     */
    private function handleEvent(array $ev): void
    {
        $id = (string)($ev["id"] ?? "");
        $mc = (string)($ev["mc_username"] ?? "");
        $watched = (string)($ev["watched_handle"] ?? "");
        $follower = (string)($ev["follower_handle"] ?? "");
        $followedAt = (string)($ev["followed_at"] ?? "");
        if ($id === "") {
            return;
        }
        if ($mc === "") {
            // follower has no linked MC account (system accounts): ack + skip
            $this->ackQueue[] = $id;
            return;
        }
        if (isset($this->inflight[$id])) {
            return;
        }
        $this->inflight[$id] = $mc;

        if ($this->holdHours > 0) {
            $followedMs = $followedAt === "" ? null : $this->parseIsoMs($followedAt);
            $ageMs = $followedMs === null ? PHP_INT_MAX : ((time() * 1000) - $followedMs);
            if ($ageMs < $this->holdHours * 3600000) {
                // younger than the hold window: stays pending, retry later
                unset($this->inflight[$id]);
                return;
            }
            $check = $this->checkStillFollowing($watched, $follower);
            if ($check === null) {
                // network problem: keep the event pending, retry next poll
                unset($this->inflight[$id]);
                return;
            }
            if ($check === false) {
                $this->droppedUnfollows++;
                $this->getLogger()->info("Follow was undone before the reward ({$follower} -> {$watched}): no reward, event closed.");
                $this->ackQueue[] = $id;
                unset($this->inflight[$id]);
                return;
            }
        }
        $this->dispatchIfOnline($id, $mc, $follower, $watched);
    }

    /**
     * null = network problem (keep the event pending), true = still
     * following, false = unfollowed (no reward, ever).
     */
    private function checkStillFollowing(string $watched, string $follower): ?bool
    {
        if ($watched === "" || $follower === "") {
            return false;
        }
        $result = $this->post("/v1/follow/status", [
            "username" => $this->username,
            "checks" => [["account" => $watched, "follower" => $follower]],
        ]);
        if ($result === null || isset($result["error"])) {
            return null;
        }
        foreach (($result["results"] ?? []) as $r) {
            if (($r["following"] ?? false) === true) {
                return true;
            }
        }
        return false;
    }

    private function dispatchIfOnline(string $id, string $mc, string $follower, string $watched): void
    {
        if (!isset($this->onlineNames[$mc])) {
            // not on the server: stays pending server-side, join retries
            unset($this->inflight[$id]);
            return;
        }
        $player = $this->getServer()->getPlayerExact($mc);
        if ($player === null || !$player->isOnline()) {
            unset($this->inflight[$id]);
            return;
        }
        $this->deliver($id, $player, $follower, $watched);
    }

    private function deliver(string $eventId, Player $player, string $follower, string $watched): void
    {
        try {
            foreach ($this->rewardCommands as $template) {
                $cmd = str_replace(
                    ["{player}", "{handle}", "{account}"],
                    [$player->getName(), $follower, $watched],
                    (string)$template
                );
                if (trim($cmd) === "") {
                    continue;
                }
                $this->getServer()->dispatchCommand(
                    new \pocketmine\command\ConsoleCommandSender(), $cmd
                );
            }
            if ($this->rewardMessage !== "") {
                $msg = str_replace(
                    ["{player}", "{handle}", "{account}"],
                    [$player->getName(), $follower, $watched],
                    $this->rewardMessage
                );
                $player->sendMessage(TextFormat::colorize($this->prefix . $msg));
            }
            $this->rewardedTotal++;
            $this->getLogger()->info("Reward delivered: " . $player->getName()
                . " followed " . ($watched !== "" ? $watched : "?") . " on BlueGalaxy.");
        } catch (\Throwable $t) {
            $this->getLogger()->warning("reward dispatch failed for " . $player->getName() . ": " . $t->getMessage());
        } finally {
            $this->ackQueue[] = $eventId;
            unset($this->inflight[$eventId]);
        }
    }

    // ------------------------------------------------------------- command

    public function onCommand(CommandSender $sender, Command $command, string $label, array $args): bool
    {
        $sub = $args[0] ?? "status";
        if (strtolower($sub) === "reload") {
            $this->reload();
            $this->sendPrefixed($sender, TextFormat::GREEN . "Config reloaded. Rewarding followers of: "
                . ($this->usernameOk() ? $this->username : "NOT SET (fix username in the config)"));
            return true;
        }
        $ago = $this->lastPollOk === 0 ? -1 : time() - $this->lastPollOk;
        $this->sendPrefixed($sender, "GalaxyFollows v" . $this->getDescription()->getVersion()
            . " | username: " . ($this->usernameOk() ? $this->username : "NOT SET")
            . " | last poll: " . ($ago < 0 ? "never" : $ago . "s ago")
            . " | hold: {$this->holdHours}h");
        $this->sendPrefixed($sender, "Rewards delivered: {$this->rewardedTotal}"
            . " | unfollows caught: {$this->droppedUnfollows}"
            . " | pending acks: " . count($this->ackQueue));
        return true;
    }

    private function sendPrefixed(CommandSender $to, string $msg): void
    {
        $to->sendMessage(TextFormat::colorize(($this->prefix !== "" ? $this->prefix : "") . $msg));
    }

    // ------------------------------------------------------------- http

    /**
     * @return array<string, mixed>|null
     */
    private function post(string $path, array $body): ?array
    {
        $ch = curl_init(self::API_URL . $path);
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => json_encode($body),
            CURLOPT_HTTPHEADER => ["Accept: application/json", "Content-Type: application/json"],
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_CONNECTTIMEOUT => 10,
        ]);
        $raw = curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        if ($raw === false) {
            return null;
        }
        $json = json_decode((string)$raw, true);
        if (!is_array($json)) {
            return ["error" => "bad response (HTTP {$status})", "_status" => $status];
        }
        $json["_status"] = $status;
        return $json;
    }

    private function parseIsoMs(string $iso): ?int
    {
        $ts = strtotime($iso);
        return $ts === false ? null : $ts * 1000;
    }
}
