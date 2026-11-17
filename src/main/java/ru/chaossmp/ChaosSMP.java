package ru.chaossmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChaosSMP extends JavaPlugin implements Listener {
    private Connection connection;
    private NamespacedKey boardKey;
    private Location spawn;
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportStart = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> teleportTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> teleportOrigins = new ConcurrentHashMap<>();
    private BukkitTask boardTask;

    private static final Map<String, String> BOARD_NAMES = Map.of(
            "kills", "ТОП УБИЙСТВ",
            "deaths", "ТОП СМЕРТЕЙ",
            "streak", "ТОП СЕРИЙ УБИЙСТВ",
            "playtime", "ТОП ОНЛАЙНА"
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boardKey = new NamespacedKey(this, "leaderboard_entity");
        openDatabase();
        loadSpawn();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) sessionStart.put(player.getUniqueId(), System.currentTimeMillis());
        if (getConfig().getBoolean("leaderboards.enabled", true)) {
            refreshBoards();
            long period = Math.max(10, getConfig().getLong("leaderboards.refresh-seconds", 30)) * 20L;
            boardTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshBoards, period, period);
        }
        getLogger().info("ChaosSMP 0.1.0 enabled.");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) saveSession(player.getUniqueId());
        teleportTasks.values().forEach(BukkitTask::cancel);
        if (boardTask != null) boardTask.cancel();
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
        }
    }

    private void openDatabase() {
        try {
            File folder = getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create plugin folder");
            connection = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "stats.db").getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      kills INTEGER NOT NULL DEFAULT 0,
                      deaths INTEGER NOT NULL DEFAULT 0,
                      current_streak INTEGER NOT NULL DEFAULT 0,
                      best_streak INTEGER NOT NULL DEFAULT 0,
                      playtime_seconds INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            }
        } catch (Exception ex) {
            getLogger().severe("Could not open stats database: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void ensurePlayer(Player player) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_stats(uuid,name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.executeUpdate();
        } catch (SQLException ex) { getLogger().warning("Stats write failed: " + ex.getMessage()); }
    }

    private void updateStat(UUID uuid, String field, long amount) {
        if (!Set.of("kills", "deaths", "current_streak", "best_streak", "playtime_seconds").contains(field)) return;
        try (PreparedStatement ps = connection.prepareStatement("UPDATE player_stats SET " + field + " = " + field + " + ? WHERE uuid=?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) { getLogger().warning("Stats update failed: " + ex.getMessage()); }
    }

    private void setStreak(UUID uuid, int streak) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_stats SET current_streak=?, best_streak=MAX(best_streak, ?) WHERE uuid=?")) {
            ps.setInt(1, streak); ps.setInt(2, streak); ps.setString(3, uuid.toString()); ps.executeUpdate();
        } catch (SQLException ex) { getLogger().warning("Streak update failed: " + ex.getMessage()); }
    }

    private void saveSession(UUID uuid) {
        Long started = sessionStart.remove(uuid);
        if (started == null) return;
        long seconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
        updateStat(uuid, "playtime_seconds", seconds);
    }

    private void loadSpawn() {
        String worldName = getConfig().getString("spawn.world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        spawn = new Location(world,
                getConfig().getDouble("spawn.x"), getConfig().getDouble("spawn.y"), getConfig().getDouble("spawn.z"),
                (float) getConfig().getDouble("spawn.yaw"), (float) getConfig().getDouble("spawn.pitch"));
    }

    private void saveSpawn(Location loc) {
        spawn = loc.clone();
        getConfig().set("spawn.world", loc.getWorld().getName());
        getConfig().set("spawn.x", loc.getX()); getConfig().set("spawn.y", loc.getY()); getConfig().set("spawn.z", loc.getZ());
        getConfig().set("spawn.yaw", loc.getYaw()); getConfig().set("spawn.pitch", loc.getPitch());
        saveConfig();
        refreshBoards();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("setspawn")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(color("&cТолько игрок может установить спавн.")); return true; }
            if (!player.hasPermission("chaossmp.setspawn")) { send(player, "no-permission"); return true; }
            saveSpawn(player.getLocation());
            send(player, "set-spawn");
            return true;
        }
        if (name.equals("spawn")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(color("&cЭта команда доступна только игроку.")); return true; }
            beginTeleport(player);
            return true;
        }
        if (name.equals("stats")) {
            if (args.length == 0 && sender instanceof Player p) showStats(sender, p.getUniqueId(), p.getName());
            else if (args.length >= 1) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                if (!target.hasPlayedBefore() && !target.isOnline()) { send(sender, "player-not-found"); return true; }
                showStats(sender, target.getUniqueId(), target.getName() == null ? args[0] : target.getName());
            } else sender.sendMessage(color("&cИспользование: /stats [ник]"));
            return true;
        }
        if (name.equals("top")) {
            String type = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                sender.sendMessage(color("&b&lCHAOS SMP &8» &fДоступные рейтинги:"));
                sender.sendMessage(color("&e/top kills &7| &e/top deaths &7| &e/top streak &7| &e/top playtime"));
                return true;
            }
            if (!BOARD_NAMES.containsKey(type)) { sender.sendMessage(color("&cНеизвестный рейтинг. Используй: kills, deaths, streak, playtime.")); return true; }
            showTop(sender, type);
            return true;
        }
        return false;
    }

    private void beginTeleport(Player player) {
        if (spawn == null || spawn.getWorld() == null) { send(player, "no-spawn"); return; }
        long now = System.currentTimeMillis();
        if (combatUntil.getOrDefault(player.getUniqueId(), 0L) > now) { send(player, "combat-block"); return; }
        cancelTeleport(player.getUniqueId());
        int delay = Math.max(1, getConfig().getInt("spawn.teleport-delay-seconds", 5));
        teleportStart.put(player.getUniqueId(), now);
        teleportOrigins.put(player.getUniqueId(), player.getLocation().clone());
        send(player, "teleport-start", "{seconds}", String.valueOf(delay));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            teleportTasks.remove(player.getUniqueId());
            teleportStart.remove(player.getUniqueId());
            teleportOrigins.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            if (combatUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) {
                send(player, "combat-block"); return;
            }
            player.teleport(spawn);
            send(player, "teleport-success");
        }, delay * 20L);
        teleportTasks.put(player.getUniqueId(), task);
    }

    private void cancelTeleport(UUID uuid) {
        BukkitTask task = teleportTasks.remove(uuid);
        if (task != null) task.cancel();
        teleportStart.remove(uuid);
        teleportOrigins.remove(uuid);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!teleportTasks.containsKey(uuid) || !getConfig().getBoolean("spawn.cancel-on-move", true)) return;
        Location from = event.getFrom(), to = event.getTo();
        if (to != null && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ())) {
            cancelTeleport(uuid);
            send(event.getPlayer(), "teleport-cancel-move");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = event.getDamager() instanceof Player p ? p : null;
        if (attacker == null && event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player p) attacker = p;
        if (victim != null && teleportTasks.containsKey(victim.getUniqueId())) {
            cancelTeleport(victim.getUniqueId());
            send(victim, "teleport-cancel-damage");
        }
        if (attacker != null) {
            long until = System.currentTimeMillis() + getConfig().getLong("spawn.combat-tag-seconds", 15) * 1000L;
            combatUntil.put(attacker.getUniqueId(), until);
        }
        if (victim != null && attacker != null) {
            long until = System.currentTimeMillis() + getConfig().getLong("spawn.combat-tag-seconds", 15) * 1000L;
            combatUntil.put(victim.getUniqueId(), until);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        ensurePlayer(victim);
        updateStat(victim.getUniqueId(), "deaths", 1);
        setStreak(victim.getUniqueId(), 0);
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            ensurePlayer(killer);
            updateStat(killer.getUniqueId(), "kills", 1);
            try (PreparedStatement ps = connection.prepareStatement("SELECT current_streak FROM player_stats WHERE uuid=?")) {
                ps.setString(1, killer.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    int next = rs.next() ? rs.getInt(1) + 1 : 1;
                    setStreak(killer.getUniqueId(), next);
                }
            } catch (SQLException ex) { getLogger().warning("Could not update killstreak: " + ex.getMessage()); }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensurePlayer(event.getPlayer());
        sessionStart.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveSession(event.getPlayer().getUniqueId());
        cancelTeleport(event.getPlayer().getUniqueId());
        combatUntil.remove(event.getPlayer().getUniqueId());
    }

    private void showStats(CommandSender sender, UUID uuid, String playerName) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT kills,deaths,best_streak,playtime_seconds FROM player_stats WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { sender.sendMessage(color("&cСтатистика пока отсутствует.")); return; }
                sender.sendMessage(color("&8&m--------------------------------"));
                sender.sendMessage(color("&b&lСТАТИСТИКА &f" + playerName));
                sender.sendMessage(color("&aУбийства: &f" + rs.getInt("kills")));
                sender.sendMessage(color("&cСмерти: &f" + rs.getInt("deaths")));
                sender.sendMessage(color("&6Лучшая серия: &f" + rs.getInt("best_streak")));
                sender.sendMessage(color("&eВремя игры: &f" + formatTime(rs.getLong("playtime_seconds") + liveSessionSeconds(uuid))));
                sender.sendMessage(color("&8&m--------------------------------"));
            }
        } catch (SQLException ex) { sender.sendMessage(color("&cОшибка чтения статистики.")); }
    }

    private long liveSessionSeconds(UUID uuid) {
        Long start = sessionStart.get(uuid);
        return start == null ? 0 : Math.max(0, (System.currentTimeMillis() - start) / 1000);
    }

    private void showTop(CommandSender sender, String type) {
        String column = switch (type) {
            case "kills" -> "kills";
            case "deaths" -> "deaths";
            case "streak" -> "best_streak";
            case "playtime" -> "playtime_seconds";
            default -> throw new IllegalArgumentException("Unknown board");
        };
        String sql = "SELECT name," + column + " AS score FROM player_stats ORDER BY score DESC, name ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, getConfig().getInt("leaderboards.entries", 10)));
            try (ResultSet rs = ps.executeQuery()) {
                sender.sendMessage(color("&8&m--------------------------------"));
                sender.sendMessage(color("&b&l" + BOARD_NAMES.get(type)));
                int place = 0;
                while (rs.next()) {
                    place++;
                    sender.sendMessage(color(placeColor(place) + "#" + place + " &f" + rs.getString("name") + " &8» " + scoreText(type, rs.getLong("score"))));
                }
                if (place == 0) sender.sendMessage(color("&7Пока нет данных."));
                sender.sendMessage(color("&8&m--------------------------------"));
            }
        } catch (SQLException ex) { sender.sendMessage(color("&cОшибка чтения рейтинга.")); }
    }

    private void refreshBoards() {
        if (spawn == null || connection == null || !getConfig().getBoolean("leaderboards.enabled", true)) return;
        removeBoards();
        ConfigurationSection section = getConfig().getConfigurationSection("leaderboards.boards");
        if (section == null) return;
        for (String type : BOARD_NAMES.keySet()) {
            ConfigurationSection cfg = section.getConfigurationSection(type);
            if (cfg == null) continue;
            Location base = spawn.clone().add(cfg.getDouble("x"), 0, cfg.getDouble("z"));
            base.setYaw((float) cfg.getDouble("yaw"));
            spawnBoard(type, base);
        }
    }

    private void spawnBoard(String type, Location base) {
        World world = base.getWorld();
        if (world == null) return;
        spawnLine(world, base.clone().add(0, 2.8, 0), "&b&l✦ " + BOARD_NAMES.get(type) + " ✦");
        spawnLine(world, base.clone().add(0, 2.45, 0), "&7Топ игроков сервера");
        String column = switch (type) {
            case "kills" -> "kills";
            case "deaths" -> "deaths";
            case "streak" -> "best_streak";
            default -> "playtime_seconds";
        };
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid,name," + column + " AS score FROM player_stats ORDER BY score DESC, name ASC LIMIT ?")) {
            ps.setInt(1, Math.max(1, getConfig().getInt("leaderboards.entries", 10)));
            try (ResultSet rs = ps.executeQuery()) {
                int place = 0;
                while (rs.next()) {
                    place++;
                    String playerName = rs.getString("name");
                    long score = rs.getLong("score");
                    Location line = base.clone().add(0, 2.05 - (place - 1) * 0.24, 0);
                    spawnLine(world, line, placeColor(place) + "#" + place + " &f" + playerName + " &8- " + scoreText(type, score));
                    if (place <= 3) spawnHead(world, base.clone().add(0, 1.85 - (place - 1) * 0.24, 0), rs.getString("uuid"));
                }
                if (place == 0) spawnLine(world, base.clone().add(0, 2.0, 0), "&7Пока нет игроков");
            }
        } catch (SQLException ex) { getLogger().warning("Leaderboard refresh failed: " + ex.getMessage()); }
    }

    private void spawnLine(World world, Location location, String text) {
        ArmorStand stand = world.spawn(location, ArmorStand.class, entity -> {
            entity.setVisible(false);
            entity.setMarker(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setCustomNameVisible(true);
            entity.setCustomName(color(text));
            entity.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void spawnHead(World world, Location location, String uuidText) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(UUID.fromString(uuidText));
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) { meta.setOwningPlayer(offline); head.setItemMeta(meta); }
            world.spawn(location, ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setMarker(true);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setSilent(true);
                stand.getEquipment().setHelmet(head);
                stand.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (IllegalArgumentException ignored) {}
    }

    private void removeBoards() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(boardKey, PersistentDataType.BYTE)) entity.remove();
            }
        }
    }

    private String scoreText(String type, long score) {
        return switch (type) {
            case "playtime" -> formatTime(score);
            case "streak" -> score + " подряд";
            default -> String.valueOf(score);
        };
    }

    private String formatTime(long seconds) {
        long days = seconds / 86400, hours = (seconds % 86400) / 3600, minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "д " + hours + "ч";
        if (hours > 0) return hours + "ч " + minutes + "м";
        return minutes + "м";
    }

    private String placeColor(int place) {
        return switch (place) { case 1 -> "&6"; case 2 -> "&f"; case 3 -> "&c"; default -> "&7"; };
    }

    private void send(CommandSender sender, String key) { send(sender, key, "", ""); }
    private void send(CommandSender sender, String key, String token, String value) {
        String message = getConfig().getString("messages." + key, "&cMissing message: " + key);
        if (!token.isEmpty()) message = message.replace(token, value);
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(color(prefix + message));
    }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
}
