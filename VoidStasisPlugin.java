package com.thegildedking.voidstasis;

import org.bukkit.plugin.java.JavaPlugin;

public final class VoidStasisPlugin extends JavaPlugin {

	@Override
	public void onEnable() {
		getLogger().info("Void Stasis enabled (Stage 1 skeleton).");
	}

	@Override
	public void onDisable() {
		getLogger().info("Void Stasis disabled.");
	}
}
