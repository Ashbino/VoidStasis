package com.thegildedking.voidstasis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla bobbers cannot hook Ender Pearls, so this simulates it on the server:
 * while a player has a Void Stasis rod bobber out, any thrown pearl that comes
 * within range of the bobber counts as hooked and tags the pearl's owner.
 */
public final class HookTracker implements Listener {

	private final VoidStasisPlugin plugin;
	private final TagManager tags;
	private final Map<UUID, FishHook> hooks = new HashMap<>();
	private final Map<UUID, Long> lastFailMessage = new HashMap<>();

	private double radius;
	private boolean allowSelfTag;

	public HookTracker(VoidStasisPlugin plugin, TagManager tags) {
		this.plugin = plugin;
		this.tags = tags;
		reloadSettings();
	}

	public void reloadSettings() {
		radius = Math.max(0.5, plugin.getConfig().getDouble("pearl-hook-radius", 2.0));
		allowSelfTag = plugin.getConfig().getBoolean("allow-self-tag", false);
	}

	public void start() {
		plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
	}

	@EventHandler
	public void onFish(PlayerFishEvent event) {
		if (event.getState() != PlayerFishEvent.State.FISHING) {
			return;
		}
		Player player = event.getPlayer();
		if (plugin.holdsRod(player)) {
			hooks.put(player.getUniqueId(), event.getHook());
		} else {
			hooks.remove(player.getUniqueId());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		hooks.remove(event.getPlayer().getUniqueId());
		lastFailMessage.remove(event.getPlayer().getUniqueId());
	}

	private void tick() {
		Iterator<Map.Entry<UUID, FishHook>> it = hooks.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, FishHook> entry = it.next();
			Player tagger = Bukkit.getPlayer(entry.getKey());
			FishHook hook = entry.getValue();
			if (tagger == null || !tagger.isOnline() || tagger.isDead() || hook == null || !hook.isValid()) {
				it.remove();
				continue;
			}
			if (!plugin.holdsRod(tagger)) {
				continue;
			}
			for (EnderPearl pearl : hook.getWorld().getNearbyEntitiesByType(EnderPearl.class, hook.getLocation(), radius)) {
				if (tryHook(tagger, pearl)) {
					break;
				}
			}
		}
	}

	private boolean tryHook(Player tagger, EnderPearl pearl) {
		if (!pearl.isValid() || pearl.isDead()) {
			return false;
		}
		// Only real, player-thrown pearls count.
		if (!(pearl.getShooter() instanceof Player owner)) {
			return false;
		}
		if (!owner.isOnline() || owner.isDead()) {
			return false;
		}
		if (owner.getUniqueId().equals(tagger.getUniqueId()) && !allowSelfTag) {
			fail(tagger, "You cannot tag yourself.");
			return false;
		}
		if (!tags.tag(tagger.getUniqueId(), owner.getUniqueId())) {
			fail(tagger, owner.getName() + " is already tagged by you.");
			return false;
		}

		// Pull the pearl out of the air so its owner is not teleported.
		var pearlLocation = pearl.getLocation();
		pearl.remove();

		pearlLocation.getWorld().spawnParticle(Particle.PORTAL, pearlLocation, 40, 0.3, 0.3, 0.3, 0.6);
		tagger.playSound(tagger.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.5f);
		tagger.sendActionBar(Component.text("Pearl hooked: " + owner.getName() + " is tagged.", NamedTextColor.DARK_PURPLE));
		owner.playSound(owner.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.0f);
		owner.sendActionBar(Component.text("Something has marked you...", NamedTextColor.DARK_GRAY));
		return true;
	}

	private void fail(Player tagger, String message) {
		long now = System.currentTimeMillis();
		Long last = lastFailMessage.get(tagger.getUniqueId());
		if (last != null && now - last < 1500L) {
			return;
		}
		lastFailMessage.put(tagger.getUniqueId(), now);
		tagger.sendActionBar(Component.text(message, NamedTextColor.RED));
	}
}
