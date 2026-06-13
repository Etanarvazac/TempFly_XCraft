package com.moneybags.tempfly.util;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.primitives.Doubles;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class U {

	private static final Pattern LOCATION_STRING_PATTERN = Pattern.compile("~");
	private static final String PREFIX = "{PREFIX}";

	public static void command(String s) {
		Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), s);
	}

	private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)");

	/**
	 * Determines whether the running server uses the modern (1.12+) Bukkit API.
	 * Handles both the legacy "1.x.y" scheme and the calendar-based scheme
	 * introduced in 2026 (e.g. "26.1.2"). Anything older than 1.12 is treated
	 * as legacy. Defaults to modern when the version cannot be parsed.
	 */
	public static boolean isModernServer() {
		java.util.regex.Matcher m = VERSION_PATTERN.matcher(Bukkit.getBukkitVersion());
		if (!m.find()) {
			return true;
		}
		int major = Integer.parseInt(m.group(1));
		int minor = Integer.parseInt(m.group(2));
		// Calendar versions (>= year 2026) and any 1.12+ release are modern.
		return major > 1 || (major == 1 && minor >= 12);
	}
	
	public static boolean isPlayer(CommandSender s){
		return s instanceof Player;
	}
	
	public static String locationToString(Location loc) {
		if (loc == null) {
			return null;
		}

		return String.format(
				"%s~%d~%d~%d",
				loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()
		);
	}
	
	public static Location locationFromString(String loc) {
		String[] args = LOCATION_STRING_PATTERN.split(loc);

		if (args.length < 4) {
			return null;
		}

		World world = Bukkit.getWorld(args[0]);

		if (world == null) {
			return null;
		}

		Double x = Doubles.tryParse(args[1]);
		Double y = Doubles.tryParse(args[2]);
		Double z = Doubles.tryParse(args[3]);

		return (x == null || y == null || z == null) ? null : new Location(world, x, y , z);
	}
	
	public static String cc(String m) {
		return ChatColor.translateAlternateColorCodes('&', Strings.nullToEmpty(m));
	}
	
	public static String strip(String m) {
		return ChatColor.stripColor(U.cc(m));
	}
	
	public static void m(CommandSender p, String s) {
		if (s == null || s.equals(PREFIX) || s.length() == 0) {
			return;
		}

		p.sendMessage(s.replace(PREFIX, V.prefix));
	}
	
	public static void m(OfflinePlayer p, String s) {
		if (p.isOnline()) {
			m((Player) p, s);
		}
	}
	
	public static void m(Player p, String s) {
		if (s == null || s.isEmpty() || s.equals(V.prefix) || s.equals(PREFIX)) {
			return;
		}

		p.sendMessage(s.replace(PREFIX, V.prefix));
	}
	
	public static boolean hasPermission(CommandSender s, String perm){
		return !isPlayer(s) || s.hasPermission(perm);
	}
	
	public static String locToString(Location loc) {
		return locationToString(loc);
	}
	
	public static Location locFromString(String loc) {
		return locationFromString(loc);
	}
	
	public static ItemStack getConfigItem(FileConfiguration config, String path) {
		ConfigurationSection section = config.getConfigurationSection(path);

		if (section == null) {
			return new ItemStack(Material.STONE);
		}

		ItemStack item = new ItemStack(Material.STONE, Math.max(1, section.getInt("amount", 1)));
		ItemMeta meta = item.getItemMeta();
		
		meta.setDisplayName(cc(ChatColor.RESET + section.getString("name", "&cThis item is broken. :'(")));
		meta.setLore(
				section.getStringList("lore").stream()
						.map(it -> cc(ChatColor.RESET + it))
						.collect(Collectors.toList())
		);

		item.setItemMeta(meta);
		return item;
	}
	
	public static String arrayToString(Object[] array, String divider) {
		if (array == null) {
			return null;
		}

		return Joiner.on(Strings.nullToEmpty(divider)).join(array);
	}
	
	public static String[] skipArray(String[] array, int skip) {
		if (array.length <= skip) {
			return new String[0];
		}

		return Arrays.copyOfRange(array, skip, array.length);
	}

}
