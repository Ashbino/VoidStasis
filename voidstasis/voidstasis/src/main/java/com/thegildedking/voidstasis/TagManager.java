package com.thegildedking.voidstasis;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores who tagged whom, by UUID, and saves it to plugins/VoidStasis/tags.yml. */
public final class TagManager {

	private final JavaPlugin plugin;
	private final File file;
	/** target UUID -> UUIDs of everyone who has tagged that target */
	private final Map<UUID, Set<UUID>> tags = new HashMap<>();

	public TagManager(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "tags.yml");
	}

	public void load() {
		tags.clear();
		if (!file.exists()) {
			return;
		}
		YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection section = yml.getConfigurationSection("tags");
		if (section == null) {
			return;
		}
		for (String key : section.getKeys(false)) {
			UUID target;
			try {
				target = UUID.fromString(key);
			} catch (IllegalArgumentException ex) {
				continue;
			}
			Set<UUID> taggers = new LinkedHashSet<>();
			for (String raw : section.getStringList(key)) {
				try {
					taggers.add(UUID.fromString(raw));
				} catch (IllegalArgumentException ignored) {
					// skip bad entries
				}
			}
			if (!taggers.isEmpty()) {
				tags.put(target, taggers);
			}
		}
	}

	public void save() {
		YamlConfiguration yml = new YamlConfiguration();
		for (Map.Entry<UUID, Set<UUID>> entry : tags.entrySet()) {
			yml.set("tags." + entry.getKey(), entry.getValue().stream().map(UUID::toString).toList());
		}
		try {
			plugin.getDataFolder().mkdirs();
			yml.save(file);
		} catch (IOException ex) {
			plugin.getLogger().warning("Could not save tags.yml: " + ex.getMessage());
		}
	}

	/** Returns false if this tagger already tagged this target. */
	public boolean tag(UUID tagger, UUID target) {
		Set<UUID> taggers = tags.computeIfAbsent(target, k -> new LinkedHashSet<>());
		if (!taggers.add(tagger)) {
			return false;
		}
		save();
		return true;
	}

	public boolean has(UUID tagger, UUID target) {
		Set<UUID> taggers = tags.get(target);
		return taggers != null && taggers.contains(tagger);
	}

	/** Removes only this tagger's tag on this target (used when a tag is consumed). */
	public boolean remove(UUID tagger, UUID target) {
		Set<UUID> taggers = tags.get(target);
		if (taggers == null || !taggers.remove(tagger)) {
			return false;
		}
		if (taggers.isEmpty()) {
			tags.remove(target);
		}
		save();
		return true;
	}

	/** Clears every tag on this player and every tag this player holds. Returns how many were removed. */
	public int clearPlayer(UUID player) {
		int removed = 0;
		Set<UUID> onPlayer = tags.remove(player);
		if (onPlayer != null) {
			removed += onPlayer.size();
		}
		Iterator<Map.Entry<UUID, Set<UUID>>> it = tags.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Set<UUID>> entry = it.next();
			if (entry.getValue().remove(player)) {
				removed++;
			}
			if (entry.getValue().isEmpty()) {
				it.remove();
			}
		}
		if (removed > 0) {
			save();
		}
		return removed;
	}

	public Set<UUID> targetsOf(UUID tagger) {
		Set<UUID> result = new LinkedHashSet<>();
		for (Map.Entry<UUID, Set<UUID>> entry : tags.entrySet()) {
			if (entry.getValue().contains(tagger)) {
				result.add(entry.getKey());
			}
		}
		return result;
	}

	public Map<UUID, Set<UUID>> snapshot() {
		Map<UUID, Set<UUID>> copy = new HashMap<>();
		for (Map.Entry<UUID, Set<UUID>> entry : tags.entrySet()) {
			copy.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
		}
		return copy;
	}
}
