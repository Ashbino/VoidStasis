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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class VoidStasisPlugin extends JavaPlugin implements TabExecutor {

	private NamespacedKey rodKey;
	private TagManager tags;
	private HookTracker tracker;
	private StasisManager stasis;
	private final Set<UUID> testMode = new HashSet<>();

	@Override
	public void onEnable() {
		saveDefaultConfig();
		rodKey = new NamespacedKey(this, "void_stasis_rod");

		tags = new TagManager(this);
		tags.load();

		tracker = new HookTracker(this, tags);
		getServer().getPluginManager().registerEvents(tracker, this);
		tracker.start();

		stasis = new StasisManager(this);
		stasis.init();
		getServer().getPluginManager().registerEvents(stasis, this);
		getServer().getPluginManager().registerEvents(new ReelListener(this, tags, stasis), this);

		if (getCommand("voidrod") != null) {
			getCommand("voidrod").setExecutor(this);
			getCommand("voidrod").setTabCompleter(this);
		}
		if (getCommand("voidstasis") != null) {
			getCommand("voidstasis").setExecutor(this);
			getCommand("voidstasis").setTabCompleter(this);
		}
		getLogger().info("Void Stasis enabled (pearl hooking, tagging, reel-in).");
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

	public boolean isTestMode(UUID id) {
		return testMode.contains(id);
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
		if (command.getName().equalsIgnoreCase("voidrod")) {
			handleVoidrod(sender, args);
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(gray("Usage: /voidstasis <give [player] | list | status | clear <player> | release <player> | reload>"));
			return true;
		}

		switch (args[0].toLowerCase()) {
			case "give" -> giveRod(sender, args);
			case "list" -> listTags(sender);
			case "status" -> status(sender);
			case "clear" -> clearTags(sender, args);
			case "release" -> releasePlayer(sender, args);
			case "reload" -> {
				reloadConfig();
				tracker.reloadSettings();
				sender.sendMessage(Component.text("Void Stasis config reloaded.", NamedTextColor.DARK_PURPLE));
			}
			default -> sender.sendMessage(gray("Unknown subcommand."));
		}
		return true;
	}

	/** /voidrod = give yourself the rod. /voidrod test = toggle self-test mode. /voidrod test tag = tag yourself. */
	private void handleVoidrod(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage(Component.text("Only players can use /voidrod.", NamedTextColor.RED));
			return;
		}
		UUID id = player.getUniqueId();
		if (args.length == 0) {
			giveRod(player, new String[] {"give"});
			return;
		}
		if (!args[0].equalsIgnoreCase("test")) {
			player.sendMessage(gray("Usage: /voidrod [test [tag]]"));
			return;
		}
		if (args.length > 1 && args[1].equalsIgnoreCase("tag")) {
			testMode.add(id);
			boolean added = tags.tag(id, id);
			player.sendMessage(Component.text(added ? "You tagged yourself." : "You are already tagged.",
					NamedTextColor.DARK_PURPLE));
			player.sendMessage(gray("Hold the Void Stasis rod, cast it, and reel in with nothing hooked to void yourself."));
			return;
		}
		if (testMode.remove(id)) {
			player.sendMessage(Component.text("Void Stasis test mode OFF.", NamedTextColor.DARK_PURPLE));
			return;
		}
		testMode.add(id);
		if (!holdsRod(player)) {
			giveRod(player, new String[] {"give"});
		}
		player.sendMessage(Component.text("Void Stasis test mode ON.", NamedTextColor.DARK_PURPLE));
		player.sendMessage(gray("Run /voidrod test tag to tag yourself (or hook your own pearl), then cast the rod and reel in to void yourself."));
		player.sendMessage(gray("To get out: /voidstasis release " + player.getName() + " or /tp to another player."));
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
		} else {
			sender.sendMessage(Component.text("Active tags (target <- tagged by):", NamedTextColor.DARK_PURPLE));
			for (Map.Entry<UUID, Set<UUID>> entry : all.entrySet()) {
				String taggers = entry.getValue().stream().map(VoidStasisPlugin::nameOf).collect(Collectors.joining(", "));
				sender.sendMessage(gray(nameOf(entry.getKey()) + " <- " + taggers));
			}
		}
		Set<UUID> inStasis = stasis.trappedPlayers();
		if (!inStasis.isEmpty()) {
			String names = inStasis.stream().map(VoidStasisPlugin::nameOf).collect(Collectors.joining(", "));
			sender.sendMessage(Component.text("In Void Stasis: ", NamedTextColor.DARK_PURPLE).append(gray(names)));
		}
	}

	private void releasePlayer(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(gray("Usage: /voidstasis release <player>"));
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
		boolean changed = stasis.release(target.getUniqueId());
		sender.sendMessage(changed
				? Component.text("Released " + args[1] + " from Void Stasis.", NamedTextColor.DARK_PURPLE)
				: gray(args[1] + " was not in Void Stasis."));
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
		if (command.getName().equalsIgnoreCase("voidrod")) {
			if (args.length == 1) {
				return List.of("test");
			}
			if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
				return List.of("tag");
			}
			return List.of();
		}
		if (args.length == 1) {
			return List.of("give", "list", "status", "clear", "release", "reload");
		}
		if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("clear")
				|| args[0].equalsIgnoreCase("release"))) {
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		}
		return List.of();
	}
}
