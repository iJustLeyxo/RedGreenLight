package com.cavetale.redgreenlight;

import com.cavetale.area.struct.Area;
import com.cavetale.area.struct.AreasFile;
import com.cavetale.core.struct.Cuboid;
import com.cavetale.core.struct.Vec3i;
import com.cavetale.core.util.Json;
import com.cavetale.fam.trophy.Highscore;
import com.cavetale.mytems.Mytems;
import com.cavetale.mytems.item.trophy.TrophyCategory;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Snowman;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextColor.color;

public final class RedGreenLightPlugin extends JavaPlugin {
    private final RedGreenLightCommand redgreenlightCommand = new RedGreenLightCommand(this);
    private final EventListener eventListener = new EventListener(this);
    Tag tag;
    final Random random = new Random();
    private List<Cuboid> gameAreas;
    private List<Cuboid> spawnAreas;
    private List<Cuboid> goalAreas;
    private List<Cuboid> warpAreas;
    List<Cuboid> creeperAreas;
    List<Cuboid> snowmanAreas;
    List<Cuboid> dispenserAreas;
    List<Cuboid> campfireAreas;
    List<Cuboid> skeletonAreas;
    List<Cuboid> ghastAreas;
    List<Vec3i> checkpoints;
    private BukkitTask task;
    boolean teleporting;
    final Map<Vec3i, Creeper> creeperMap = new HashMap<>();
    final Map<Vec3i, Snowman> snowmanMap = new HashMap<>();
    final Map<Vec3i, Skeleton> skeletonMap = new HashMap<>();
    final Map<Vec3i, Ghast> ghastMap = new HashMap<>();
    List<Highscore> highscore = List.of();
    private final Map<UUID, Instant> invincibility = new HashMap<>();
    final Map<UUID, Integer> backwardsTicks = new HashMap<>();
    public static final Component TITLE = join(noSeparators(),
                                               Mytems.TRAFFIC_LIGHT.component,
                                               text(tiny("Red"), color(0xFF0000)),
                                               text(tiny("Light"), AQUA),
                                               Mytems.TRAFFIC_LIGHT.component,
                                               text(tiny("Green"), color(0x00FF00)),
                                               text(tiny("Light"), AQUA));
    final BossBar bossBar = BossBar.bossBar(TITLE, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    @Override
    public void onEnable() {
        redgreenlightCommand.enable();
        eventListener.enable();
        load();
    }

    @Override
    public void onDisable() {
        cleanUp();
        saveTag();
    }

    void load() {
        loadTag();
        loadAreas();
        for (UUID uuid : List.copyOf(tag.playing)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && !isGameWorld(player.getWorld())) {
                tag.playing.remove(uuid);
                tag.checkpoints.remove(uuid);
            }
        }
    }

    private void loadTag() {
        File file = new File(getDataFolder(), "save.json");
        tag = Json.load(file, Tag.class, Tag::new);
        if (tag.started) {
            startTicking();
        } else {
            stopTicking();
        }
        computeHighscore();
    }

    void cleanUp() {
        for (Creeper creeper : creeperMap.values()) {
            creeper.remove();
        }
        creeperMap.clear();
        for (Snowman snowman : snowmanMap.values()) {
            snowman.remove();
        }
        snowmanMap.clear();
        for (Skeleton skeleton : skeletonMap.values()) {
            skeleton.remove();
        }
        skeletonMap.clear();
        for (Ghast ghast : ghastMap.values()) {
            ghast.remove();
        }
        ghastMap.clear();
    }

    void saveTag() {
        getDataFolder().mkdirs();
        File file = new File(getDataFolder(), "save.json");
        Json.save(file, tag);
    }

    private void loadAreas() {
        gameAreas = List.of();
        spawnAreas = List.of();
        goalAreas = List.of();
        warpAreas = List.of();
        creeperAreas = List.of();
        snowmanAreas = List.of();
        skeletonAreas = List.of();
        ghastAreas = List.of();
        dispenserAreas = List.of();
        campfireAreas = List.of();
        World w = getWorld();
        if (w == null) {
            getLogger().warning("World not found: " + tag.world);
            return;
        }
        AreasFile areasFile = AreasFile.load(w, "RedGreenLight");
        if (areasFile == null) {
            getLogger().warning("Areas not found: " + tag.world + "/RedGreenLight");
            return;
        }
        this.gameAreas = toCuboid(areasFile.find("area"));
        this.spawnAreas = toCuboid(areasFile.find("spawn"));
        this.goalAreas = toCuboid(areasFile.find("goal"));
        this.warpAreas = toCuboid(areasFile.find("warp"));
        this.creeperAreas = toCuboid(areasFile.find("creeper"));
        this.snowmanAreas = toCuboid(areasFile.find("snowman"));
        this.dispenserAreas = toCuboid(areasFile.find("dispenser"));
        this.campfireAreas = toCuboid(areasFile.find("campfire"));
        this.skeletonAreas = toCuboid(areasFile.find("skeleton"));
        this.ghastAreas = toCuboid(areasFile.find("ghast"));
        this.checkpoints = new ArrayList<>();
        for (Cuboid cuboid : toCuboid(areasFile.find("checkpoint"))) {
            checkpoints.add(cuboid.getMin());
        }
    }

    private static List<Cuboid> toCuboid(Iterable<Area> areas) {
        if (areas == null) return List.of();
        List<Cuboid> result = new ArrayList<>();
        for (Area area : areas) {
            result.add(area.toCuboid());
        }
        return result;
    }

    public boolean inArea(List<Cuboid> areaList, Location location) {
        if (!tag.world.equals(location.getWorld().getName())) return false;
        for (Cuboid area : areaList) {
            if (area.contains(location)) return true;
        }
        return false;
    }

    public boolean isGameWorld(World world) {
        return tag.world.equals(world.getName());
    }

    public boolean inGameArea(Location location) {
        return inArea(gameAreas, location);
    }

    public boolean inSpawnArea(Location location) {
        return inArea(spawnAreas, location);
    }

    public boolean inGoalArea(Location location) {
        return inArea(goalAreas, location);
    }

    boolean isAtCheckpoint(Player player) {
        int checkpointIndex = tag.checkpoints.getOrDefault(player.getUniqueId(), -1);
        if (checkpointIndex < 0 || checkpointIndex >= checkpoints.size()) return false;
        Vec3i checkpoint = checkpoints.get(checkpointIndex);
        Vec3i pvec = Vec3i.of(player.getLocation());
        return pvec.x == checkpoint.x && pvec.z == checkpoint.z;
    }

    public List<Player> getPresentPlayers() {
        World world = Bukkit.getWorld(tag.world);
        if (world == null) return List.of();
        List<Player> list = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (inGameArea(player.getLocation())) {
                list.add(player);
            }
        }
        return list;
    }

    World getWorld() {
        return Bukkit.getWorld(tag.world);
    }

    public Location randomSpawnLocation() {
        Set<Vec3i> options = new HashSet<>();
        for (Cuboid area : warpAreas) {
            options.addAll(area.enumerate());
        }
        if (options.isEmpty()) throw new IllegalStateException("Warp is empty");
        List<Vec3i> list = new ArrayList<>(options);
        Vec3i center = list.get(random.nextInt(list.size()));
        return center.toBlock(getWorld()).getLocation().add(0.5, 0.0, 0.5);
    }

    /**
     * Warp to spawn and mark as playing.
     */
    void teleportToSpawn(Player player) {
        Location location = randomSpawnLocation();
        Location ploc = player.getLocation();
        location.setPitch(ploc.getPitch());
        location.setYaw(ploc.getYaw());
        teleporting = true;
        player.teleport(location, TeleportCause.PLUGIN);
        teleporting = false;
        player.setFallDistance(0);
        addPlaying(player);
        tag.checkpoints.remove(player.getUniqueId());
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
    }

    void teleportToCheckpoint(Player player, String reason) {
        getLogger().info(player.getName() + " went back to checkpoint because: " + reason);
        if (player.hasPermission("redgreenlight.debug")) {
            player.sendMessage(text("Checkpoint reason: " + reason, YELLOW));
        }
        Instant invincible = invincibility.get(player.getUniqueId());
        if (invincible == null || invincible.toEpochMilli() < Instant.now().toEpochMilli()) {
            invincibility.put(player.getUniqueId(), Instant.now().plus(Duration.ofSeconds(3)));
        }
        int checkpointIndex = tag.checkpoints.getOrDefault(player.getUniqueId(), -1);
        if (checkpointIndex < 0 || checkpointIndex >= checkpoints.size()) {
            teleportToSpawn(player);
            return;
        }
        Vec3i checkpoint = checkpoints.get(checkpointIndex);
        Location location = checkpoint.toLocation(getWorld()).add(0.5, 1.0, 0.5);
        Location ploc = player.getLocation();
        location.setPitch(ploc.getPitch());
        location.setYaw(ploc.getYaw());
        teleporting = true;
        player.teleport(location);
        teleporting = false;
        tag.addScores(player.getUniqueId(), -1);
        computeHighscore();
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }

    void addPlaying(Player player) {
        if (tag.playing.contains(player.getUniqueId())) return;
        tag.playing.add(player.getUniqueId());
        if (tag.event) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ml add " + player.getName());
        }
    }

    void startTicking() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(this, eventListener::onTick, 0L, 0L);
    }

    void stopTicking() {
        if (task == null) return;
        task.cancel();
        task = null;
    }

    void computeHighscore() {
        highscore = Highscore.of(tag.scores);
    }

    int rewardHighscore() {
        return Highscore.reward(tag.scores,
                                "red_light_green_light",
                                TrophyCategory.RED_GREEN_LIGHT,
                                TITLE,
                                hi -> ("You played Red Light Green Light with "
                                       + hi.score + " point" + (hi.score == 1 ? "" : "s")));
    }
}
