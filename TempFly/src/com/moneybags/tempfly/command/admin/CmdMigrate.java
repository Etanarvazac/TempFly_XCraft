package com.moneybags.tempfly.command.admin;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.command.TempFlyCommand;
import com.moneybags.tempfly.util.Console;
import com.moneybags.tempfly.util.data.DataBridge;
import com.moneybags.tempfly.util.data.DataBridge.DataTable;
import com.moneybags.tempfly.util.data.DataBridge.DataValue;

public class CmdMigrate extends TempFlyCommand {

	private static boolean sure = false;

	public CmdMigrate(TempFly tempfly, String[] args) {
		super(tempfly, args);
	}

	@Override
	public List<String> getPotentialArguments(CommandSender s) {
		return new ArrayList<>();
	}

	@Override
	public void executeAs(CommandSender s) {

		if (!(s instanceof ConsoleCommandSender)) {
			s.sendMessage("Only the console may use this command!");
			return;
		}

		// ---- Double confirmation ----
		if (!sure) {
			s.sendMessage("§eWARNING: This will migrate ALL data from data.yml to MySQL.");
			s.sendMessage("§eIf data already exists in MySQL it may be overwritten.");
			s.sendMessage("§eRun the command again within 5 seconds to confirm.");

			sure = true;
			Bukkit.getScheduler().runTaskLater(tempfly, () -> sure = false, 100);
			return;
		}

		if (!tempfly.getDataBridge().hasDatabaseEnabled()) {
			s.sendMessage("§cYou must enable a database option in the config to migrate data!");
			return;
		}

		try {
			tempfly.getDataBridge().openMigrationConnection();
		} catch (Exception e) {
			s.sendMessage("§cFailed to open migration SQL connection!");
			e.printStackTrace();
			return;
		}

		File dataf = new File(tempfly.getDataFolder(), "data.yml");
		if (!dataf.exists()) {
			s.sendMessage("§cThere is no data.yml to migrate...");
			tempfly.getDataBridge().closeMigrationConnection();
			return;
		}

		FileConfiguration data = new YamlConfiguration();
		try {
			data.load(dataf);
		} catch (Exception e) {
			s.sendMessage("§cThere is a problem inside data.yml!");
			e.printStackTrace();
			tempfly.getDataBridge().closeMigrationConnection();
			return;
		}

		ConfigurationSection csPlayers = data.getConfigurationSection("players");
		if (csPlayers == null) {
			s.sendMessage("§cThere is no player data to migrate...");
			tempfly.getDataBridge().closeMigrationConnection();
			return;
		}

		List<String> players = new ArrayList<>(csPlayers.getKeys(false));
		int total = players.size();
		if (total == 0) {
			s.sendMessage("§cNo players found in data.yml.");
			tempfly.getDataBridge().closeMigrationConnection();
			return;
		}

		s.sendMessage("§eStarting migration of " + total + " players...");

		int migrated = 0;
		int failed = 0;

		// ---- Migration loop ----
		for (int i = 0; i < total; i++) {
			String uuid = players.get(i);

			// 1) Create row (only for SQL databases, MongoDB auto-creates documents)
			if (tempfly.getDataBridge().hasSqlEnabled() || tempfly.getDataBridge().hasSqliteEnabled()) {
				// Use appropriate INSERT syntax based on database type
				String insertQuery = tempfly.getDataBridge().hasSqliteEnabled()
					? "INSERT OR IGNORE INTO tempfly_data(uuid) VALUES(?)"
					: "INSERT OR IGNORE INTO tempfly_data(uuid) VALUES(?)";
				try (PreparedStatement stCreate = tempfly.getDataBridge().prepareStatement(insertQuery)) {
					stCreate.setString(1, uuid);
					stCreate.execute();
				} catch (SQLException e) {
					failed++;
					Console.warn("Failed to create DB entry for " + uuid);
					e.printStackTrace();
					continue;
				}
			}

			// 2) Update values
			for (DataValue value : DataValue.values()) {
				if (value.getTable() != DataTable.TEMPFLY_DATA) continue;

				StringBuilder path = new StringBuilder();
				int index = 0;
				for (String part : value.getYamlPath()) {
					if (path.length() > 0) path.append(".");
					path.append(part);
					if (index < 1) path.append(".").append(uuid);
					index++;
				}

				Object obj = data.get(path.toString());
				if (obj == null) continue;

				// Use DataBridge.setValue() which works for all database types
				try {
					String[] pathArray = new String[] { uuid };
					DataBridge.StagedChange change = 
						new DataBridge.StagedChange(value, obj, pathArray, null);
					tempfly.getDataBridge().setValue(change, false);
				} catch (Exception e) {
					Console.warn("Migration error for " + uuid + " on " + value.name());
					e.printStackTrace();
				}
			}

			migrated++;

			// Progress bar
			int percent = (int) ((migrated / (double) total) * 100);
			StringBuilder bar = new StringBuilder("[");
			int filled = percent / 2; // 50 character max
			for (int j = 0; j < 50; j++) bar.append(j < filled ? "=" : " ");
			bar.append("] ").append(percent).append("%");
			Console.info(bar.toString());
		}

		tempfly.getDataBridge().closeMigrationConnection();

		s.sendMessage("§a-------------------------------------");
		s.sendMessage("§aMigration finished!");
		s.sendMessage("§aPlayers migrated: §f" + migrated);
		s.sendMessage("§cPlayers failed: §f" + failed);
		s.sendMessage("§a-------------------------------------");
	}
}
