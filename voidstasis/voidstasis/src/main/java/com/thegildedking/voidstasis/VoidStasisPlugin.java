package com.thegildedking.voidstasis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class VoidStasisPlugin extends JavaPlugin implements TabExecutor {

	private NamespacedKey rodKey;
	private TagManager tags;
	private HookTracker tracker;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		rodKey = new NamespacedKey(this, "void_stasis_rod");

		tags = new TagManager(this);
		tags.load();

		tracker = new HookTracker(this, tags);
		getServer().getPluginManager().registerEvents(tracker, this);
		tracker.start();

		if (getCommand("voidstasis") != null) {
			getCommand("voidstasis").setExecutor(this);
			getCommand("voidstasis").setTabCompleter(this);
		}
		getLogger().info("Void Stasis enabled (pearl hooking + tagging).");
	}

	@Override
	public void onDisable() {
		if (tags != null) {
			tags.save();
		}
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

	public boolean holdsRod(Player player) {
		return isVoidStasisRod(player.getInventory().getItemInMainHand())
				|| isVoidStasisRod(player.getInventory().getItemInOffHand());
	}

	private static String nameOf(UUID uuid) {
		String name = Bukkit.getOfflinePlayer(uuid).getName();
		return name != null ? name : uuid.toString().substring(0, 8);
	}

	private static Component gray(String text) {
		return Component.text(text, NamedTextColor.GRAY);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("voidstasis.admin")) {
			sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(gray("Usage: /voidstasis <give [player] | list | status | clear <player> | reload>"));
			return true;
		}

		switch (args[0].toLowerCase()) {
			case "give" -> giveRod(sender, args);
			case "list" -> listTags(sender);
			case "status" -> status(sender);
			case "clear" -> clearTags(sender, args);
			case "reload" -> {
				reloadConfig();
				tracker.reloadSettings();
				sender.sendMessage(Component.text("Void Stasis config reloaded.", NamedTextColor.DARK_PURPLE));
			}
			default -> sender.sendMessage(gray("Unknown subcommand."));
		}
		return true;
	}

	private void giveRod(CommandSender sender, String[] args) {
		Player target;
		if (args.length > 1) {
			target = Bukkit.getPlayerExact(args[1]);
			if (target == null) {
				sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
				return;
			}
		} else if (sender instanceof Player self) {
			target = self;
		} else {
			sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
			return;
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
	}

	private void listTags(CommandSender sender) {
		Map<UUID, Set<UUID>> all = tags.snapshot();
		if (all.isEmpty()) {
			sender.sendMessage(gray("No active tags."));
			return;
		}
		sender.sendMessage(Component.text("Active tags (target <- tagged by):", NamedTextColor.DARK_PURPLE));
		for (Map.Entry<UUID, Set<UUID>> entry : all.entrySet()) {
			String taggers = entry.getValue().stream().map(VoidStasisPlugin::nameOf).collect(Collectors.joining(", "));
			sender.sendMessage(gray(nameOf(entry.getKey()) + " <- " + taggers));
		}
	}

	private void status(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage(gray("Use /voidstasis list from the console."));
			return;
		}
		Set<UUID> targets = tags.targetsOf(player.getUniqueId());
		if (targets.isEmpty()) {
			player.sendMessage(gray("You have no tagged players."));
			return;
		}
		String names = targets.stream().map(VoidStasisPlugin::nameOf).collect(Collectors.joining(", "));
		player.sendMessage(Component.text("You have tagged: ", NamedTextColor.DARK_PURPLE).append(gray(names)));
	}

	private void clearTags(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(gray("Usage: /voidstasis clear <player>"));
			return;
		}
		OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
		if (target == null) {
			target = Bukkit.getOfflinePlayerIfCached(args[1]);
		}
		if (target == null) {
			sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
			return;
		}
		int removed = tags.clearPlayer(target.getUniqueId());
		sender.sendMessage(Component.text("Cleared " + removed + " tag(s) involving " + args[1] + ".",
				NamedTextColor.DARK_PURPLE));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission("voidstasis.admin")) {
			return List.of();
		}
		if (args.length == 1) {
			return List.of("give", "list", "status", "clear", "reload");
		}
		if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("clear"))) {
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		}
		return List.of();
	}
}
