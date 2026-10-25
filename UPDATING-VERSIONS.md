# Updating GalaxyFollows for newer Minecraft versions

Read this when a new Minecraft / server version drops. Short version:
for Java you usually have to do NOTHING, and for the download page it is
a one-line edit.

## Java (Paper)

The plugin is a single universal build with `api-version: '1.21'` in
`java/src/main/resources/plugin.yml`. Bukkit/Paper loads any plugin
whose api-version is OLDER than or equal to the server version, and the
plugin only uses APIs that have existed since 1.21 (commands, config,
scheduler, player join/quit events). So every new Paper release
(26.4, 27.x, whatever) should load the existing jar unchanged.

When a new Minecraft version comes out:

1. Do nothing at first. The existing jar almost certainly works.
2. Add the new version to the download page version list (see below).
3. Only if Paper REMOVED or CHANGED an API the plugin uses (check your
   server log for GalaxyFollows errors on boot): bump the version in
   `java/pom.xml`, fix the compile, rebuild
   (`cd java && mvn clean package`), test on a server, then ship a new
   zip and update the download page URL.

## Bedrock (PowerNukkitX 3.x)

PowerNukkitX follows the Bedrock protocol version, so one build usually
spans several Bedrock releases. When a new Bedrock version drops:

1. Boot a test server on the new version with the plugin installed.
2. If it boots and `/galaxyfollows status` answers, nothing to change.
3. If the PNX 3.x API changed, update `bedrock/powernukkitx/src` (org.powernukkitx) and rebuild against the PowerNukkitX server jar.

## Bedrock (PocketMine-MP)

PocketMine-MP 5 reached its final release (project shut down in July
2026). New Bedrock versions will come through forks. If a fork changes
the plugin API, update `bedrock/pocketmine/bluenetwork/galaxyfollows/Main.php`.

## The download page (galaxy.gamer.gd/download)

Not part of this repo - it lives in the site's `download.php`. To list a
new version:

1. Open `site/download.php` in the BlueGalaxy handover package.
2. Find the `EDITIONS` object in the script at the bottom.
3. Put the new version at the FRONT of `versions` (first entry is
   labelled "(latest)") and move the previous latest one back, or push
   old ones into `older` (they show under "More 1.21.x versions").
4. The Java card serves ONE universal zip for every version
   (`JAVA_URL`), so no file changes are needed. Bedrock serves one zip
   per server software.
5. Deploy the site file and bump `ASSET_VER` in `config.php`.

Rule of thumb: new version number = one line in `download.php`. New
jar build only if a server log proves something broke.
