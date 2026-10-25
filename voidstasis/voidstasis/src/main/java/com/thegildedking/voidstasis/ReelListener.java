package com.thegildedking.voidstasis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Handles reeling in a hooked player: only a target tagged by the reeler is affected. */
public final class ReelListener implements Listener {

	private final VoidStasisPlugin plugin;
	private final TagManager tags;
	private final StasisManager stasis;
	private final Set<UUID> activating = new HashSet<>();
	private final Map<UUID, Long> lastNotice = new HashMap<>();

	public ReelListener(VoidStasisPlugin plugin, TagManager tags, StasisManager stasis) {
		this.plugin = plugin;
		this.tags = tags;
		this.stasis = stasis;
	}

	@EventHandler(ignoreCancelled = true)
	public void onReel(PlayerFishEvent event) {
		PlayerFishEvent.State state = event.getState();
		Player tagger = event.getPlayer();
		Player target;
		if (state == PlayerFishEvent.State.CAUGHT_ENTITY) {
			if (!plugin.holdsRod(tagger)) {
				return;
			}
			if (!(event.getCaught() instanceof Player hooked)) {
				return;
			}
			if (hooked.getUniqueId().equals(tagger.getUniqueId())) {
				return;
			}
			target = hooked;
		} else if (state == PlayerFishEvent.State.REEL_IN && plugin.isTestMode(tagger.getUniqueId())) {
			// Test mode only: reeling in with nothing hooked targets yourself.
			if (!plugin.holdsRod(tagger)) {
				return;
			}
			target = tagger;
		} else {
			return;
		}
		if (!tagger.isOnline() || tagger.isDead() || !target.isOnline() || target.isDead()) {
			return;
		}

		UUID taggerId = tagger.getUniqueId();
		UUID targetId = target.getUniqueId();

		if (!tags.has(taggerId, targetId)) {
			notice(tagger, target == tagger
					? "You are not tagged. Use /voidrod test tag first."
					: target.getName() + " is not tagged by you.", NamedTextColor.RED);
			return;
		}
		if (stasis.isTrapped(targetId)) {
			notice(tagger, target.getName() + " is already in Void Stasis.", NamedTextColor.RED);
			return;
		}
		String mode = plugin.getConfig().getString("on-reel", "drop").toLowerCase();
		boolean useStasisWorld = mode.equals("void") || mode.equals("stasis");
		if (useStasisWorld && !stasis.isReady()) {
			notice(tagger, "The Void Stasis world is unavailable.", NamedTextColor.RED);
			return;
		}

		// Guard against the event firing twice for the same target.
		if (!activating.add(targetId)) {
			return;
		}
		try {
			// Consume only this tagger's tag; other taggers' tags stay.
			if (!tags.remove(taggerId, targetId)) {
				return;
			}

			Location where = target.getLocation().add(0, 1, 0);
			where.getWorld().spawnParticle(Particle.PORTAL, where, 60, 0.4, 0.8, 0.4, 0.8);

			boolean ok;
			if (mode.equals("kill")) {
				target.setHealth(0.0);
				ok = true;
			} else if (useStasisWorld) {
				ok = stasis.enter(target);
			} else {
				ok = dropIntoVoid(target);
			}

			if (!ok) {
				// Could not send them: give the tag back so it is not wasted.
				tags.tag(taggerId, targetId);
				notice(tagger, "Void Stasis failed. The tag was kept.", NamedTextColor.RED);
				return;
			}

			tagger.playSound(tagger.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.5f);
			tagger.sendActionBar(Component.text(target.getName() + " has been cast into the void.",
					NamedTextColor.DARK_PURPLE));
		} finally {
			activating.remove(targetId);
		}
	}

	/** Teleports the player far below the bottom of the world (same x/z) so they die to void damage. */
	private boolean dropIntoVoid(Player target) {
		target.eject();
		target.leaveVehicle();
		Location below = target.getLocation().clone();
		below.setY(below.getWorld().getMinHeight() - 70);
		return target.teleport(below);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastNotice.remove(event.getPlayer().getUniqueId());
	}

	private void notice(Player player, String message, NamedTextColor color) {
		long now = System.currentTimeMillis();
		Long last = lastNotice.get(player.getUniqueId());
		if (last != null && now - last < 1500L) {
			return;
		}
		lastNotice.put(player.getUniqueId(), now);
		player.sendActionBar(Component.text(message, color));
	}
}
