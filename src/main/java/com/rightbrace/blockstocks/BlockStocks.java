package com.rightbrace.blockstocks;

import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class BlockStocks extends JavaPlugin implements Listener {
    
    private static BlockStocks instance;
    private static Economy economy;
    public static Inventory marketGui;
    
    
    public BlockStocks() {

    }
    
    public static BlockStocks getMain() {
        if (instance == null) {
            instance = new BlockStocks();
        }
        return instance;
    }
    
    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        
        economy = rsp.getProvider();
        return economy != null;
    }
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        if (!setupEconomy()) {
            getLogger().severe("Need Vault + an Economy provider. Check you have both!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        
        int slots = getConfig().getInt("inventory-slots", 9);
        marketGui = Bukkit.createInventory(null, slots, "BlockStocks Market");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("market").setExecutor(new MarketCommand());
        constructGui();
    }

    public double stockValue(double initialValue, double volatility, int netPurchases) {
        if (netPurchases == 0) {
            return initialValue;
        } else if (netPurchases > 0) {
            return initialValue*(Math.log(volatility * (double) netPurchases + 1) + 1);
        } else {
            return initialValue * Math.exp(volatility * (double) netPurchases);
        }
    }
    
    public double stockValue(int slot) {
        ConfigurationSection item = getConfig().getConfigurationSection("items." + slot);
        double initialValue = item.getDouble("initial-value", 1);
        double volatility = item.getDouble("volatility", 1);
        int netPurchases = item.getInt("net-purchases", 0);
        
        return stockValue(initialValue, volatility, netPurchases);
    }
    
    public double stockValue(int slot, int netPurchaseDelta) {
        ConfigurationSection item = getConfig().getConfigurationSection("items." + slot);
        if (item == null) {
            throw new IndexOutOfBoundsException();
        }
        double initialValue = item.getDouble("initial-value", 1);
        double volatility = item.getDouble("volatility", 1);
        int netPurchases = item.getInt("net-purchases", 0) + netPurchaseDelta;
        
        return stockValue(initialValue, volatility, netPurchases);
    }
    
    public double sellValue(int slot) {
        return stockValue(slot) * (1 - tradeFee());
    }
    
    public double buyValue(int slot) {
        return stockValue(slot, 1) * (1 + tradeFee());
    }
    
    public double tradeFee() {
        return getConfig().getDouble("trade-fee", 0);
    }
    
    public void attemptPurchase(Player player, int slot) {
        
        double cost = buyValue(slot);
        
        ConfigurationSection item = getConfig().getConfigurationSection("items." + slot);
        if (item == null) {
            return;
        }
        String type = item.getString("type", "BARRIER");
        Material material = Material.getMaterial(type);
        
        // Check if the player can afford it
        if (economy.getBalance(player) < cost) {
            player.sendMessage("You can't afford that!");
            return;
        }
            
        // Loop through the inventory, looking for a stack of the right kind
        Inventory playerInventory = player.getInventory();
        for (int i = 0; i < playerInventory.getSize(); i++) {
            
            ItemStack invItem = playerInventory.getItem(i);
            if (invItem != null && invItem.getType().equals(material) && invItem.getAmount() < invItem.getMaxStackSize()) {
                // They have an applicable stack!
                invItem.setAmount(invItem.getAmount() + 1);
                playerInventory.setItem(i, invItem);
                player.updateInventory();
                
                // Gimme the money
                economy.withdrawPlayer(player, cost);
                
                // Increase the net purchases
                int currentNetPurchases = getConfig().getInt("items." + slot + ".net-purchases");
                getConfig().set("items." + slot + ".net-purchases", currentNetPurchases + 1);
                
                player.sendMessage("Purchased!");
                return;
            }
        }
        
        player.sendMessage("You don't have space for that!");
        
    }
    
    public void attemptSale(Player player, int slot) {
        
        double earnings = sellValue(slot);
        ConfigurationSection item = getConfig().getConfigurationSection("items." + slot);
        if (item == null) {
            return;
        }
        String type = item.getString("type", "BARRIER");
        Material material = Material.getMaterial(type);
            
        // Loop through the inventory, looking for a stack of the right kind
        Inventory playerInventory = player.getInventory();
        for (int i = 0; i < playerInventory.getSize(); i++) {
            
            ItemStack invItem = playerInventory.getItem(i);
            if (invItem != null && invItem.getType().equals(material)) {
                // They have an applicable stack!
                invItem.setAmount(invItem.getAmount() - 1);
                playerInventory.setItem(i, invItem.getAmount() > 0 ? invItem : null);
                player.updateInventory();
                
                // Give the player their money
                economy.depositPlayer(player, earnings);
                
                // Decrease the net purchases
                int currentNetPurchases = getConfig().getInt("items." + slot + ".net-purchases");
                getConfig().set("items." + slot + ".net-purchases", currentNetPurchases - 1);
                
                player.sendMessage("Sold!");
                return;
            }
        }
        
        player.sendMessage("You don't have that!");
    }
 
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        
        Inventory inv = event.getInventory();        
        if (!inv.equals(marketGui)) {
            return;
        }
        
        event.setCancelled(true);
        
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        
        if (event.getClick() == ClickType.LEFT) {
            attemptPurchase(player, slot);
        } else if (event.getClick() == ClickType.RIGHT) {
            attemptSale(player, slot);
        }
        
        saveConfig();
        constructGui();
        
    }
    
    @EventHandler
    public void onInventoryClick(InventoryDragEvent event) {
        if (event.getInventory().equals(marketGui)) {
            event.setCancelled(true);
        }
    }
    
    public ItemStack makeItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }
    
    public void constructGui() {
        marketGui.clear();
        
        ConfigurationSection items =  getConfig().getConfigurationSection("items");
        for (String itemSlot : items.getKeys(false)) {
            
            int slotInt = Integer.parseInt(itemSlot);
            
            ConfigurationSection item = getConfig().getConfigurationSection("items." + itemSlot);
            String type = item.getString("type", "BARRIER");
            Material material = Material.getMaterial(type);
            
            String[] lore = {
                String.format("Left Click to buy at %.2f", buyValue(slotInt)),
                String.format("Right Click to sell at %.2f", sellValue(slotInt))
            };
            
            ItemStack entry = makeItem(material, material.name(), lore);
            
            marketGui.setItem(slotInt, entry);
            
        }
    }
}
