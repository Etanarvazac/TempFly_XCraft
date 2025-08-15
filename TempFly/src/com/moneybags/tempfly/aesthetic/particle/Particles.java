package com.moneybags.tempfly.aesthetic.particle;

import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.user.FlightUser;
import com.moneybags.tempfly.util.Console;
import com.moneybags.tempfly.util.V;
import com.moneybags.tempfly.util.data.DataBridge.DataValue;
import com.moneybags.tempfly.util.data.DataPointer;

public class Particles {

	private static Class<?>
	dustOptions = null,
	blockData;
	private static TempFly tempfly;
	private static boolean oldParticles;
	
	public static void initialize(TempFly plugin) {
		tempfly = plugin;
		try {dustOptions = Class.forName("org.bukkit.Particle$DustOptions");} catch (Exception e) {}
		try {blockData = Class.forName("org.bukkit.block.data.BlockData");} catch (Exception e) {}
		oldParticles = oldParticles();
	}
	
	public static boolean oldParticles() {
		String version = Bukkit.getBukkitVersion(); // Obtain the current version number
		String[] parts = version.split(".");
		try {
			int major = Integer.parseInt(parts[0]);
			int minor = Integer.parseInt(parts[1]);
			return major == 1 && minor <= 12;
		} catch (Exception e) {
			return true;
		}
	}
	
	public static void play(Location loc, String s) {
		if (!oldParticles) {
			Particle particle;
			try {
				particle = Particle.valueOf(s.toUpperCase());
			} catch (Exception e1) {
				try {
					particle = Particle.valueOf(V.particleType.toUpperCase());
				} catch (Exception e2) {
					particle = Particle.HAPPY_VILLAGER;
				}
			}
			try {
				Class<?> datatype = particle.getDataType();
				
				
				if (dustOptions != null && dustOptions.equals(datatype)) {
					Random rand = new Random();
					DustOptions dust = new DustOptions(
							Color.fromRGB(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256)), 2f
					);
					loc.getWorld().spawnParticle(particle,  loc, 1, dust);
				} else if (blockData != null && blockData.equals(datatype)) {
					loc.getWorld().spawnParticle(particle, loc, 1, Material.STONE.createBlockData());
				} else {
					loc.getWorld().spawnParticle(particle, loc, 1, 0, 0, 0, 0.1);
				}
			} catch (Exception e) {
				loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 1, 0, 0, 0, 0.1);
			}
		} else {
			if("ITEM_BREAK".equalsIgnoreCase(s)) {
				s = "HAPPY_VILLAGER";
			}
			
			Effect effect;
			try {
				effect = Effect.valueOf(s.toUpperCase());
			} catch (Exception e1) {
				try {
					effect = Effect.valueOf(V.particleType.toUpperCase());
				} catch (Exception e2) {
					effect = Effect.SMOKE;
				}
			}
			
			loc.getWorld().playEffect(loc, effect, 1);
		}
	}
	
	public static String loadTrail(UUID u) {
		String particle = (String) tempfly.getDataBridge().getOrDefault(DataPointer.of(DataValue.PLAYER_TRAIL, u.toString()), null);
		if (V.debug) {Console.debug("", "------Loading particle trail------", "Player: " + u.toString(), "Value from data: " + String.valueOf(particle), "Default trail enabled: " + V.particleDefault, "Default trail is: " + V.particleType, "Returning trail: " +  (particle != null ? particle: (V.particleDefault ? V.particleType : "")), "------End particle trail------", "");}
		return particle != null ? particle: (V.particleDefault ? V.particleType : "");
	}
	
	/**
	 * Set a players particle trail.
	 * If particle is set to null or if the trail specified does not exist TempFly will attempt to use the default trail if enabled in the config.
	 * If it is set to an empty string however the particle will be disabled, IE no trail. This is what the remove trail command does.
	 * @param u the player
	 * @param particle the particle
	 */
	public static void setTrail(UUID u, String particle) {
		FlightUser user = tempfly.getFlightManager().getUser(Bukkit.getPlayer(u));
		if (user != null) {
			user.setTrail(particle);
			return;
		}
		tempfly.getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_TRAIL, u.toString()), particle);
	}
	
}

