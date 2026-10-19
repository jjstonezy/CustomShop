
package com.customshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CustomShopPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private Economy eco;

    private final Map<UUID, String> currentCategory = new HashMap<>();
    private final Map<UUID, Map<Integer, Material>> sellSlots = new HashMap<>();

    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Map<UUID, String> bountyNames = new HashMap<>();
    private final Map<UUID, Set<UUID>> bountyPosters = new HashMap<>();
    private final Map<UUID, UUID> selectedTargets = new HashMap<>();
    private final Set<UUID> awaitingCustomAmount = new HashSet<>();

    private File bountyFile;
    private YamlConfiguration bountyData;

    private final String categoryTitle = color("&8Shop Category");
    private final String sellTitle = color("&c&lSell Items");
    private final String bountyTargetTitle = color("&8&lChoose Bounty Target");
    private final String bountyAmountTitle = color("&8&lChoose Bounty Amount");
    private final String menuTitle1 = color("&8&lSite Chaos Menu &7(Page 1/2)");
    private final String menuTitle2 = color("&8&lSite Chaos Menu &7(Page 2/2)");

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> provider =
                getServer().getServicesManager().getRegistration(Economy.class);

        if (provider == null || provider.getProvider() == null) {
            getLogger().severe("Vault economy missing!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        eco = provider.getProvider();

        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("bounty")).setExecutor(this);
        Objects.requireNonNull(getCommand("bounties")).setExecutor(this);
        Objects.requireNonNull(getCommand("menu"), "menu command missing from plugin.yml").setExecutor(this);

        bountyFile = new File(getDataFolder(), "bounties.yml");
        loadBounties();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CustomShop enabled with bounty system!");
    }

    @Override
    public void onDisable() {
        saveBounties();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bounties")) {
            showLeaderboard(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("menu")) {
            openMenu(player, 1);
        } else if (command.getName().equalsIgnoreCase("bounty")) {
            openBountyTargets(player);
        } else {
            openMain(player);
        }

        return true;
    }

    // ==================== SHOP ====================

    private void openMain(Player player) {
        String title = color(getConfig().getString(
                "shop-title", "&a&lServer Shop"));

        Inventory inv = Bukkit.createInventory(
                null, getConfig().getInt("size", 27), title);

        ConfigurationSection categories =
                getConfig().getConfigurationSection("categories");

        if (categories != null) {
            for (String key : categories.getKeys(false)) {
                ConfigurationSection cat =
                        categories.getConfigurationSection(key);
                if (cat == null) continue;

                Material material = Material.matchMaterial(
                        cat.getString("material", "CHEST"));
                if (material == null) material = Material.CHEST;

                ItemStack icon = new ItemStack(material);
                ItemMeta meta = icon.getItemMeta();
                meta.setDisplayName(color(cat.getString("name", key)));
                meta.setLore(List.of(color("&7Click to browse")));
                icon.setItemMeta(meta);

                int slot = cat.getInt("slot", 0);
                if (slot >= 0 && slot < inv.getSize()) {
                    inv.setItem(slot, icon);
                }
            }
        }

        ItemStack sell = new ItemStack(Material.GOLD_INGOT);
        ItemMeta sellMeta = sell.getItemMeta();
        sellMeta.setDisplayName(color("&c&lSell Items"));
        sellMeta.setLore(List.of(
                color("&7Sell items from your inventory"),
                color("&eYou receive 70% of the buy price")));
        sell.setItemMeta(sellMeta);
        inv.setItem(inv.getSize() - 1, sell);

        currentCategory.remove(player.getUniqueId());
        sellSlots.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    // ==================== SERVER MENU ====================

    private void openMenu(Player player, int page) {
        int safePage = page == 2 ? 2 : 1;
        String title = safePage == 1 ? menuTitle1 : menuTitle2;
        Inventory inv = Bukkit.createInventory(null, 54, title);

        if (safePage == 1) {
            inv.setItem(10, makeItem(Material.CHEST, "&a&lShop",
                    List.of("&7Browse the server shop", "&eClick to open")));
            inv.setItem(12, makeItem(Material.PLAYER_HEAD, "&c&lBounty Board",
                    List.of("&7Place bounties on online players", "&7View and claim bounties", "&eClick to open")));
            inv.setItem(14, makeItem(Material.EMERALD, "&6&lAuction Market",
                    List.of("&7Player-to-player item trading", "&eComing soon")));
            inv.setItem(16, makeItem(Material.ENCHANTED_BOOK, "&d&lKits",
                    List.of("&7Claim server kits", "&eComing soon")));
            inv.setItem(53, makeItem(Material.ARROW, "&eNext Page &7(2/2)",
                    List.of("&7Click to view more features")));
        } else {
            inv.setItem(10, makeItem(Material.COMPASS, "&b&lPlayer Warps",
                    List.of("&7Visit player-created warps", "&eComing soon")));
            inv.setItem(12, makeItem(Material.BOOK, "&a&lQuests",
                    List.of("&7View server quests", "&eComing soon")));
            inv.setItem(14, makeItem(Material.NETHER_STAR, "&d&lMore Features",
                    List.of("&7More server features will appear here", "&eComing soon")));
            inv.setItem(45, makeItem(Material.ARROW, "&ePrevious Page &7(1/2)",
                    List.of("&7Return to the first page")));
            inv.setItem(49, makeItem(Material.BARRIER, "&c&lClose Menu",
                    List.of("&7Click to close this menu")));
        }

        currentCategory.remove(player.getUniqueId());
        sellSlots.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openCategory(Player player, String key) {
        ConfigurationSection cat =
                getConfig().getConfigurationSection("categories." + key);
        if (cat == null) return;

        Inventory inv = Bukkit.createInventory(null, 54, categoryTitle);
        ConfigurationSection items = cat.getConfigurationSection("items");

        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection item =
                        items.getConfigurationSection(id);
                if (item == null) continue;

                Material material = Material.matchMaterial(
                        item.getString("material", ""));
                if (material == null) continue;

                ItemStack stack = new ItemStack(
                        material, Math.max(1, item.getInt("amount", 1)));

                ItemMeta meta = stack.getItemMeta();
                meta.setDisplayName(color(item.getString(
                        "name", "&f" + material.name())));
                meta.setLore(List.of(
                        color("&7Buy amount: " + stack.getAmount()),
                        color("&6Price: $" + item.getDouble("price")),
                        color("&eClick to buy")));
                stack.setItemMeta(meta);

                int slot = item.getInt("slot", 0);
                if (slot >= 0 && slot < 45) {
                    inv.setItem(slot, stack);
                }
            }
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(color("&cBack to Shop"));
        back.setItemMeta(backMeta);
        inv.setItem(45, back);

        currentCategory.put(player.getUniqueId(), key);
        sellSlots.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openSell(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, sellTitle);
        Map<Integer, Material> slots = new HashMap<>();
        Set<Material> seen = new HashSet<>();
        int slot = 0;

        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()
                    || seen.contains(stack.getType())) continue;

            Material material = stack.getType();
            ConfigurationSection item = findItem(material);
            if (item == null) continue;

            seen.add(material);

            double unitPrice = item.getDouble("price")
                    / Math.max(1, item.getInt("amount", 1));
            double sellPrice = unitPrice * 0.70;

            ItemStack icon = new ItemStack(material);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(color("&f" + material.name()));
            meta.setLore(List.of(
                    color("&7You have: " +
                            player.getInventory().all(material).values()
                                    .stream().mapToInt(ItemStack::getAmount).sum()),
                    color("&6Sell price each: $" +
                            String.format(Locale.US, "%.2f", sellPrice)),
                    color("&eClick to sell ALL of this item")));
            icon.setItemMeta(meta);

            inv.setItem(slot, icon);
            slots.put(slot, material);
            slot++;

            if (slot >= 45) break;
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta meta = back.getItemMeta();
        meta.setDisplayName(color("&cBack to Shop"));
        back.setItemMeta(meta);
        inv.setItem(49, back);

        sellSlots.put(player.getUniqueId(), slots);
        currentCategory.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    private ConfigurationSection findItem(Material material) {
        ConfigurationSection categories =
                getConfig().getConfigurationSection("categories");
        if (categories == null) return null;

        for (String key : categories.getKeys(false)) {
            ConfigurationSection items =
                    categories.getConfigurationSection(key + ".items");
            if (items == null) continue;

            for (String id : items.getKeys(false)) {
                ConfigurationSection item =
                        items.getConfigurationSection(id);
                if (item != null && material.name().equalsIgnoreCase(
                        item.getString("material", ""))) {
                    return item;
                }
            }
        }

        return null;
    }

    // ==================== BOUNTY MENUS ====================

    private void openBountyTargets(Player player) {
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        targets.removeIf(target ->
                target.getUniqueId().equals(player.getUniqueId()));
        targets.sort(Comparator.comparing(
                Player::getName, String.CASE_INSENSITIVE_ORDER));

        int size = Math.max(9, Math.min(54,
                ((targets.size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(null, size, bountyTargetTitle);

        if (targets.isEmpty()) {
            inv.setItem(4, makeItem(Material.BARRIER,
                    "&cNo other players online",
                    List.of("&7Try again when another player is online.")));
        } else {
            for (int i = 0; i < targets.size() && i < size; i++) {
                Player target = targets.get(i);

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(target);
                meta.setDisplayName(color("&e" + target.getName()));
                meta.setLore(List.of(
                        color("&7Active bounty: &6$" +
                                money(bounties.getOrDefault(
                                        target.getUniqueId(), 0.0))),
                        color("&aClick to choose this player")));
                head.setItemMeta(meta);
                inv.setItem(i, head);
            }
        }

        player.openInventory(inv);
    }

    private void openBountyAmounts(Player player, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);

        if (target == null) {
            player.sendMessage(color("&cThat player is no longer online."));
            openBountyTargets(player);
            return;
        }

        selectedTargets.put(player.getUniqueId(), targetId);

        Inventory inv = Bukkit.createInventory(null, 27, bountyAmountTitle);
        inv.setItem(10, makeItem(Material.GOLD_INGOT, "&e&l$100",
                List.of("&7Place a $100 bounty on " + target.getName(),
                        "&aClick to confirm")));
        inv.setItem(12, makeItem(Material.GOLD_BLOCK, "&6&l$1,000",
                List.of("&7Place a $1,000 bounty on " + target.getName(),
                        "&aClick to confirm")));
        inv.setItem(14, makeItem(Material.EMERALD, "&a&l$10,000",
                List.of("&7Place a $10,000 bounty on " + target.getName(),
                        "&aClick to confirm")));
        inv.setItem(16, makeItem(Material.NAME_TAG, "&b&lCustom Amount",
                List.of("&7Type an amount in chat", "&7Minimum: $1",
                        "&aClick to continue")));
        inv.setItem(22, makeItem(Material.ARROW, "&cChoose another player",
                List.of("&7Return to player selection")));

        player.openInventory(inv);
    }

    private ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));

        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) coloredLore.add(color(line));

        meta.setLore(coloredLore);
        item.setItemMeta(meta);
        return item;
    }

    private void placeBounty(Player poster, UUID targetId, double amount) {
        Player target = Bukkit.getPlayer(targetId);

        if (targetId.equals(poster.getUniqueId())) {
            poster.sendMessage(color("&cYou cannot place a bounty on yourself."));
            return;
        }

        if (target == null) {
            poster.sendMessage(color("&cThat player must be online to place a bounty."));
            return;
        }

        if (!Double.isFinite(amount) || amount < 1
                || amount > 1_000_000_000_000.0) {
            poster.sendMessage(color("&cEnter a valid amount of at least $1."));
            return;
        }

        if (eco.getBalance(poster) < amount) {
            poster.sendMessage(color("&cYou don't have enough money. Your balance is $"
                    + money(eco.getBalance(poster)) + "."));
            return;
        }

        if (!eco.withdrawPlayer(poster, amount).transactionSuccess()) {
            poster.sendMessage(color("&cPayment failed; your bounty was not placed."));
            return;
        }

        UUID id = target.getUniqueId();
        bounties.merge(id, amount, Double::sum);
        bountyNames.put(id, target.getName());
        bountyPosters.computeIfAbsent(id, ignored -> new HashSet<>())
                .add(poster.getUniqueId());

        saveBounties();

        poster.sendMessage(color("&aPlaced a $" + money(amount)
                + " bounty on &e" + target.getName()
                + "&a. Their total active bounty is &6$"
                + money(bounties.get(id)) + "&a."));

        target.sendMessage(color("&cA bounty of $" + money(amount)
                + " has been placed on you! Total: $"
                + money(bounties.get(id)) + "."));
    }

    private void showLeaderboard(CommandSender sender) {
        List<UUID> ids = new ArrayList<>(bounties.keySet());
        ids.removeIf(id -> bounties.getOrDefault(id, 0.0) <= 0);
        ids.sort((a, b) -> Double.compare(
                bounties.getOrDefault(b, 0.0),
                bounties.getOrDefault(a, 0.0)));

        sender.sendMessage(color("&8&m-----------------------------"));
        sender.sendMessage(color("&6&lTop 10 Active Bounties"));

        if (ids.isEmpty()) {
            sender.sendMessage(color("&7There are no active bounties."));
        } else {
            for (int i = 0; i < Math.min(10, ids.size()); i++) {
                UUID id = ids.get(i);
                String name = bountyNames.getOrDefault(id, "Unknown");
                sender.sendMessage(color("&e#" + (i + 1) + " &f" + name
                        + " &7- &6$" + money(bounties.get(id))));
            }
        }

        sender.sendMessage(color("&8&m-----------------------------"));
    }

    // ==================== BOUNTY STORAGE ====================

    private void loadBounties() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }

        bountyData = YamlConfiguration.loadConfiguration(bountyFile);
        ConfigurationSection section =
                bountyData.getConfigurationSection("active");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID targetId = UUID.fromString(key);
                ConfigurationSection entry =
                        section.getConfigurationSection(key);
                if (entry == null) continue;

                double amount = entry.getDouble("amount", 0.0);
                if (amount <= 0 || !Double.isFinite(amount)) continue;

                bounties.put(targetId, amount);
                bountyNames.put(targetId, entry.getString("name", "Unknown"));

                Set<UUID> posters = new HashSet<>();
                for (String poster : entry.getStringList("posters")) {
                    try {
                        posters.add(UUID.fromString(poster));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore invalid poster UUID.
                    }
                }
                bountyPosters.put(targetId, posters);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping invalid bounty target UUID: " + key);
            }
        }
    }

    private void saveBounties() {
        if (bountyFile == null) return;
        if (bountyData == null) bountyData = new YamlConfiguration();

        bountyData.set("active", null);

        for (Map.Entry<UUID, Double> entry : bounties.entrySet()) {
            UUID targetId = entry.getKey();
            String path = "active." + targetId;

            bountyData.set(path + ".amount", entry.getValue());
            bountyData.set(path + ".name",
                    bountyNames.getOrDefault(targetId, "Unknown"));

            List<String> posters = new ArrayList<>();
            for (UUID poster : bountyPosters.getOrDefault(
                    targetId, Collections.emptySet())) {
                posters.add(poster.toString());
            }

            bountyData.set(path + ".posters", posters);
        }

        try {
            bountyData.save(bountyFile);
        } catch (IOException e) {
            getLogger().severe("Could not save bounties.yml: " + e.getMessage());
        }
    }

    // ==================== INVENTORY EVENTS ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String mainTitle = color(getConfig().getString(
                "shop-title", "&a&lServer Shop"));

        boolean bountyMenu = title.equals(bountyTargetTitle)
                || title.equals(bountyAmountTitle);
        boolean menuPage1 = title.equals(menuTitle1);
        boolean menuPage2 = title.equals(menuTitle2);
        boolean serverMenu = menuPage1 || menuPage2;

        if (!title.equals(mainTitle) && !title.equals(categoryTitle)
                && !title.equals(sellTitle) && !bountyMenu && !serverMenu) return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0
                || slot >= event.getView().getTopInventory().getSize()) return;

        if (serverMenu) {
            if (menuPage1) {
                if (slot == 10) {
                    openMain(player);
                } else if (slot == 12) {
                    openBountyTargets(player);
                } else if (slot == 14) {
                    player.sendMessage(color("&6Auction Market is coming soon."));
                } else if (slot == 16) {
                    player.sendMessage(color("&dKits are coming soon."));
                } else if (slot == 53) {
                    openMenu(player, 2);
                }
            } else {
                if (slot == 10) {
                    player.sendMessage(color("&bPlayer Warps are coming soon."));
                } else if (slot == 12) {
                    player.sendMessage(color("&aQuests are coming soon."));
                } else if (slot == 14) {
                    player.sendMessage(color("&dMore server features are coming soon."));
                } else if (slot == 45) {
                    openMenu(player, 1);
                } else if (slot == 49) {
                    player.closeInventory();
                }
            }
            return;
        }

        if (title.equals(bountyTargetTitle)) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

            ItemMeta meta = clicked.getItemMeta();
            if (meta == null || meta.getDisplayName() == null) return;

            String targetName = ChatColor.stripColor(meta.getDisplayName());
            Player target = Bukkit.getPlayerExact(targetName);

            if (target == null
                    || target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(color("&cThat player is no longer available."));
                openBountyTargets(player);
                return;
            }

            openBountyAmounts(player, target.getUniqueId());
            return;
        }

        if (title.equals(bountyAmountTitle)) {
            UUID targetId = selectedTargets.get(player.getUniqueId());

            if (targetId == null) {
                player.sendMessage(color("&cPlease choose a target first."));
                openBountyTargets(player);
                return;
            }

            if (slot == 22) {
                openBountyTargets(player);
            } else if (slot == 10) {
                placeBounty(player, targetId, 100);
                player.closeInventory();
            } else if (slot == 12) {
                placeBounty(player, targetId, 1000);
                player.closeInventory();
            } else if (slot == 14) {
                placeBounty(player, targetId, 10000);
                player.closeInventory();
            } else if (slot == 16) {
                awaitingCustomAmount.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(color("&bType your custom bounty amount in chat (numbers only)."));
                player.sendMessage(color("&7Type &ccancel &7to stop."));
            }
            return;
        }

        if (title.equals(mainTitle)) {
            if (slot == event.getView().getTopInventory().getSize() - 1) {
                openSell(player);
                return;
            }

            ConfigurationSection categories =
                    getConfig().getConfigurationSection("categories");
            if (categories == null) return;

            for (String key : categories.getKeys(false)) {
                ConfigurationSection cat =
                        categories.getConfigurationSection(key);
                if (cat != null && cat.getInt("slot", 0) == slot) {
                    openCategory(player, key);
                    return;
                }
            }
            return;
        }

        if (slot == 45 && title.equals(categoryTitle)) {
            openMain(player);
            return;
        }

        if (slot == 49 && title.equals(sellTitle)) {
            openMain(player);
            return;
        }

        if (title.equals(categoryTitle)) {
            String key = currentCategory.get(player.getUniqueId());
            if (key == null) return;

            ConfigurationSection items =
                    getConfig().getConfigurationSection(
                            "categories." + key + ".items");
            if (items == null) return;

            for (String id : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(id);
                if (item == null || item.getInt("slot", 0) != slot) continue;

                Material material = Material.matchMaterial(
                        item.getString("material", ""));
                if (material == null) return;

                int amount = Math.max(1, item.getInt("amount", 1));
                double price = item.getDouble("price");

                if (!Double.isFinite(price) || price < 0) {
                    player.sendMessage(color("&cThis item has an invalid price."));
                    return;
                }

                if (eco.getBalance(player) < price) {
                    player.sendMessage(color("&cNot enough money!"));
                    return;
                }

                if (player.getInventory().firstEmpty() == -1) {
                    player.sendMessage(color("&cYour inventory is full!"));
                    return;
                }

                if (!eco.withdrawPlayer(player, price).transactionSuccess()) {
                    player.sendMessage(color("&cPayment failed."));
                    return;
                }

                Map<Integer, ItemStack> leftovers =
                        player.getInventory().addItem(new ItemStack(material, amount));

                if (!leftovers.isEmpty()) {
                    eco.depositPlayer(player, price);
                    player.sendMessage(color(
                            "&cPurchase cancelled because your inventory is full; money refunded."));
                    return;
                }

                player.sendMessage(color("&aBought " + amount + " "
                        + material.name() + " for $" + money(price)));
                return;
            }
            return;
        }

        if (title.equals(sellTitle)) {
            Material material = sellSlots.getOrDefault(
                    player.getUniqueId(), Collections.emptyMap()).get(slot);
            if (material == null) return;

            ConfigurationSection item = findItem(material);
            if (item == null) return;

            int amount = 0;
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == material) {
                    amount += stack.getAmount();
                }
            }

            if (amount <= 0) {
                player.sendMessage(color("&cYou don't have that item."));
                openSell(player);
                return;
            }

            double unitPrice = item.getDouble("price")
                    / Math.max(1, item.getInt("amount", 1));
            double total = unitPrice * 0.70 * amount;

            for (int i = 0; i < player.getInventory().getStorageContents().length; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack != null && stack.getType() == material) {
                    player.getInventory().setItem(i, null);
                }
            }

            eco.depositPlayer(player, total);
            player.sendMessage(color("&aSold " + amount + " "
                    + material.name() + " for $"
                    + String.format(Locale.US, "%.2f", total)));
            openSell(player);
        }
    }

    // ==================== CUSTOM AMOUNT CHAT ====================

    @EventHandler
    public void onCustomAmountChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!awaitingCustomAmount.contains(playerId)) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                awaitingCustomAmount.remove(playerId);
                return;
            }

            if (message.equalsIgnoreCase("cancel")) {
                awaitingCustomAmount.remove(playerId);
                player.sendMessage(color("&7Custom bounty cancelled."));
                return;
            }

            double amount;
            try {
                amount = Double.parseDouble(
                        message.replace("$", "").replace(",", ""));
            } catch (NumberFormatException ex) {
                player.sendMessage(color(
                        "&cInvalid amount. Enter a number, or type cancel."));
                return;
            }

            UUID targetId = selectedTargets.get(playerId);
            awaitingCustomAmount.remove(playerId);

            if (targetId == null) {
                player.sendMessage(color("&cNo target selected. Use /bounty to try again."));
                return;
            }

            placeBounty(player, targetId, amount);
        });
    }

    // ==================== BOUNTY CLAIMS ====================

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID targetId = victim.getUniqueId();
        double amount = bounties.getOrDefault(targetId, 0.0);
        Player killer = victim.getKiller();

        if (amount <= 0 || killer == null) return;

        // A contributor cannot claim the bounty they helped fund.
        if (bountyPosters.getOrDefault(targetId, Collections.emptySet())
                .contains(killer.getUniqueId())) {
            killer.sendMessage(color("&cYou cannot claim a bounty you contributed to."));
            return;
        }

        if (eco.depositPlayer(killer, amount).transactionSuccess()) {
            bounties.remove(targetId);
            bountyNames.remove(targetId);
            bountyPosters.remove(targetId);
            saveBounties();

            killer.sendMessage(color("&aBounty claimed! You received &6$"
                    + money(amount) + "&a for killing &e"
                    + victim.getName() + "&a."));

            Bukkit.broadcastMessage(color("&6&lBOUNTY CLAIMED! &e"
                    + killer.getName() + " &7claimed &6$"
                    + money(amount) + " &7for killing &c"
                    + victim.getName() + "&7."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        awaitingCustomAmount.remove(id);
        selectedTargets.remove(id);
        currentCategory.remove(id);
        sellSlots.remove(id);
    }

    private String money(double amount) {
        return String.format(Locale.US, "%,.2f", amount)
                .replaceAll("\\.00$", "");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&',
                text == null ? "" : text);
    }
}
