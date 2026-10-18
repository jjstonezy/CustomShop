
package com.customshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class CustomShopPlugin extends JavaPlugin
        implements Listener, CommandExecutor {

    private Economy eco;
    private final Map<UUID, String> currentCategory = new HashMap<>();
    private final Map<UUID, Map<Integer, Material>> sellSlots = new HashMap<>();

    private final String categoryTitle = color("&8Shop Category");
    private final String sellTitle = color("&c&lSell Items");

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> provider =
                getServer().getServicesManager()
                        .getRegistration(Economy.class);

        if (provider == null || provider.getProvider() == null) {
            getLogger().severe("Vault economy missing!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        eco = provider.getProvider();
        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CustomShop enabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        openMain(player);
        return true;
    }

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

                inv.setItem(cat.getInt("slot", 0), icon);
            }
        }

        ItemStack sell = new ItemStack(Material.GOLD_INGOT);
        ItemMeta sellMeta = sell.getItemMeta();
        sellMeta.setDisplayName(color("&c&lSell Items"));
        sellMeta.setLore(List.of(color("&7Sell items from your inventory"),
                color("&eYou receive 70% of the buy price")));
        sell.setItemMeta(sellMeta);
        inv.setItem(inv.getSize() - 1, sell);

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

                inv.setItem(item.getInt("slot", 0), stack);
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
                if (item == null) continue;

                if (material.name().equalsIgnoreCase(
                        item.getString("material", ""))) {
                    return item;
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String mainTitle = color(getConfig().getString(
                "shop-title", "&a&lServer Shop"));

        if (!title.equals(mainTitle)
                && !title.equals(categoryTitle)
                && !title.equals(sellTitle)) return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (slot < 0 || slot >= event.getView().getTopInventory().getSize())
            return;

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
                ConfigurationSection item =
                        items.getConfigurationSection(id);
                if (item == null || item.getInt("slot", 0) != slot)
                    continue;

                Material material = Material.matchMaterial(
                        item.getString("material", ""));
                if (material == null) return;

                int amount = Math.max(1, item.getInt("amount", 1));
                double price = item.getDouble("price");

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

                player.getInventory().addItem(new ItemStack(material, amount));
                player.sendMessage(color("&aBought " + amount + " "
                        + material.name() + " for $" + price));
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
                    + material.name() + " for $" +
                    String.format(Locale.US, "%.2f", total)));
            openSell(player);
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&',
                text == null ? "" : text);
    }
}
