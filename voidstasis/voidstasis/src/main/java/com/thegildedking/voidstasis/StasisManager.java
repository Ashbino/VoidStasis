package com.thegildedking.voidstasis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the Void Stasis world (a completely empty, dark void) and the list of trapped players.
 * Trapped players fall endlessly: whenever they drop too far they are lifted back to the top.
 */
public final class StasisManager implements Listener {

	/** Where trapped players start falling from, and the height at which they are lifted back up. */
	private static final int SPAWN_Y = 200;
	private static final int RESET_Y = 50;

	private final VoidStasisPlugin plugin;
	private final File file;
	private final Set<UUID> trapped = new LinkedHashSet<>();
	private World world;

	public StasisManager(VoidStasisPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "trapped.yml");
	}

	public void init() {
		loadTrapped();
		createWorld();
		if (world != null) {
			startMaintenanceTask();
		}
	}

	public boolean isReady() {
		return world != null;
	}

	public boolean isTrapped(UUID id) {
		return trapped.contains(id);
	}

	public Set<UUID> trappedPlayers() {
		return Set.copyOf(trapped);
	}

	private Location spawn() {
		return new Location(world, 0.5, SPAWN_Y, 0.5);
	}

	private Location mainSpawn() {
		return Bukkit.getWorlds().get(0).getSpawnLocation();
	}

	private boolean isInStasisWorld(Player player) {
		return world != null && player.getWorld().equals(world);
	}

	/** Sends a player into Void Stasis. Returns false if it could not be done. */
	public boolean enter(Player target) {
		if (world == null) {
			return false;
		}
		trapped.add(target.getUniqueId());
		saveTrapped();
		target.setFallDistance(0f);
		if (!target.teleport(spawn())) {
			trapped.remove(target.getUniqueId());
			saveTrapped();
			return false;
		}
		target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.5f);
		target.showTitle(Title.title(
				Component.text("VOID STASIS", NamedTextColor.DARK_PURPLE),
				Component.text("You are trapped.", NamedTextColor.GRAY)));
		return true;
	}

	/** Frees a player (online or offline). Returns true if anything changed. */
	public boolean release(UUID id) {
		boolean wasTrapped = trapped.remove(id);
		if (wasTrapped) {
			saveTrapped();
		}
		Player player = Bukkit.getPlayer(id);
		boolean moved = false;
		if (player != null && player.isOnline() && isInStasisWorld(player)) {
			player.setFallDistance(0f);
			player.teleport(mainSpawn());
			player.sendActionBar(Component.text("You have been released.", NamedTextColor.GRAY));
			moved = true;
		}
		return wasTrapped || moved;
	}

	private void createWorld() {
		String name = plugin.getConfig().getString("stasis-world", "voidstasis");
		try {
			WorldCreator creator = new WorldCreator(name);
			creator.environment(World.Environment.NORMAL);
			creator.generator(new VoidGenerator());
			creator.biomeProvider(new VoidBiomeProvider());
			creator.generateStructures(false);
			world = creator.createWorld();
		} catch (Exception ex) {
			plugin.getLogger().severe("Could not create the Void Stasis world: " + ex.getMessage());
			world = null;
			return;
		}
		if (world == null) {
			plugin.getLogger().severe("Could not create the Void Stasis world (server returned null).");
			return;
		}
		clearOldPlatform();
		world.setSpawnLocation(0, SPAWN_Y, 0);
		world.setTime(18000L);
	}

	/** Removes the old bedrock platform / barrier walls from earlier versions, so the world is a true void. */
	private void clearOldPlatform() {
		for (int x = -5; x <= 5; x++) {
			for (int z = -5; z <= 5; z++) {
				for (int y = 100; y <= 104; y++) {
					if (!world.getBlockAt(x, y, z).getType().isAir()) {
						world.getBlockAt(x, y, z).setType(Material.AIR, false);
					}
				}
			}
		}
	}

	/** Keeps the world at midnight. Anyone who falls too far is lifted back up, so they fall forever. */
	private void startMaintenanceTask() {
		plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			if (world == null) {
				return;
			}
			world.setTime(18000L);
			for (Player player : world.getPlayers()) {
				if (player.getLocation().getY() < RESET_Y) {
					player.setFallDistance(0f);
					player.setVelocity(new Vector(0, 0, 0));
					player.teleport(spawn());
				}
			}
		}, 20L, 10L);
	}

	private void loadTrapped() {
		trapped.clear();
		if (!file.exists()) {
			return;
		}
		YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
		for (String raw : yml.getStringList("trapped")) {
			try {
				trapped.add(UUID.fromString(raw));
			} catch (IllegalArgumentException ignored) {
				// skip bad entries
			}
		}
	}

	private void saveTrapped() {
		YamlConfiguration yml = new YamlConfiguration();
		yml.set("trapped", trapped.stream().map(UUID::toString).toList());
		try {
			plugin.getDataFolder().mkdirs();
			yml.save(file);
		} catch (IOException ex) {
			plugin.getLogger().warning("Could not save trapped.yml: " + ex.getMessage());
		}
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		boolean shouldBeTrapped = trapped.contains(player.getUniqueId());
		boolean inside = isInStasisWorld(player);
		if (shouldBeTrapped && world != null && !inside) {
			player.teleport(spawn());
		} else if (!shouldBeTrapped && inside) {
			player.teleport(mainSpawn());
		}
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		if (world != null && trapped.contains(event.getPlayer().getUniqueId())) {
			event.setRespawnLocation(spawn());
		}
	}

	/** Trapped players cannot pearl, chorus-fruit or portal out. Admin /tp and plugin teleports are allowed. */
	@EventHandler(ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		if (!trapped.contains(event.getPlayer().getUniqueId())) {
			return;
		}
		switch (event.getCause()) {
			case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY -> event.setCancelled(true);
			default -> {
				// COMMAND (/tp), PLUGIN and others are allowed
			}
		}
	}

	/** If a trapped player ends up outside the stasis world by any means (e.g. /tp), they are free. */
	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		if (world != null && event.getFrom().equals(world)
				&& trapped.remove(event.getPlayer().getUniqueId())) {
			saveTrapped();
		}
	}

	private static final class VoidGenerator extends ChunkGenerator {
		@Override
		public boolean shouldGenerateNoise() {
			return false;
		}

		@Override
		public boolean shouldGenerateSurface() {
			return false;
		}

		@Override
		public boolean shouldGenerateCaves() {
			return false;
		}

		@Override
		public boolean shouldGenerateDecorations() {
			return false;
		}

		@Override
		public boolean shouldGenerateMobs() {
			return false;
		}

		@Override
		public boolean shouldGenerateStructures() {
			return false;
		}

		@Override
		public Location getFixedSpawnLocation(World world, Random random) {
			return new Location(world, 0.5, SPAWN_Y, 0.5);
		}
	}

	private static final class VoidBiomeProvider extends BiomeProvider {
		@Override
		public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
			return Biome.THE_VOID;
		}

		@Override
		public List<Biome> getBiomes(WorldInfo worldInfo) {
			return List.of(Biome.THE_VOID);
		}
	}
}
