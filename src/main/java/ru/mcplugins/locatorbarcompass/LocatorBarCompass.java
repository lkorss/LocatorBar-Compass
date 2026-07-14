package ru.mcplugins.locatorbarcompass;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class LocatorBarCompass extends JavaPlugin implements Listener, CommandExecutor {

    private boolean featureEnabled;
    private boolean checkOffhand;
    private Set<Material> compassMaterials;
    private double fullRange;
    private double disabledRange;
    private double fullTransmitRange;
    private BukkitTask periodicTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("locatorbarcompass")).setExecutor(this);

        startPeriodicTask();

        // Подхватываем уже онлайн игроков (например, при /reload плагина)
        for (Player p : Bukkit.getOnlinePlayers()) {
            updatePlayer(p);
        }

        getLogger().info("LocatorBarCompass включён. Feature enabled: " + featureEnabled);
    }

    @Override
    public void onDisable() {
        if (periodicTask != null) {
            periodicTask.cancel();
        }
        // При выключении плагина возвращаем всем ваниль, чтобы не оставлять игроков с range=0
        for (Player p : Bukkit.getOnlinePlayers()) {
            resetToVanilla(p);
        }
    }

    // ---------------------------------------------------------------
    // Конфиг
    // ---------------------------------------------------------------

    private void loadConfigValues() {
        reloadConfig();
        FileConfiguration c = getConfig();

        featureEnabled = c.getBoolean("enabled", true);
        checkOffhand = c.getBoolean("check-offhand", true);
        fullRange = c.getDouble("full-receive-range", 60000000.0);
        disabledRange = c.getDouble("disabled-receive-range", 0.0);
        fullTransmitRange = c.getDouble("full-transmit-range", 60000000.0);

        Set<Material> materials = c.getStringList("compass-materials").stream()
                .map(this::parseMaterial)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (materials.isEmpty()) {
            getLogger().warning("compass-materials пуст или некорректен, использую COMPASS по умолчанию.");
            materials.add(Material.COMPASS);
        }
        compassMaterials = materials;
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Неизвестный материал в compass-materials: " + name);
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Основная логика
    // ---------------------------------------------------------------

    private void startPeriodicTask() {
        long interval = Math.max(1, getConfig().getLong("check-interval", 10));
        periodicTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                updatePlayer(p);
            }
        }, 1L, interval);
    }

    /**
     * Пересчитывает и, при необходимости, выставляет игроку нужные waypoint-атрибуты.
     *
     * waypoint_transmit_range ("виден ли Я другим") всегда держится максимальным для ВСЕХ
     * игроков и не зависит от компаса - иначе игрок без компаса пропадал бы с бара
     * даже у тех, кто компас держит.
     *
     * waypoint_receive_range ("вижу ли Я других") - вот это как раз и завязано на компас.
     */
    private void updatePlayer(Player player) {
        // Всегда держим transmit на максимуме, независимо от компаса/фичи/пермишенов -
        // это то, что делает игрока видимым другим.
        AttributeInstance transmitAttribute = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (transmitAttribute != null) {
            applyRange(transmitAttribute, fullTransmitRange);
        }

        AttributeInstance receiveAttribute = player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (receiveAttribute == null) {
            return; // на всякий случай, если атрибута нет у этого типа сущности
        }

        // Фича выключена в конфиге ИЛИ у игрока есть permission на "ванильную работу компаса"
        // -> локатор бар работает как обычно, не завязан на предмет в руке.
        if (!featureEnabled || player.hasPermission("locatorbar.bypass")) {
            applyRange(receiveAttribute, fullRange);
            return;
        }

        boolean holdingCompass = isHoldingCompass(player);
        applyRange(receiveAttribute, holdingCompass ? fullRange : disabledRange);
    }

    private boolean isHoldingCompass(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (compassMaterials.contains(mainHand.getType())) {
            return true;
        }
        if (checkOffhand) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            return compassMaterials.contains(offHand.getType());
        }
        return false;
    }

    private void applyRange(AttributeInstance attribute, double value) {
        if (attribute.getBaseValue() != value) {
            attribute.setBaseValue(value);
        }
    }

    private void resetToVanilla(Player player) {
        AttributeInstance receiveAttribute = player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (receiveAttribute != null) {
            receiveAttribute.setBaseValue(fullRange);
        }
        AttributeInstance transmitAttribute = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (transmitAttribute != null) {
            transmitAttribute.setBaseValue(fullTransmitRange);
        }
    }

    // ---------------------------------------------------------------
    // События - мгновенная реакция на смену предмета в руке
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        // Выполняем через 1 тик, т.к. в момент события инвентарь ещё не обновлён
        Bukkit.getScheduler().runTask(this, () -> updatePlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updatePlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updatePlayer(event.getPlayer());
    }

    // ---------------------------------------------------------------
    // Команды
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/" + label + " reload §f- перезагрузить конфиг");
            sender.sendMessage("§7/" + label + " toggle §f- вкл/выкл фичу компаса");
            sender.sendMessage("§7/" + label + " status §f- показать текущее состояние");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadConfigValues();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updatePlayer(p);
                }
                sender.sendMessage("§aLocatorBarCompass: конфиг перезагружен.");
            }
            case "toggle" -> {
                featureEnabled = !featureEnabled;
                getConfig().set("enabled", featureEnabled);
                saveConfig();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updatePlayer(p);
                }
                sender.sendMessage("§aLocatorBarCompass: фича " + (featureEnabled ? "§aвключена" : "§cвыключена") + "§a.");
            }
            case "status" -> {
                sender.sendMessage("§7Фича включена: §f" + featureEnabled);
                sender.sendMessage("§7Проверка оффхенда: §f" + checkOffhand);
                sender.sendMessage("§7Материалы-компасы: §f" + compassMaterials);
                sender.sendMessage("§7Интервал проверки: §f" + getConfig().getLong("check-interval", 10) + " тиков");
            }
            default -> sender.sendMessage("§cНеизвестная подкоманда. Используй reload / toggle / status.");
        }
        return true;
    }
}
