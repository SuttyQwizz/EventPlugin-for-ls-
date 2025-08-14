package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class EventPlugin extends JavaPlugin implements Listener {

    private final Set<UUID> eventChatPlayers = new HashSet<>();
    private final Map<UUID, Long> mutedPlayers = new HashMap<>();
    private final Map<UUID, Long> bannedPlayers = new HashMap<>();
    private final Map<UUID, Long> checkedPlayers = new HashMap<>();
    private final Map<UUID, String> playerIPs = new HashMap<>();
    private final Map<UUID, UUID> checkers = new HashMap<>();
    private FileConfiguration config;
    private FileConfiguration mutesConfig;
    private FileConfiguration bansConfig;
    private File mutesFile;
    private File bansFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        mutesFile = new File(getDataFolder(), "mutes.yml");
        if (!mutesFile.exists()) {
            saveResource("mutes.yml", false);
        }
        mutesConfig = YamlConfiguration.loadConfiguration(mutesFile);
        loadMutes();

        bansFile = new File(getDataFolder(), "bans.yml");
        if (!bansFile.exists()) {
            saveResource("bans.yml", false);
        }
        bansConfig = YamlConfiguration.loadConfiguration(bansFile);
        loadBans();

        getCommand("event").setExecutor(new EventCommand());
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::checkTimers, 0L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::updateCheckTitles, 0L, 40L);
        getLogger().info("EventPlugin enabled for Spigot 1.16.5!");
    }

    @Override
    public void onDisable() {
        saveMutes();
        saveBans();
        getLogger().info("EventPlugin disabled!");
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void loadMutes() {
        for (String key : mutesConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long expiry = mutesConfig.getLong(key + ".expiry");
                if (expiry > System.currentTimeMillis()) {
                    mutedPlayers.put(uuid, expiry);
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in mutes.yml: " + key);
            }
        }
    }

    private void saveMutes() {
        for (Map.Entry<UUID, Long> entry : mutedPlayers.entrySet()) {
            mutesConfig.set(entry.getKey().toString() + ".expiry", entry.getValue());
        }
        try {
            mutesConfig.save(mutesFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save mutes.yml: " + e.getMessage());
        }
    }

    private void loadBans() {
        for (String key : bansConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long expiry = bansConfig.getLong(key + ".expiry");
                if (expiry > System.currentTimeMillis()) {
                    bannedPlayers.put(uuid, expiry);
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in bans.yml: " + key);
            }
        }
    }

    private void saveBans() {
        for (Map.Entry<UUID, Long> entry : bannedPlayers.entrySet()) {
            bansConfig.set(entry.getKey().toString() + ".expiry", entry.getValue());
        }
        try {
            bansConfig.save(bansFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save bans.yml: " + e.getMessage());
        }
    }

    private void checkTimers() {
        long now = System.currentTimeMillis();
        mutedPlayers.entrySet().removeIf(entry -> {
            if (entry.getValue() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.sendMessage(color(config.getString("messages.mute-expired", "&aВаш мут истёк!")));
                }
                mutesConfig.set(entry.getKey().toString(), null);
                return true;
            }
            return false;
        });
        saveMutes();

        bannedPlayers.entrySet().removeIf(entry -> {
            if (entry.getValue() <= now) {
                bansConfig.set(entry.getKey().toString(), null);
                return true;
            }
            return false;
        });
        saveBans();

        checkedPlayers.entrySet().removeIf(entry -> {
            if (entry.getValue() <= now) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    bannedPlayers.put(player.getUniqueId(), System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000);
                    saveBans();
                    player.kickPlayer(color(config.getString("messages.check-ban-auto", "&cВы были забанены на 7 дней за истечение времени проверки!")));
                }
                checkers.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void updateCheckTitles() {
        for (UUID playerId : checkedPlayers.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendTitle(color(config.getString("messages.check-title", "&cПроверка на читы, пишите свой Discord")),
                        color(config.getString("messages.check-subtitle", "&eОсталось времени: %time%")),
                        0, 50, 0);
            }
        }
    }

    private long parseDuration(String duration) {
        try {
            long value = Long.parseLong(duration.replaceAll("[^0-9]", ""));
            if (duration.endsWith("s")) return value * 1000;
            if (duration.endsWith("m")) return value * 60 * 1000;
            if (duration.endsWith("h")) return value * 60 * 60 * 1000;
            if (duration.endsWith("d")) return value * 24 * 60 * 60 * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        playerIPs.put(playerId, player.getAddress().getAddress().getHostAddress());
        if (bannedPlayers.containsKey(playerId)) {
            long expiry = bannedPlayers.get(playerId);
            if (expiry > System.currentTimeMillis()) {
                long remaining = (expiry - System.currentTimeMillis()) / 1000;
                long days = remaining / (24 * 60 * 60);
                long hours = (remaining % (24 * 60 * 60)) / (60 * 60);
                long minutes = (remaining % (60 * 60)) / 60;
                String timeLeft = String.format("%dd %dh %dm", days, hours, minutes);
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, color(config.getString("messages.ban-kick", "&cВы забанены до %time%!").replace("%time%", timeLeft)));
            } else {
                bannedPlayers.remove(playerId);
                bansConfig.set(playerId.toString(), null);
                saveBans();
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (checkedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String command = event.getMessage().toLowerCase().split(" ")[0];

        if (checkedPlayers.containsKey(playerId) || checkers.containsValue(playerId)) {
            if (command.equals("/tp") || command.equals("/tpa") || command.equals("/warp") || command.equals("/home")) {
                event.setCancelled(true);
                player.sendMessage(color(config.getString("messages.no-teleport", "&cВы не можете телепортироваться во время проверки!")));
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (mutedPlayers.containsKey(playerId)) {
            long expiry = mutedPlayers.get(playerId);
            if (expiry > System.currentTimeMillis()) {
                event.setCancelled(true);
                player.sendMessage(color(config.getString("messages.mute-blocked", "&cВы замучены и не можете писать в чат!")));
                return;
            } else {
                mutedPlayers.remove(playerId);
                mutesConfig.set(playerId.toString(), null);
                saveMutes();
            }
        }

        if (checkedPlayers.containsKey(playerId)) {
            event.setCancelled(true);
            String message = event.getMessage();
            String formattedMessage = color(config.getString("check.format", "&c[Проверка] &f%player%: &e%message%")
                    .replace("%player%", player.getName())
                    .replace("%message%", message));
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.hasPermission("lifesteal.event.check") || onlinePlayer.getUniqueId().equals(playerId)) {
                    onlinePlayer.sendMessage(formattedMessage);
                }
            }
            return;
        }

        if (eventChatPlayers.contains(playerId)) {
            event.setCancelled(true);
            String message = event.getMessage();
            String formattedMessage = color(config.getString("chat.format", "&7[Event Chat] &f%player%: &e%message%")
                    .replace("%player%", player.getName())
                    .replace("%message%", message));
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.hasPermission("lifesteal.eventchat")) {
                    onlinePlayer.sendMessage(formattedMessage);
                }
            }
        }
    }

    private class EventCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(color(config.getString("messages.usage", "&cИспользование: /event <kick|chat|mute|ban|check|checkaddtime|checkrevise|checkban|checkbanpriz|checkchat|dupeip|baninfo|unban|help>")));
                return true;
            }

            if (args[0].equalsIgnoreCase("kick")) {
                if (!sender.hasPermission("lifesteal.event.kick")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.kick-usage", "&cИспользование: /event kick <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                target.setHealth(0);
                target.setGameMode(GameMode.SPECTATOR);
                sender.sendMessage(color(config.getString("messages.kick-success", "&aИгрок %player% убит и переведён в режим наблюдателя!").replace("%player%", target.getName())));
                target.sendMessage(color(config.getString("messages.kick-target", "&cВы были убиты и переведены в режим наблюдателя!")));
                return true;
            }

            if (args[0].equalsIgnoreCase("mute")) {
                if (!sender.hasPermission("lifesteal.event.mute")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(color(config.getString("messages.mute-usage", "&cИспользование: /event mute <ник> <причина> <время>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                String reason = String.join(" ", args).substring(args[0].length() + args[1].length() + 2, args[0].length() + args[1].length() + args[2].length() + 3).trim();
                String durationStr = args[args.length - 1];
                long duration = parseDuration(durationStr);
                if (duration == -1) {
                    sender.sendMessage(color(config.getString("messages.invalid-duration", "&cНеверный формат времени! Используйте: 5m, 4d, 1h, 30s")));
                    return true;
                }
                long expiry = System.currentTimeMillis() + duration;
                mutedPlayers.put(target.getUniqueId(), expiry);
                saveMutes();
                sender.sendMessage(color(config.getString("messages.mute-success", "&aИгрок %player% замучен на %duration% по причине: %reason%")
                        .replace("%player%", target.getName())
                        .replace("%duration%", durationStr)
                        .replace("%reason%", reason)));
                target.sendMessage(color(config.getString("messages.mute-target", "&cВы замучены на %duration% по причине: %reason%")
                        .replace("%duration%", durationStr)
                        .replace("%reason%", reason)));
                return true;
            }

            if (args[0].equalsIgnoreCase("ban")) {
                if (!sender.hasPermission("lifesteal.event.ban")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(color(config.getString("messages.ban-usage", "&cИспользование: /event ban <ник> <причина> <время>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                String reason = String.join(" ", args).substring(args[0].length() + args[1].length() + 2, args[0].length() + args[1].length() + args[2].length() + 3).trim();
                String durationStr = args[args.length - 1];
                long duration = parseDuration(durationStr);
                if (duration == -1) {
                    sender.sendMessage(color(config.getString("messages.invalid-duration", "&cНеверный формат времени! Используйте: 5m, 4d, 1h, 30s")));
                    return true;
                }
                long expiry = System.currentTimeMillis() + duration;
                bannedPlayers.put(target.getUniqueId(), expiry);
                saveBans();
                target.kickPlayer(color(config.getString("messages.ban-target", "&cВы забанены на %duration% по причине: %reason%")
                        .replace("%duration%", durationStr)
                        .replace("%reason%", reason)));
                sender.sendMessage(color(config.getString("messages.ban-success", "&aИгрок %player% забанен на %duration% по причине: %reason%")
                        .replace("%player%", target.getName())
                        .replace("%duration%", durationStr)
                        .replace("%reason%", reason)));
                return true;
            }

            if (args[0].equalsIgnoreCase("check")) {
                if (!sender.hasPermission("lifesteal.event.check")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.check-usage", "&cИспользование: /event check <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                if (checkedPlayers.containsKey(target.getUniqueId())) {
                    sender.sendMessage(color(config.getString("messages.check-already", "&cИгрок %player% уже на проверке!").replace("%player%", target.getName())));
                    return true;
                }
                checkedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + 5 * 60 * 1000);
                if (sender instanceof Player) {
                    checkers.put(target.getUniqueId(), ((Player) sender).getUniqueId());
                }
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 5, false, false));
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false));
                target.sendTitle(color(config.getString("messages.check-title", "&cПроверка на читы, пишите свой Discord")),
                        color(config.getString("messages.check-subtitle", "&eОсталось времени: %time%")), 10, 100, 10);
                sender.sendMessage(color(config.getString("messages.check-success", "&aИгрок %player% вызван на проверку!").replace("%player%", target.getName())));
                target.sendMessage(color(config.getString("messages.check-target", "&cВы на проверке! Скиньте ваш Discord в чат.")));
                return true;
            }

            if (args[0].equalsIgnoreCase("checkaddtime")) {
                if (!sender.hasPermission("lifesteal.event.check")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.checkaddtime-usage", "&cИспользование: /event checkaddtime <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                if (!checkedPlayers.containsKey(target.getUniqueId())) {
                    sender.sendMessage(color(config.getString("messages.check-not-found", "&cИгрок %player% не на проверке!").replace("%player%", target.getName())));
                    return true;
                }
                checkedPlayers.put(target.getUniqueId(), checkedPlayers.get(target.getUniqueId()) + 5 * 60 * 1000);
                sender.sendMessage(color(config.getString("messages.checkaddtime-success", "&aВремя проверки для %player% продлено на 5 минут!").replace("%player%", target.getName())));
                target.sendMessage(color(config.getString("messages.checkaddtime-target", "&cВремя вашей проверки продлено на 5 минут!")));
                return true;
            }

            if (args[0].equalsIgnoreCase("checkrevise")) {
                if (!sender.hasPermission("lifesteal.event.check")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.checkrevise-usage", "&cИспользование: /event checkrevise <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                if (!checkedPlayers.containsKey(target.getUniqueId())) {
                    sender.sendMessage(color(config.getString("messages.check-not-found", "&cИгрок %player% не на проверке!").replace("%player%", target.getName())));
                    return true;
                }
                checkedPlayers.remove(target.getUniqueId());
                checkers.remove(target.getUniqueId());
                target.removePotionEffect(PotionEffectType.SLOW);
                target.removePotionEffect(PotionEffectType.BLINDNESS);
                sender.sendMessage(color(config.getString("messages.checkrevise-success", "&aИгрок %player% признан чистым!").replace("%player%", target.getName())));
                target.sendMessage(color(config.getString("messages.checkrevise-target", "&aВы признаны чистым и сняты с проверки!")));
                return true;
            }

            if (args[0].equalsIgnoreCase("checkban")) {
                if (!sender.hasPermission("lifesteal.event.check")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.checkban-usage", "&cИспользование: /event checkban <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                if (!checkedPlayers.containsKey(target.getUniqueId())) {
                    sender.sendMessage(color(config.getString("messages.check-not-found", "&cИгрок %player% не на проверке!").replace("%player%", target.getName())));
                    return true;
                }
                checkedPlayers.remove(target.getUniqueId());
                checkers.remove(target.getUniqueId());
                target.removePotionEffect(PotionEffectType.SLOW);
                target.removePotionEffect(PotionEffectType.BLINDNESS);
                bannedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000);
                saveBans();
                target.kickPlayer(color(config.getString("messages.checkban-target", "&cВы забанены на 7 дней за читы!")));
                sender.sendMessage(color(config.getString("messages.checkban-success", "&aИгрок %player% забанен на 7 дней за читы!").replace("%player%", target.getName())));
                return true;
            }

            if (args[0].equalsIgnoreCase("checkbanpriz")) {
                if (!sender.hasPermission("lifesteal.event.check")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.checkbanpriz-usage", "&cИспользование: /event checkbanpriz <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                if (!checkedPlayers.containsKey(target.getUniqueId())) {
                    sender.sendMessage(color(config.getString("messages.check-not-found", "&cИгрок %player% не на проверке!").replace("%player%", target.getName())));
                    return true;
                }
                checkedPlayers.remove(target.getUniqueId());
                checkers.remove(target.getUniqueId());
                target.removePotionEffect(PotionEffectType.SLOW);
                target.removePotionEffect(PotionEffectType.BLINDNESS);
                bannedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + 4 * 24 * 60 * 60 * 1000);
                saveBans();
                target.kickPlayer(color(config.getString("messages.checkbanpriz-target", "&cВы забанены на 4 дня за читы!")));
                sender.sendMessage(color(config.getString("messages.checkbanpriz-success", "&aИгрок %player% забанен на 4 дня за читы!").replace("%player%", target.getName())));
                return true;
            }

            if (args[0].equalsIgnoreCase("checkchat")) {
                if (!sender.hasPermission("lifesteal.event.check")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(color(config.getString("messages.checkchat-usage", "&cИспользование: /event checkchat <ник> <сообщение>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                if (!checkedPlayers.containsKey(target.getUniqueId())) {
                    sender.sendMessage(color(config.getString("messages.check-not-found", "&cИгрок %player% не на проверке!").replace("%player%", target.getName())));
                    return true;
                }
                String message = String.join(" ", args).substring(args[0].length() + args[1].length() + 2).trim();
                String formattedMessage = color(config.getString("check.format", "&c[Проверка] &f%player%: &e%message%")
                        .replace("%player%", sender instanceof Player ? ((Player) sender).getName() : "LS")
                        .replace("%message%", message));
                target.sendMessage(formattedMessage);
                if (sender instanceof Player) {
                    ((Player) sender).sendMessage(formattedMessage);
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("dupeip")) {
                if (!sender.hasPermission("lifesteal.event.dupeip")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.dupeip-usage", "&cИспользование: /event dupeip <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                String ip = playerIPs.get(target.getUniqueId());
                sender.sendMessage(color(config.getString("messages.dupeip-header", "&eИгроки с IP %ip%:").replace("%ip%", ip)));
                for (Map.Entry<UUID, String> entry : playerIPs.entrySet()) {
                    if (entry.getValue().equals(ip)) {
                        Player otherPlayer = Bukkit.getPlayer(entry.getKey());
                        if (otherPlayer != null) {
                            String name = otherPlayer.getName();
                            if (bannedPlayers.containsKey(entry.getKey())) {
                                name = "&c" + name;
                            }
                            sender.sendMessage(color(" - " + name));
                        }
                    }
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("baninfo")) {
                if (!sender.hasPermission("lifesteal.event.baninfo")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.baninfo-usage", "&cИспользование: /event baninfo <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                UUID targetId = target.getUniqueId();
                if (bannedPlayers.containsKey(targetId)) {
                    long expiry = bannedPlayers.get(targetId);
                    long remaining = (expiry - System.currentTimeMillis()) / 1000;
                    long days = remaining / (24 * 60 * 60);
                    long hours = (remaining % (24 * 60 * 60)) / (60 * 60);
                    long minutes = (remaining % (60 * 60)) / 60;
                    String timeLeft = String.format("%dd %dh %dm", days, hours, minutes);
                    sender.sendMessage(color(config.getString("messages.baninfo-banned", "&eИгрок %player% забанен до %time%!")
                            .replace("%player%", target.getName())
                            .replace("%time%", timeLeft)));
                } else {
                    sender.sendMessage(color(config.getString("messages.baninfo-not-banned", "&eИгрок %player% не забанен!").replace("%player%", target.getName())));
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("unban")) {
                if (!sender.hasPermission("lifesteal.event.unban")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(color(config.getString("messages.unban-usage", "&cИспользование: /event unban <ник>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !target.isOnline()) {
                    sender.sendMessage(color(config.getString("messages.player-not-found", "&cИгрок %player% не найден!").replace("%player%", args[1])));
                    return true;
                }
                UUID targetId = target.getUniqueId();
                if (!bannedPlayers.containsKey(targetId)) {
                    sender.sendMessage(color(config.getString("messages.unban-not-banned", "&cИгрок %player% не забанен!").replace("%player%", target.getName())));
                    return true;
                }
                bannedPlayers.remove(targetId);
                bansConfig.set(targetId.toString(), null);
                saveBans();
                sender.sendMessage(color(config.getString("messages.unban-success", "&aИгрок %player% разбанен!").replace("%player%", target.getName())));
                return true;
            }

            if (args[0].equalsIgnoreCase("chat")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(color(config.getString("messages.player-only", "&cЭта команда только для игроков!")));
                    return true;
                }
                if (!sender.hasPermission("lifesteal.eventchat")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                Player player = (Player) sender;
                UUID playerId = player.getUniqueId();

                if (args.length == 1) {
                    if (eventChatPlayers.contains(playerId)) {
                        eventChatPlayers.remove(playerId);
                        player.sendMessage(color(config.getString("messages.chat-disabled", "&aРежим Event Chat выключен.")));
                    } else {
                        eventChatPlayers.add(playerId);
                        player.sendMessage(color(config.getString("messages.chat-enabled", "&aРежим Event Chat включен. Все ваши сообщения будут отправляться в Event Chat.")));
                    }
                    return true;
                }

                String message = String.join(" ", args).substring(5).trim();
                if (message.isEmpty()) {
                    sender.sendMessage(color(config.getString("messages.chat-usage", "&cИспользование: /event chat <сообщение>")));
                    return true;
                }
                String formattedMessage = color(config.getString("chat.format", "&7[Event Chat] &f%player%: &e%message%")
                        .replace("%player%", player.getName())
                        .replace("%message%", message));
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.hasPermission("lifesteal.eventchat")) {
                        onlinePlayer.sendMessage(formattedMessage);
                    }
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("help")) {
                if (!sender.hasPermission("lifesteal.event")) {
                    sender.sendMessage(color(config.getString("messages.no-permission", "&cУ вас нет прав!")));
                    return true;
                }
                sender.sendMessage(color(config.getString("messages.help-header", "&eПривет, дорогой ЛС, наверняка ты новичок раз смотришь команды!")));
                for (String line : config.getStringList("messages.help-commands")) {
                    sender.sendMessage(color(line));
                }
                return true;
            }

            sender.sendMessage(color(config.getString("messages.usage", "&cИспользование: /event <kick|chat|mute|ban|check|checkaddtime|checkrevise|checkban|checkbanpriz|checkchat|dupeip|baninfo|unban|help>")));
            return true;
        }
    }
}