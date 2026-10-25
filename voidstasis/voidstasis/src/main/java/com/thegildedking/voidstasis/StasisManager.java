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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the Void Stasis world (an empty, dark world with a small bedrock platform
 * enclosed by invisible barrier walls) and the list of trapped players.
 */
public final class StasisManager implements Listener {

	private static final int PLATFORM_Y = 100;

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
		return new Location(world, 0.5, PLATFORM_Y + 1, 0.5);
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
		buildPlatform();
		world.setSpawnLocation(0, PLATFORM_Y + 1, 0);
		world.setTime(18000L);
	}

	/** 9x9 bedrock platform with a 4-high ring of invisible barrier blocks around it. */
	private void buildPlatform() {
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				world.getBlockAt(x, PLATFORM_Y, z).setType(Material.BEDROCK, false);
			}
		}
		for (int x = -5; x <= 5; x++) {
			for (int z = -5; z <= 5; z++) {
				if (Math.max(Math.abs(x), Math.abs(z)) != 5) {
					continue;
				}
				for (int y = PLATFORM_Y + 1; y <= PLATFORM_Y + 4; y++) {
					world.getBlockAt(x, y, z).setType(Material.BARRIER, false);
				}
			}
		}
	}

	/** Keeps the world at midnight and brings back anyone who ends up falling into the void. */
	private void startMaintenanceTask() {
		plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			if (world == null) {
				return;
			}
			world.setTime(18000L);
			for (Player player : world.getPlayers()) {
				if (player.getLocation().getY() < PLATFORM_Y - 10) {
					player.setFallDistance(0f);
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

	/** Trapped players cannot pearl, chorus-fruit or command their way out. */
	@EventHandler(ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		if (trapped.contains(event.getPlayer().getUniqueId())
				&& event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
			event.setCancelled(true);
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
			return new Location(world, 0.5, PLATFORM_Y + 1, 0.5);
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
