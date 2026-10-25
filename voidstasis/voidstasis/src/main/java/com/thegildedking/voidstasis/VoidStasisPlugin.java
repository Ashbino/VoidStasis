package com.thegildedking.voidstasis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;

public final class VoidStasisPlugin extends JavaPlugin implements TabExecutor {

	private NamespacedKey rodKey;

	@Override
	public void onEnable() {
		rodKey = new NamespacedKey(this, "void_stasis_rod");
		if (getCommand("voidstasis") != null) {
			getCommand("voidstasis").setExecutor(this);
			getCommand("voidstasis").setTabCompleter(this);
		}
		getLogger().info("Void Stasis enabled (Stage 1.5: admin-only item).");
	}

	@Override
	public void onDisable() {
		getLogger().info("Void Stasis disabled.");
	}

	/** Builds the Void Stasis item. There is no recipe: it can only be created by this method. */
	public ItemStack createRod() {
		ItemStack rod = new ItemStack(Material.FISHING_ROD);
		ItemMeta meta = rod.getItemMeta();
		meta.displayName(Component.text("Void Stasis", NamedTextColor.DARK_PURPLE)
				.decoration(TextDecoration.ITALIC, false));
		meta.lore(List.of(
				Component.text("Hook a pearl to tag its owner.", NamedTextColor.GRAY)
						.decoration(TextDecoration.ITALIC, false),
				Component.text("Reel in a tagged player to send them into the void.", NamedTextColor.GRAY)
						.decoration(TextDecoration.ITALIC, false)));
		meta.setUnbreakable(true);
		meta.setEnchantmentGlintOverride(true);
		meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
		rod.setItemMeta(meta);
		return rod;
	}

	/** True only for rods created by createRod(). */
	public boolean isVoidStasisRod(ItemStack item) {
		if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
			return false;
		}
		return item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("voidstasis.admin")) {
			sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
			return true;
		}
		if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
			sender.sendMessage(Component.text("Usage: /voidstasis give [player]", NamedTextColor.GRAY));
			return true;
		}

		Player target;
		if (args.length > 1) {
			target = Bukkit.getPlayerExact(args[1]);
			if (target == null) {
				sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
				return true;
			}
		} else if (sender instanceof Player self) {
			target = self;
		} else {
			sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
			return true;
		}

		HashMap<Integer, ItemStack> leftover = target.getInventory().addItem(createRod());
		for (ItemStack extra : leftover.values()) {
			target.getWorld().dropItem(target.getLocation(), extra);
		}
		target.sendMessage(Component.text("You received the Void Stasis rod.", NamedTextColor.DARK_PURPLE));
		if (target != sender) {
			sender.sendMessage(Component.text("Gave the Void Stasis rod to " + target.getName() + ".",
					NamedTextColor.DARK_PURPLE));
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission("voidstasis.admin")) {
			return List.of();
		}
		if (args.length == 1) {
			return List.of("give");
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		}
		return List.of();
	}
}
