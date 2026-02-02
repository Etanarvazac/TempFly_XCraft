package com.moneybags.tempfly.util.data;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.StandardCopyOption;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import com.moneybags.tempfly.util.Console;

/**
 * This class is going to be reworked in a future version. Too much static abuse and repetition.
 *
 */
public class Files {

	public static enum C {
		CONFIG,
		LANG,
		DATA,
		PAGE;
	}
	
	private static File
	configf,
	langf,
	pagef;
	
	public static FileConfiguration
	config,
	lang,
	page;
	
	public static void createFiles(Plugin plugin){
	    configf = new File(plugin.getDataFolder(), "config.yml");
	    langf = new File(plugin.getDataFolder(), "lang.yml");
	    pagef = new File(plugin.getDataFolder(), "page.yml");
	    
		// Check versions BEFORE creating files
		fileUpdateCheck(plugin);

		// Now create files if they don't exist
	    if (!configf.exists()){
	    	configf.getParentFile().mkdirs();
	        plugin.saveResource("config.yml", false);
	    }
	    if (!langf.exists()){
	    	langf.getParentFile().mkdirs();
	        plugin.saveResource("lang.yml", false);
	    }
	    if (!pagef.exists()){
	    	pagef.getParentFile().mkdirs();
	        plugin.saveResource("page.yml", false);
	    }
	    
	    config = new YamlConfiguration();
	    lang = new YamlConfiguration();
	    page = new YamlConfiguration();
	    
	    try {
	        config.load(configf);
	    } catch (IOException | InvalidConfigurationException e1){
	    	Console.severe("There is a problem inside the config.yml, If you cannot fix the issue, please contact the developer.");
	        e1.printStackTrace();
	    }
	    try {
	        lang.load(langf);
	    } catch (IOException | InvalidConfigurationException e1){
	    	Console.severe("There is a problem inside the lang.yml, If you cannot fix the issue, please contact the developer.");
	        e1.printStackTrace();
	    }
	    try {
	        page.load(pagef);
	    } catch (IOException | InvalidConfigurationException e1){
	    	Console.severe("There is a problem inside the page.yml, If you cannot fix the issue, please contact the developer.");
	        e1.printStackTrace();
	    }
	}

	// Let's check if the Config and Lang files are up to date. If not, back them up and merge new keys into them.
	// Data and Page files are left alone because they are solely player data or made by the server's owner and staff.
	public static void fileUpdateCheck(Plugin plugin) {
		Console.info("Checking configuration files for updates...");
		// Load embeded config from JAR to get expected versions
		InputStream stream = plugin.getResource("config.yml");
		YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));

		String expectedConf = jarConfig.getString("system.file_versions.conf");
		String expectedLang = jarConfig.getString("system.file_versions.lang");

		// Check current non-jar config version
		if (configf.exists()) {
			FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configf);
			String currentConf = diskConfig.getString("system.file_versions.conf");
			if (currentConf == null || !currentConf.equals(expectedConf)) {
				Console.info("--------- Config File Update -----------");
				backupAndUpdate(plugin, "config.yml", currentConf, expectedConf);
				Console.info("--------- End Config File Update -----------");
			}
		}

		// Check current non-jar lang version
		// Note: Lang file version is stored in config.yml, not in lang.yml itself
		if (langf.exists()) {
			FileConfiguration diskConfig = YamlConfiguration.loadConfiguration(configf);
			String currentLang = diskConfig.getString("system.file_versions.lang");
			if (currentLang == null || !currentLang.equals(expectedLang)) {
				Console.info("--------- Lang File Update -----------");
				backupAndUpdate(plugin, "lang.yml", currentLang, expectedLang);
				// Update the config file with the new lang version
				diskConfig.set("system.file_versions.lang", expectedLang);
				try {
					diskConfig.save(configf);
					Console.info("Updated system.file_versions.lang to " + expectedLang + " in config.yml");
				} catch (Exception e) {
					Console.warn("Failed to update system.file_versions.lang in config.yml");
					e.printStackTrace();
				}
				Console.info("--------- End Lang File Update -----------");
			}
		}
	}

	// Backup/Replace method for above
	private static void backupAndUpdate(Plugin plugin, String fileName, String currentVersion, String expectedVersion) {
		try {
			File lastFile = new File(plugin.getDataFolder(), fileName);
			File backupFile = new File(plugin.getDataFolder(), fileName.replace(".yml", "_v" + (currentVersion != null ? currentVersion : "unknown") + "_backup.yml"));
			File tempFile = new File(plugin.getDataFolder(), fileName + ".tmp");

			// Step 1: Make backup with nio.Files so everything is preserved
			java.nio.file.Files.copy(lastFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Console.info("Old version: " + (currentVersion != null ? currentVersion : "unknown") + ", New version: " + expectedVersion);
			Console.info("Backup saved as " + backupFile.getName());

			// Step 2: Extract the new version from JAR and save to temp location
			Console.info("Updating " + fileName + "...");
			InputStream jarStream = plugin.getResource(fileName);
			if (jarStream == null) {
				Console.warn("Could not load " + fileName + " from JAR for update.");
				return;
			}
			java.nio.file.Files.copy(jarStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			jarStream.close();

			// Step 3: Let's load in the old config
			FileConfiguration oldFileConfig = YamlConfiguration.loadConfiguration(lastFile);

			// Step 4: Read everything from both files, including comments. We want to not remove anything the server had noted.
			List<String> oldLines = java.nio.file.Files.readAllLines(lastFile.toPath());
			List<String> newLines = java.nio.file.Files.readAllLines(tempFile.toPath());

			// Step 5: Now we want to build a merged version
			List<String> mergedLines = new ArrayList<>();
			List<String> currentPath = new ArrayList<>();

			for (String newLine : newLines) {
				String trimmed = newLine.trim();

				// Copy comments and empty lines directly from JAR
				if (trimmed.startsWith("#") || trimmed.isEmpty()) {
					mergedLines.add(newLine);
					continue;
				}

				// Calculate indentation level (2 spaces each)
				int indent = getIndentLevel(newLine);

				// Now adjust the path based on indentation
				while (currentPath.size() > indent / 2) {
					currentPath.remove(currentPath.size() - 1);
				}

				// Extract keys from here
				String key = extractKey(newLine);
				if (key == null) {
					mergedLines.add(newLine);
					continue;
				}

				// Let's now build the full dot notation of the path
				String fullPath = currentPath.isEmpty() ? key : String.join(".", currentPath) + "." + key;

				// And let's check if this is a parent key instead of value
				boolean isParent = !newLine.contains(": ") || newLine.trim().endsWith(":");

				if (isParent) {
					// Since we're working with a parent key, just add it and update path
					mergedLines.add(newLine);
					currentPath.add(key);
				} else {
					// Since we're working with a value key, we can check for existing value However, we never preserve 
					// values for system.file_versions in the config file.
					if (oldFileConfig.contains(fullPath) && !fullPath.startsWith("system.file_versions")) {
						String oldValue = findValueInLines(oldLines, fullPath, key, newLine);
						if (oldValue != null) {
							Console.debug("Preserving old value for " + fullPath + ": " + oldValue.trim());
							mergedLines.add(oldValue);
						} else {
							Console.debug("Could not find old value for " + fullPath + ", using new: " + newLine.trim());
							mergedLines.add(newLine);
						}
					} else {
						// Since we're working with a new key or a file version key, we'll add it from the new file
						Console.debug("Adding new key or version key: " + fullPath);
						mergedLines.add(newLine);
					}
				}
			}

			// Step 6: Write our new file into the old one
			java.nio.file.Files.write(lastFile.toPath(), mergedLines);
			Console.info(fileName + " has been updated to v" + expectedVersion + "!");
			Console.info("Moving backup file to backups folder...");

			// Step 7: Move backup files to to backup folder
			File backupFolder = new File(plugin.getDataFolder(), "backups");
			if (!backupFolder.exists()) {
				backupFolder.mkdirs();
			}
			File finalBackupFile = new File(backupFolder, backupFile.getName());
			java.nio.file.Files.move(backupFile.toPath(), finalBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Console.info("Backup of " + fileName + " has been moved to backup folder.");

			// Step 8: Temp file is no longer needed, let's delete it and inform the console
			tempFile.delete();
			Console.info("Update completed successfully!");
		} catch (Exception e) {
			// Well that sucks, let's let console know there was a backup made, but update didn't work.
			Console.warn("Failed to update the " + fileName + " file! Backup file saved in TempFly folder. Please update manually.");
			e.printStackTrace();
		}
	}

	// Helper #1: Let's extract YAML key in the right format
	private static String extractKey(String line) {
		int colonIndex = line.indexOf(':');
		if (colonIndex == -1) return null;
		return line.substring(0, colonIndex).trim();
	}

	// Helper #2: Let's get indentation level based on spaces
	private static int getIndentLevel(String line) {
		int spaces = 0;
		for (char c : line.toCharArray()) {
			if (c == ' ') {
				spaces++;
			} else break;
		}
		return spaces;
	}

	// Helper #3: Now let's get the line with the server's value for the given key
	private static String findValueInLines(List<String> lines, String fullPath, String key, String newLine) {
		int targetIndent = getIndentLevel(newLine);
		String[] pathParts = fullPath.split("\\.");
		Console.debug("Looking for key '" + key + "' (path: " + fullPath + ") at indent level " + targetIndent);

		// Build the expected parent indents
		String[] pathParts = fullPath.split("\\.");
		List<String> pathStack = new ArrayList<>();
		for (iString part : pathParts) {
			pathStack.add(part);
		}
		pathStack.remove(pathStack.size() - 1); // Remove key, we're just getting the parents

		// Track our current position in the YAML hierarchy
		List<String> currentStack = new ArrayList<>();
		
		for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
			String line = lines.get(lineIdx);
			String lineTrimmed = line.trim();
			int lineIndent = getIndentLevel(line);
			
			// Skip comments and empty lines
			if (lineTrimmed.startsWith("#") || lineTrimmed.isEmpty()) {
				continue;
			}
			
			// Adjust current stack based on indentation
			while (!currentStack.isEmpty() && lineIndent <= (currentStack.size() - 1) * 2) {
				currentStack.remove(currentStack.size() - 1);
			}
			
			// Extract the key from this line
			String lineKey = extractKey(line);
			if (lineKey == null) {
				continue;
			}
			
			// Add to stack if this is a parent (ends with : and has nothing after)
			if (lineTrimmed.endsWith(":") && !lineTrimmed.contains(": ")) {
				if (lineIndent == currentStack.size() * 2) {
					currentStack.add(lineKey);
				}
			} else if (lineIndent == currentStack.size() * 2) {
				// This is a value at the current depth
				// Check if current stack matches our expected path parents
				boolean stackMatches = true;
				if (currentStack.size() != pathStack.size()) {
					stackMatches = false;
				} else {
					for (int i = 0; i < currentStack.size(); i++) {
						if (!currentStack.get(i).equals(pathStack.get(i))) {
							stackMatches = false;
							break;
						}
					}
				}
				
				// If this is our target key and the stack matches, we found it
				if (stackMatches && lineKey.equals(key)) {
					Console.debug("Found matching line at correct path: " + lineTrimmed);
					int colonIndex = lineTrimmed.indexOf(':');
					if (colonIndex == -1) {
						Console.debug("No colon found in line");
						return null;
					}
					
					String oldValue = lineTrimmed.substring(colonIndex + 1).trim();
					Console.debug("Extracted old value: '" + oldValue + "'");
					
					// Rebuild the line with proper indentation
					String indent = " ".repeat(targetIndent);
					String result = indent + key + ": " + oldValue;
					Console.debug("Rebuilt line: " + result);
					return result;
				}
			}
		}
		
		Console.debug("Key '" + key + "' was not found at path: " + fullPath);
		return null;
	}
	
	public static void createConfig(InputStream stream, File file) throws IOException {
		byte[] buffer = new byte[stream.available()];
		stream.read(buffer);
		OutputStream outStream = new FileOutputStream(file);
		outStream.write(buffer);
		outStream.close();
	}
	
}

