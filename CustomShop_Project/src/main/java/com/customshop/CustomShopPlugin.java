
package com.customshop;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    private static final String PREFIX =
            "\u00A7x\u00A7C\u00A70\u00A78\u00A74\u00A7F\u00A7C\u00A7l[SITE CHAOS] - \u00A7r";
    private static final int MAX_LISTINGS = 10;
    private static final double SELLER_SHARE = 0.95;

    private Economy eco;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, String> currentCategory = new HashMap<>();
    private final Map<UUID, Map<Integer, Material>> sellSlots = new HashMap<>();

    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Map<UUID, String> bountyNames = new HashMap<>();
    private final Map<UUID, Set<UUID>> bountyPosters = new HashMap<>();
    private final Map<UUID, UUID> selectedTargets = new HashMap<>();
    private final Set<UUID> awaitingCustomAmount = new HashSet<>();

    private final Map<UUID, AuctionListing> listings = new LinkedHashMap<>();
    private final Map<UUID, Double> collectionMoney = new HashMap<>();
    private final Map<UUID, List<ItemStack>> collectionItems = new HashMap<>();
    private final Map<UUID, List<UUID>> auctionPageListings = new HashMap<>();
    private final Map<UUID, Integer> auctionPages = new HashMap<>();

    private File bountyFile;
    private File auctionFile;
    private File collectionFile;
    private YamlConfiguration bountyData;
    private YamlConfiguration auctionData;
    private YamlConfiguration collectionData;

    private final String categoryTitle = color("&8Shop Category");
    private final String sellTitle = color("&c&lSell Items");
    private final String bountyTargetTitle = color("&8&lChoose Bounty Target");
    private final String bountyAmountTitle = color("&8&lChoose Bounty Amount");
    private final String menuTitle1 = color("&8&lSite Chaos Menu &7(Page 1/2)");
    private final String menuTitle2 = color("&8&lSite Chaos Menu &7(Page 2/2)");
    private final String auctionTitle = color("&8&lAuction House");
    private final String myListingsTitle = color("&8&lMy Auction Listings");

    private static class AuctionListing {
        UUID id;
        UUID sellerId;
        String sellerName;
        double price;
        ItemStack item;

        AuctionListing(UUID id, UUID sellerId, String sellerName,
                       double price, ItemStack item) {
            this.id = id;
            this.sellerId = sellerId;
            this.sellerName = sellerName;
            this.price = price;
            this.item = item.clone();
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> provider =
                getServer().getServicesManager().getRegistration(Economy.class);

        if (provider == null || provider.getProvider() == null) {
            getLogger().severe("Vault economy missing! Disabling CustomShop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        eco = provider.getProvider();

        String[] commands = {"shop", "bounty", "bounties", "menu", "ah"};
        for (String name : commands) {
            if (getCommand(name) == null) {
                getLogger().severe("Missing command in plugin.yml: " + name);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getCommand(name).setExecutor(this);
        }

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }

        bountyFile = new File(getDataFolder(), "bounties.yml");
        auctionFile = new File(getDataFolder(), "auctions.yml");
        collectionFile = new File(getDataFolder(), "auction-collections.yml");

        loadBounties();
        loadAuctions();
        loadCollections();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CustomShop enabled: shop, bounties, and persistent auction house.");
    }

    @Override
    public void onDisable() {
        saveBounties();
        saveAuctions();
        saveCollections();
    }

    // -------------------- MESSAGES --------------------

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + color(message));
    }

    private void broadcast(String message) {
        Bukkit.broadcastMessage(PREFIX + color(message));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String formatTitle(String text) {
        if (text == null || text.isBlank()) {
            text = "<green><bold>Server Shop</bold></green>";
        }
        try {
            if (text.contains("<") && text.contains(">")) {
                return LegacyComponentSerializer.legacySection().serialize(
                        miniMessage.deserialize(text));
            }
        } catch (Exception ex) {
            getLogger().warning("Invalid MiniMessage shop title: " + ex.getMessage());
        }
        return color(text);
    }

    private String money(double amount) {
        return String.format(Locale.US, "%,.2f", amount).replaceAll("\\.00$", "");
    }

    private String readableItemName(Material material) {
        StringJoiner result = new StringJoiner(" ");
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                result.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            }
        }
        return result.toString();
    }

    private ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> colored = new ArrayList<>();
            for (String line : lore) colored.add(color(line));
            meta.setLore(colored);
            item.setItemMeta(meta);
        }
        return item;
    }

    // -------------------- COMMANDS --------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("bounties")) {
            showLeaderboard(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "&cThis command can only be used by a player.");
            return true;
        }

        switch (cmd) {
            case "shop" -> openMain(player);
            case "menu" -> openMenu(player, 1);
            case "bounty" -> handleBountyCommand(player, args);
            case "ah" -> handleAuctionCommand(player, args);
            default -> msg(player, "&cUnknown command.");
        }
        return true;
    }

    private void handleBountyCommand(Player player, String[] args) {
        if (args.length == 0) {
            openBountyTargets(player);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                msg(player, "&6&lBounty Commands");
                player.sendMessage(color("&e/bounty &7- Open the bounty menu"));
                player.sendMessage(color("&e/bounty <player> <amount> &7- Place a bounty"));
                player.sendMessage(color("&e/bounty view [player] &7- View an active bounty"));
                player.sendMessage(color("&e/bounties &7- Show the top 10 active bounties"));
            }
            case "view" -> {
                if (args.length == 1) {
                    double own = bounties.getOrDefault(player.getUniqueId(), 0.0);
                    msg(player, "&7Your active bounty: &6$" + money(own));
                } else {
                    showPlayerBounty(player, args[1]);
                }
            }
            default -> {
                if (args.length != 2) {
                    msg(player, "&cUsage: /bounty <player> <amount> or /bounty help");
                    return;
                }

                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    msg(player, "&cThat player must be online to receive a bounty.");
                    return;
                }

                double amount;
                try {
                    amount = Double.parseDouble(args[1].replace("$", "").replace(",", ""));
                } catch (NumberFormatException ex) {
                    msg(player, "&cEnter a valid amount.");
                    return;
                }

                placeBounty(player, target.getUniqueId(), amount);
            }
        }
    }

    private void showPlayerBounty(CommandSender sender, String name) {
        UUID targetId = null;
        String knownName = name;

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            targetId = online.getUniqueId();
            knownName = online.getName();
        }

        if (targetId == null) {
            for (Map.Entry<UUID, String> entry : bountyNames.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(name)) {
                    targetId = entry.getKey();
                    knownName = entry.getValue();
                    break;
                }
            }
        }

        if (targetId == null) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            if (cached != null) {
                targetId = cached.getUniqueId();
                knownName = cached.getName() == null ? name : cached.getName();
            }
        }

        if (targetId == null) {
            msg(sender, "&cPlayer not found in the server's known player cache.");
            return;
        }

        double amount = bounties.getOrDefault(targetId, 0.0);
        if (amount <= 0) {
            msg(sender, "&e" + knownName + " &7has no active bounty.");
        } else {
            msg(sender, "&e" + knownName + " &7has an active bounty of &6$" +
                    money(amount) + "&7.");
        }
    }

    // -------------------- MAIN SHOP --------------------

    private void openMain(Player player) {
        String title = formatTitle(getConfig().getString(
                "shop-title", "<green><bold>Server Shop</bold></green>"));
        int size = getConfig().getInt("size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) size = 27;

        Inventory inv = Bukkit.createInventory(null, size, title);
        ConfigurationSection categories = getConfig().getConfigurationSection("categories");

        if (categories != null) {
            for (String key : categories.getKeys(false)) {
                ConfigurationSection cat = categories.getConfigurationSection(key);
                if (cat == null) continue;

                Material material = Material.matchMaterial(cat.getString("material", "CHEST"));
                if (material == null) material = Material.CHEST;

                ItemStack icon = new ItemStack(material);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color(cat.getString("name", key)));
                    meta.setLore(List.of(color("&7Click to browse")));
                    icon.setItemMeta(meta);
                }

                int slot = cat.getInt("slot", 0);
                if (slot >= 0 && slot < inv.getSize() - 1) inv.setItem(slot, icon);
            }
        }

        inv.setItem(inv.getSize() - 1, makeItem(Material.GOLD_INGOT,
                "&6&lSell Items", List.of("&7Sell eligible items from your inventory",
                        "&eYou receive 70% of the buy price")));
        currentCategory.remove(player.getUniqueId());
        sellSlots.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openMenu(Player player, int page) {
        boolean second = page == 2;
        Inventory inv = Bukkit.createInventory(null, 54, second ? menuTitle2 : menuTitle1);

        if (!second) {
            inv.setItem(10, makeItem(Material.CHEST, "&a&lShop",
                    List.of("&7Browse the server shop", "&eClick to open")));
            inv.setItem(12, makeItem(Material.PLAYER_HEAD, "&c&lBounty Board",
                    List.of("&7Place and view bounties", "&eClick to open")));
            inv.setItem(14, makeItem(Material.GOLD_INGOT, "&6&lAuction Market",
                    List.of("&7Buy and sell player items", "&eClick to open")));
            inv.setItem(16, makeItem(Material.ENCHANTED_BOOK, "&d&lKits",
                    List.of("&7Claim server kits", "&eComing soon")));
            inv.setItem(53, makeItem(Material.ARROW, "&eNext Page",
                    List.of("&7View more features")));
        } else {
            inv.setItem(10, makeItem(Material.COMPASS, "&b&lPlayer Warps",
                    List.of("&7Visit player-created warps", "&eComing soon")));
            inv.setItem(12, makeItem(Material.BOOK, "&a&lQuests",
                    List.of("&7View server quests", "&eComing soon")));
            inv.setItem(45, makeItem(Material.ARROW, "&ePrevious Page",
                    List.of("&7Return to page one")));
            inv.setItem(49, makeItem(Material.BARRIER, "&c&lClose Menu",
                    List.of("&7Close this menu")));
        }
        currentCategory.remove(player.getUniqueId());
        sellSlots.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    private void openCategory(Player player, String key) {
        ConfigurationSection cat = getConfig().getConfigurationSection("categories." + key);
        if (cat == null) return;

        Inventory inv = Bukkit.createInventory(null, 54, categoryTitle);
        ConfigurationSection items = cat.getConfigurationSection("items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(id);
                if (item == null) continue;

                Material material = Material.matchMaterial(item.getString("material", ""));
                if (material == null) continue;
                int amount = Math.max(1, Math.min(item.getInt("amount", 1),
                        material.getMaxStackSize()));

                ItemStack stack = new ItemStack(material, amount);
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color(item.getString(
                            "name", "&f" + readableItemName(material))));
                    meta.setLore(List.of(color("&7Amount: " + amount),
                            color("&6Price: $" + money(item.getDouble("price"))),
                            color("&eClick to buy")));
                    stack.setItemMeta(meta);
                }

                int slot = item.getInt("slot", 0);
                if (slot >= 0 && slot < 45) inv.setItem(slot, stack);
            }
        }

        inv.setItem(45, makeItem(Material.ARROW, "&cBack to Shop", List.of("&7Return to categories")));
        currentCategory.put(player.getUniqueId(), key);
        sellSlots.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    private ConfigurationSection findItem(Material material) {
        ConfigurationSection categories = getConfig().getConfigurationSection("categories");
        if (categories == null) return null;

        for (String key : categories.getKeys(false)) {
            ConfigurationSection items = categories.getConfigurationSection(key + ".items");
            if (items == null) continue;
            for (String id : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(id);
                if (item != null && material.name().equalsIgnoreCase(
                        item.getString("material", ""))) return item;
            }
        }
        return null;
    }

    private void openSell(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, sellTitle);
        Map<Integer, Material> slots = new HashMap<>();
        Set<Material> seen = new HashSet<>();
        int slot = 0;

        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir() || seen.contains(stack.getType())) continue;
            Material material = stack.getType();
            ConfigurationSection item = findItem(material);
            if (item == null) continue;
            seen.add(material);

            double unitPrice = item.getDouble("price") / Math.max(1, item.getInt("amount", 1));
            int owned = 0;
            for (ItemStack s : player.getInventory().getStorageContents()) {
                if (s != null && s.getType() == material) owned += s.getAmount();
            }

            inv.setItem(slot, makeItem(material, "&f" + readableItemName(material),
                    List.of("&7You have: " + owned,
                            "&6Sell price each: $" + money(unitPrice * 0.70),
                            "&eClick to sell all")));
            slots.put(slot, material);
            if (++slot >= 45) break;
        }

        inv.setItem(49, makeItem(Material.ARROW, "&cBack to Shop", List.of("&7Return to categories")));
        sellSlots.put(player.getUniqueId(), slots);
        currentCategory.remove(player.getUniqueId());
        player.openInventory(inv);
    }

    // -------------------- BOUNTIES --------------------

    private void openBountyTargets(Player player) {
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        targets.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
        targets.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int size = Math.max(9, Math.min(54, ((targets.size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(null, size, bountyTargetTitle);

        if (targets.isEmpty()) {
            inv.setItem(4, makeItem(Material.BARRIER, "&cNo other players online",
                    List.of("&7Try again when another player is online.")));
        } else {
            for (int i = 0; i < targets.size() && i < size; i++) {
                Player target = targets.get(i);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (meta != null) {
                    meta.setOwningPlayer(target);
                    meta.setDisplayName(color("&e" + target.getName()));
                    meta.setLore(List.of(color("&7Active bounty: &6$" +
                            money(bounties.getOrDefault(target.getUniqueId(), 0.0))),
                            color("&aClick to choose this player")));
                    head.setItemMeta(meta);
                }
                inv.setItem(i, head);
            }
        }
        player.openInventory(inv);
    }

    private void openBountyAmounts(Player player, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            msg(player, "&cThat player is no longer online.");
            openBountyTargets(player);
            return;
        }

        selectedTargets.put(player.getUniqueId(), targetId);
        Inventory inv = Bukkit.createInventory(null, 27, bountyAmountTitle);
        inv.setItem(10, makeItem(Material.GOLD_INGOT, "&e&l$100",
                List.of("&7Place a $100 bounty on " + target.getName(), "&aClick to confirm")));
        inv.setItem(12, makeItem(Material.GOLD_BLOCK, "&6&l$1,000",
                List.of("&7Place a $1,000 bounty on " + target.getName(), "&aClick to confirm")));
        inv.setItem(14, makeItem(Material.EMERALD, "&a&l$10,000",
                List.of("&7Place a $10,000 bounty on " + target.getName(), "&aClick to confirm")));
        inv.setItem(16, makeItem(Material.NAME_TAG, "&b&lCustom Amount",
                List.of("&7Type an amount in chat", "&7Minimum: $1")));
        inv.setItem(22, makeItem(Material.ARROW, "&cChoose another player",
                List.of("&7Return to player selection")));
        player.openInventory(inv);
    }

    private void placeBounty(Player poster, UUID targetId, double amount) {
        Player target = Bukkit.getPlayer(targetId);
        if (targetId.equals(poster.getUniqueId())) {
            msg(poster, "&cYou cannot place a bounty on yourself.");
            return;
        }
        if (target == null) {
            msg(poster, "&cThat player must be online to place a bounty.");
            return;
        }
        if (!Double.isFinite(amount) || amount < 1 || amount > 1_000_000_000_000.0) {
            msg(poster, "&cEnter a valid amount of at least $1.");
            return;
        }
        if (eco.getBalance(poster) < amount) {
            msg(poster, "&cYou don't have enough money.");
            return;
        }
        if (!eco.withdrawPlayer(poster, amount).transactionSuccess()) {
            msg(poster, "&cPayment failed; bounty not placed.");
            return;
        }

        UUID id = target.getUniqueId();
        bounties.merge(id, amount, Double::sum);
        bountyNames.put(id, target.getName());
        bountyPosters.computeIfAbsent(id, ignored -> new HashSet<>()).add(poster.getUniqueId());
        saveBounties();

        msg(poster, "&aPlaced a $" + money(amount) + " bounty on &e" + target.getName() +
                "&a. Total bounty: &6$" + money(bounties.get(id)) + "&a.");
        msg(target, "&cA bounty of $" + money(amount) + " has been placed on you! Total: $" +
                money(bounties.get(id)) + ".");
    }

    private void showLeaderboard(CommandSender sender) {
        List<UUID> ids = new ArrayList<>(bounties.keySet());
        ids.removeIf(id -> bounties.getOrDefault(id, 0.0) <= 0);
        ids.sort((a, b) -> Double.compare(bounties.getOrDefault(b, 0.0),
                bounties.getOrDefault(a, 0.0)));

        sender.sendMessage(color("&8&m-----------------------------"));
        msg(sender, "&6&lTop 10 Active Bounties");
        if (ids.isEmpty()) {
            sender.sendMessage(color("&7There are no active bounties."));
        } else {
            for (int i = 0; i < Math.min(10, ids.size()); i++) {
                UUID id = ids.get(i);
                sender.sendMessage(color("&e#" + (i + 1) + " &f" +
                        bountyNames.getOrDefault(id, "Unknown") + " &7- &6$" +
                        money(bounties.getOrDefault(id, 0.0))));
            }
        }
        sender.sendMessage(color("&8&m-----------------------------"));
    }

    // -------------------- AUCTION HOUSE COMMANDS --------------------

    private void handleAuctionCommand(Player player, String[] args) {
        if (args.length == 0) {
            openAuctionHouse(player, 0);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                msg(player, "&6&lAuction House Commands");
                player.sendMessage(color("&e/ah &7- Browse listings"));
                player.sendMessage(color("&e/ah sell <price> &7- List the item in your main hand"));
                player.sendMessage(color("&e/ah mine &7- View your active listings"));
                player.sendMessage(color("&e/ah cancel <id> &7- Cancel your listing"));
                player.sendMessage(color("&e/ah collect &7- Collect sale money and returned items"));
                player.sendMessage(color("&7Listings stay active until sold or cancelled."));
                player.sendMessage(color("&7Fee: 5% when sold. Maximum 10 active listings."));
            }
            case "sell" -> {
                if (args.length != 2) {
                    msg(player, "&cUsage: /ah sell <price>");
                    return;
                }
                double price;
                try {
                    price = Double.parseDouble(args[1].replace("$", "").replace(",", ""));
                } catch (NumberFormatException ex) {
                    msg(player, "&cEnter a valid price.");
                    return;
                }
                createListing(player, price);
            }
            case "cancel" -> {
                if (args.length != 2) {
                    msg(player, "&cUsage: /ah cancel <listing-id>");
                    msg(player, "&7Use /ah mine to see your listing IDs.");
                    return;
                }
                cancelListing(player, args[1]);
            }
            case "mine" -> openMyListings(player);
            case "collect" -> collectAuctionItems(player);
            default -> msg(player, "&cUnknown auction command. Use /ah help.");
        }
    }

    private void createListing(Player seller, double price) {
        if (!Double.isFinite(price) || price < 1 || price > 1_000_000_000_000.0) {
            msg(seller, "&cPrice must be at least $1 and within the allowed limit.");
            return;
        }

        long count = listings.values().stream()
                .filter(l -> l.sellerId.equals(seller.getUniqueId())).count();
        if (count >= MAX_LISTINGS) {
            msg(seller, "&cYou already have 10 active listings.");
            return;
        }

        ItemStack hand = seller.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg(seller, "&cHold the item you want to list in your main hand.");
            return;
        }
        if (hand.getType() == Material.AIR) return;

        ItemStack item = hand.clone();
        UUID id = UUID.randomUUID();
        listings.put(id, new AuctionListing(id, seller.getUniqueId(),
                seller.getName(), price, item));
        seller.getInventory().setItemInMainHand(null);
        saveAuctions();

        msg(seller, "&aListed &f" + readableItemName(item.getType()) +
                " x" + item.getAmount() + " &afor &6$" + money(price) +
                "&a. Listing ID: &e" + shortId(id));
        msg(seller, "&7The 5% fee is deducted from the sale proceeds, not upfront.");
    }

    private void cancelListing(Player player, String suppliedId) {
        AuctionListing found = findListing(suppliedId);
        if (found == null) {
            msg(player, "&cListing not found.");
            return;
        }
        if (!found.sellerId.equals(player.getUniqueId())) {
            msg(player, "&cYou can only cancel your own listings.");
            return;
        }

        addCollectionItem(found.sellerId, found.item);
        listings.remove(found.id);
        saveAuctions();
        saveCollections();

        msg(player, "&aListing cancelled. Your item is available through &e/ah collect&a.");
    }

    private AuctionListing findListing(String suppliedId) {
        for (AuctionListing listing : listings.values()) {
            if (listing.id.toString().equalsIgnoreCase(suppliedId)
                    || shortId(listing.id).equalsIgnoreCase(suppliedId)) return listing;
        }
        return null;
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private void openAuctionHouse(Player player, int requestedPage) {
        List<AuctionListing> all = new ArrayList<>(listings.values());
        all.sort(Comparator.comparing(l -> l.id.toString()));

        int pages = Math.max(1, (all.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        auctionPages.put(player.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 54, auctionTitle);
        List<UUID> shown = new ArrayList<>();

        int start = page * 45;
        int end = Math.min(all.size(), start + 45);
        for (int i = start; i < end; i++) {
            AuctionListing listing = all.get(i);
            ItemStack display = listing.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(color("&8&m----------------"));
                lore.add(color("&6Price: &e$" + money(listing.price)));
                lore.add(color("&7Seller: &f" + listing.sellerName));
                lore.add(color("&7Listing ID: &f" + shortId(listing.id)));
                lore.add(color("&aClick to buy"));
                display.setItemMeta(meta);
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i - start, display);
            shown.add(listing.id);
        }
        auctionPageListings.put(player.getUniqueId(), shown);

        inv.setItem(45, makeItem(Material.ARROW, "&ePrevious Page",
                List.of("&7Go to the previous page")));
        inv.setItem(49, makeItem(Material.CHEST, "&a&lCollect Items / Money",
                List.of("&7Claim sale proceeds and returned items", "&eClick to collect")));
        inv.setItem(50, makeItem(Material.PAPER, "&b&lMy Listings",
                List.of("&7View your active listings", "&eClick to open")));
        inv.setItem(53, makeItem(Material.ARROW, "&eNext Page",
                List.of("&7Go to the next page", "&7Page " + (page + 1) + "/" + pages)));
        player.openInventory(inv);
    }

    private void openMyListings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, myListingsTitle);
        List<AuctionListing> own = new ArrayList<>();
        for (AuctionListing listing : listings.values()) {
            if (listing.sellerId.equals(player.getUniqueId())) own.add(listing);
        }

        for (int i = 0; i < own.size() && i < 45; i++) {
            AuctionListing listing = own.get(i);
            ItemStack display = listing.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(color("&6Price: &e$" + money(listing.price)));
                lore.add(color("&7Listing ID: &f" + shortId(listing.id)));
                lore.add(color("&cCancel with /ah cancel " + shortId(listing.id)));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i, display);
        }

        inv.setItem(49, makeItem(Material.ARROW, "&cBack to Auction House",
                List.of("&7Return to all listings")));
        player.openInventory(inv);
    }

    private void buyListing(Player buyer, UUID listingId) {
        AuctionListing listing = listings.get(listingId);
        if (listing == null) {
            msg(buyer, "&cThis listing is no longer available.");
            openAuctionHouse(buyer, auctionPages.getOrDefault(buyer.getUniqueId(), 0));
            return;
        }
        if (listing.sellerId.equals(buyer.getUniqueId())) {
            msg(buyer, "&cYou cannot buy your own listing.");
            return;
        }
        if (!canFit(buyer, listing.item)) {
            msg(buyer, "&cMake room in your inventory before buying this item.");
            return;
        }
        if (eco.getBalance(buyer) < listing.price) {
            msg(buyer, "&cYou don't have enough money. Price: $" + money(listing.price));
            return;
        }
        if (!eco.withdrawPlayer(buyer, listing.price).transactionSuccess()) {
            msg(buyer, "&cPayment failed. The listing was not sold.");
            return;
        }

        Map<Integer, ItemStack> leftovers =
                buyer.getInventory().addItem(listing.item.clone());
        if (!leftovers.isEmpty()) {
            // Defensive rollback if an inventory plugin interferes after the capacity check.
            eco.depositPlayer(buyer, listing.price);
            msg(buyer, "&cThe item could not be delivered. Your payment was refunded.");
            return;
        }

        listings.remove(listing.id);
        double sellerAmount = listing.price * SELLER_SHARE;
        collectionMoney.merge(listing.sellerId, sellerAmount, Double::sum);

        saveAuctions();
        saveCollections();

        msg(buyer, "&aPurchased &f" + readableItemName(listing.item.getType()) +
                " &afor &6$" + money(listing.price) + "&a.");
        msg(buyer, "&7Listing ID: &f" + shortId(listing.id));

        Player seller = Bukkit.getPlayer(listing.sellerId);
        if (seller != null) {
            msg(seller, "&aYour listing sold! &6$" + money(sellerAmount) +
                    " &7(95% after the 5% fee) is ready to collect with &e/ah collect&7.");
        }

        openAuctionHouse(buyer, auctionPages.getOrDefault(buyer.getUniqueId(), 0));
    }

    private boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        int max = item.getMaxStackSize();

        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= max;
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    private void addCollectionItem(UUID playerId, ItemStack item) {
        collectionItems.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                .add(item.clone());
    }

    private void collectAuctionItems(Player player) {
        UUID id = player.getUniqueId();
        List<ItemStack> items = collectionItems.getOrDefault(id, new ArrayList<>());
        double amount = collectionMoney.getOrDefault(id, 0.0);

        if (items.isEmpty() && amount <= 0) {
            msg(player, "&7You have no auction money or returned items to collect.");
            return;
        }

        for (ItemStack item : items) {
            if (!canFit(player, item)) {
                msg(player, "&cYour inventory needs enough space for all returned items first.");
                msg(player, "&7Your money and items have not been changed.");
                return;
            }
        }

        if (amount > 0) {
            if (!eco.depositPlayer(player, amount).transactionSuccess()) {
                msg(player, "&cCould not deposit your auction proceeds. Try again later.");
                return;
            }
            collectionMoney.remove(id);
        }

        for (ItemStack item : items) player.getInventory().addItem(item.clone());
        collectionItems.remove(id);
        saveCollections();

        if (amount > 0) msg(player, "&aCollected auction proceeds: &6$" + money(amount));
        if (!items.isEmpty()) msg(player, "&aCollected &e" + items.size() + " &areturned item stack(s).");
    }

    // -------------------- INVENTORY EVENTS --------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String mainTitle = formatTitle(getConfig().getString(
                "shop-title", "<green><bold>Server Shop</bold></green>"));

        boolean bountyMenu = title.equals(bountyTargetTitle) || title.equals(bountyAmountTitle);
        boolean serverMenu = title.equals(menuTitle1) || title.equals(menuTitle2);
        boolean auctionMenu = title.equals(auctionTitle) || title.equals(myListingsTitle);

        if (!title.equals(mainTitle) && !title.equals(categoryTitle) && !title.equals(sellTitle)
                && !bountyMenu && !serverMenu && !auctionMenu) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        if (title.equals(auctionTitle)) {
            if (slot == 45) {
                openAuctionHouse(player, auctionPages.getOrDefault(player.getUniqueId(), 0) - 1);
                return;
            }
            if (slot == 49) {
                collectAuctionItems(player);
                return;
            }
            if (slot == 50) {
                openMyListings(player);
                return;
            }
            if (slot == 53) {
                openAuctionHouse(player, auctionPages.getOrDefault(player.getUniqueId(), 0) + 1);
                return;
            }

            List<UUID> shown = auctionPageListings.getOrDefault(player.getUniqueId(), Collections.emptyList());
            if (slot >= 0 && slot < 45 && slot < shown.size()) {
                buyListing(player, shown.get(slot));
            }
            return;
        }

        if (title.equals(myListingsTitle)) {
            if (slot == 49) openAuctionHouse(player, 0);
            return;
        }

        if (serverMenu) {
            if (title.equals(menuTitle1)) {
                if (slot == 10) openMain(player);
                else if (slot == 12) openBountyTargets(player);
                else if (slot == 14) openAuctionHouse(player, 0);
                else if (slot == 16) msg(player, "&dKits are coming soon.");
                else if (slot == 53) openMenu(player, 2);
            } else {
                if (slot == 10) msg(player, "&bPlayer Warps are coming soon.");
                else if (slot == 12) msg(player, "&aQuests are coming soon.");
                else if (slot == 45) openMenu(player, 1);
                else if (slot == 49) player.closeInventory();
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
            if (target == null || target.getUniqueId().equals(player.getUniqueId())) {
                msg(player, "&cThat player is no longer available.");
                openBountyTargets(player);
                return;
            }
            openBountyAmounts(player, target.getUniqueId());
            return;
        }

        if (title.equals(bountyAmountTitle)) {
            UUID targetId = selectedTargets.get(player.getUniqueId());
            if (targetId == null) {
                msg(player, "&cPlease choose a target first.");
                openBountyTargets(player);
                return;
            }
            if (slot == 22) openBountyTargets(player);
            else if (slot == 10) { placeBounty(player, targetId, 100); player.closeInventory(); }
            else if (slot == 12) { placeBounty(player, targetId, 1000); player.closeInventory(); }
            else if (slot == 14) { placeBounty(player, targetId, 10000); player.closeInventory(); }
            else if (slot == 16) {
                awaitingCustomAmount.add(player.getUniqueId());
                player.closeInventory();
                msg(player, "&bType your custom bounty amount in chat (numbers only).");
                msg(player, "&7Type &ccancel &7to stop.");
            }
            return;
        }

        if (title.equals(mainTitle)) {
            if (slot == event.getView().getTopInventory().getSize() - 1) {
                openSell(player);
                return;
            }
            ConfigurationSection categories = getConfig().getConfigurationSection("categories");
            if (categories == null) return;
            for (String key : categories.getKeys(false)) {
                ConfigurationSection cat = categories.getConfigurationSection(key);
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
            ConfigurationSection item = getItemAtSlot(key, slot);
            if (item == null) return;

            Material material = Material.matchMaterial(item.getString("material", ""));
            if (material == null) return;
            int amount = Math.max(1, item.getInt("amount", 1));
            double price = item.getDouble("price");

            if (!Double.isFinite(price) || price < 0) {
                msg(player, "&cThis item has an invalid price.");
                return;
            }
            if (eco.getBalance(player) < price) {
                msg(player, "&cNot enough money!");
                return;
            }

            ItemStack purchase = new ItemStack(material, amount);
            if (!canFit(player, purchase)) {
                msg(player, "&cYour inventory is full!");
                return;
            }
            if (!eco.withdrawPlayer(player, price).transactionSuccess()) {
                msg(player, "&cPayment failed. You were not charged.");
                return;
            }
            player.getInventory().addItem(purchase);
            msg(player, "&aBought " + amount + " " + itemName(item, material) +
                    " for $" + money(price));
            return;
        }

        if (title.equals(sellTitle)) {
            Material material = sellSlots.getOrDefault(player.getUniqueId(),
                    Collections.emptyMap()).get(slot);
            if (material == null) return;
            ConfigurationSection item = findItem(material);
            if (item == null) return;

            int amount = 0;
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == material) amount += stack.getAmount();
            }
            if (amount <= 0) {
                msg(player, "&cYou don't have that item.");
                openSell(player);
                return;
            }

            double unitPrice = item.getDouble("price") / Math.max(1, item.getInt("amount", 1));
            double total = unitPrice * 0.70 * amount;

            for (int i = 0; i < player.getInventory().getStorageContents().length; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack != null && stack.getType() == material) player.getInventory().setItem(i, null);
            }
            if (!eco.depositPlayer(player, total).transactionSuccess()) {
                msg(player, "&cPayment failed. Contact an administrator.");
                openSell(player);
                return;
            }
            msg(player, "&aSold " + amount + " " + readableItemName(material) +
                    " for $" + money(total));
            openSell(player);
        }
    }

    private ConfigurationSection getItemAtSlot(String category, int slot) {
        ConfigurationSection items = getConfig().getConfigurationSection("categories." + category + ".items");
        if (items == null) return null;
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item != null && item.getInt("slot", 0) == slot) return item;
        }
        return null;
    }

    private String itemName(ConfigurationSection item, Material material) {
        String name = item.getString("name", "");
        if (name == null || name.isBlank()) return readableItemName(material);
        String stripped = ChatColor.stripColor(color(name));
        return stripped == null || stripped.isBlank() ? readableItemName(material) : stripped;
    }

    // -------------------- CUSTOM BOUNTY CHAT --------------------

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
                msg(player, "&7Custom bounty cancelled.");
                return;
            }

            double amount;
            try {
                amount = Double.parseDouble(message.replace("$", "").replace(",", ""));
            } catch (NumberFormatException ex) {
                msg(player, "&cInvalid amount. Enter a number, or type cancel.");
                return;
            }

            UUID targetId = selectedTargets.get(playerId);
            awaitingCustomAmount.remove(playerId);
            if (targetId == null) {
                msg(player, "&cNo target selected. Use /bounty to try again.");
                return;
            }
            placeBounty(player, targetId, amount);
        });
    }

    // -------------------- BOUNTY CLAIMS --------------------

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID targetId = victim.getUniqueId();
        double amount = bounties.getOrDefault(targetId, 0.0);
        Player killer = victim.getKiller();
        if (amount <= 0 || killer == null) return;

        if (bountyPosters.getOrDefault(targetId, Collections.emptySet())
                .contains(killer.getUniqueId())) {
            msg(killer, "&cYou cannot claim a bounty you contributed to.");
            return;
        }

        if (eco.depositPlayer(killer, amount).transactionSuccess()) {
            bounties.remove(targetId);
            bountyNames.remove(targetId);
            bountyPosters.remove(targetId);
            saveBounties();
            msg(killer, "&aBounty claimed! You received &6$" + money(amount) +
                    "&a for killing &e" + victim.getName() + "&a.");
            broadcast("&6&lBOUNTY CLAIMED! &e" + killer.getName() + " &7claimed &6$" +
                    money(amount) + " &7for killing &c" + victim.getName() + "&7.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        awaitingCustomAmount.remove(id);
        selectedTargets.remove(id);
        currentCategory.remove(id);
        sellSlots.remove(id);
        auctionPageListings.remove(id);
        auctionPages.remove(id);
    }

    // -------------------- BOUNTY STORAGE --------------------

    private void loadBounties() {
        bountyData = YamlConfiguration.loadConfiguration(bountyFile);
        ConfigurationSection section = bountyData.getConfigurationSection("active");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID targetId = UUID.fromString(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) continue;
                double amount = entry.getDouble("amount", 0.0);
                if (amount <= 0 || !Double.isFinite(amount)) continue;

                bounties.put(targetId, amount);
                bountyNames.put(targetId, entry.getString("name", "Unknown"));
                Set<UUID> posters = new HashSet<>();
                for (String value : entry.getStringList("posters")) {
                    try { posters.add(UUID.fromString(value)); }
                    catch (IllegalArgumentException ignored) {}
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
            UUID id = entry.getKey();
            String path = "active." + id;
            bountyData.set(path + ".amount", entry.getValue());
            bountyData.set(path + ".name", bountyNames.getOrDefault(id, "Unknown"));
            List<String> posters = new ArrayList<>();
            for (UUID poster : bountyPosters.getOrDefault(id, Collections.emptySet())) {
                posters.add(poster.toString());
            }
            bountyData.set(path + ".posters", posters);
        }
        saveYaml(bountyData, bountyFile, "bounties");
    }

    // -------------------- AUCTION STORAGE --------------------

    private void loadAuctions() {
        auctionData = YamlConfiguration.loadConfiguration(auctionFile);
        ConfigurationSection section = auctionData.getConfigurationSection("listings");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) continue;

                UUID sellerId = UUID.fromString(entry.getString("seller-uuid", ""));
                String sellerName = entry.getString("seller-name", "Unknown");
                double price = entry.getDouble("price", 0);
                Object stored = entry.get("item");

                if (!(stored instanceof ItemStack item) || item.getType().isAir()
                        || !Double.isFinite(price) || price < 1) continue;

                listings.put(id, new AuctionListing(id, sellerId, sellerName, price, item));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Skipping invalid auction listing: " + key);
            }
        }
    }

    private void saveAuctions() {
        if (auctionFile == null) return;
        if (auctionData == null) auctionData = new YamlConfiguration();
        auctionData.set("listings", null);

        for (AuctionListing listing : listings.values()) {
            String path = "listings." + listing.id;
            auctionData.set(path + ".seller-uuid", listing.sellerId.toString());
            auctionData.set(path + ".seller-name", listing.sellerName);
            auctionData.set(path + ".price", listing.price);
            auctionData.set(path + ".item", listing.item);
        }
        saveYaml(auctionData, auctionFile, "auctions");
    }

    // -------------------- AUCTION COLLECTION STORAGE --------------------

    private void loadCollections() {
        collectionData = YamlConfiguration.loadConfiguration(collectionFile);
        ConfigurationSection section = collectionData.getConfigurationSection("collections");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) continue;

                double amount = entry.getDouble("money", 0);
                if (amount > 0 && Double.isFinite(amount)) collectionMoney.put(playerId, amount);

                List<ItemStack> items = new ArrayList<>();
                for (Object obj : entry.getList("items", Collections.emptyList())) {
                    if (obj instanceof ItemStack item && !item.getType().isAir()) {
                        items.add(item.clone());
                    }
                }
                if (!items.isEmpty()) collectionItems.put(playerId, items);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Skipping invalid auction collection UUID: " + key);
            }
        }
    }

    private void saveCollections() {
        if (collectionFile == null) return;
        if (collectionData == null) collectionData = new YamlConfiguration();
        collectionData.set("collections", null);

        Set<UUID> ids = new HashSet<>();
        ids.addAll(collectionMoney.keySet());
        ids.addAll(collectionItems.keySet());

        for (UUID id : ids) {
            String path = "collections." + id;
            collectionData.set(path + ".money", collectionMoney.getOrDefault(id, 0.0));
            collectionData.set(path + ".items", collectionItems.getOrDefault(id, Collections.emptyList()));
        }
        saveYaml(collectionData, collectionFile, "auction collections");
    }

    private void saveYaml(YamlConfiguration yaml, File file, String label) {
        try {
            yaml.save(file);
        } catch (IOException ex) {
            getLogger().severe("Could not save " + label + " file: " + ex.getMessage());
        }
    }
}
