package com.thegildedking.voidstasis;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoidStasis implements ModInitializer {
	public static final String MOD_ID = "voidstasis";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Void Stasis initialised (Stage 1 skeleton).");
	}
}
