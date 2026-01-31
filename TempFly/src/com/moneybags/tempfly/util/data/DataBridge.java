package com.moneybags.tempfly.util.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.util.Console;
import com.mysql.cj.jdbc.MysqlConnectionPoolDataSource;
import com.mysql.cj.jdbc.MysqlDataSource;


public class DataBridge implements DataFileHolder {

	private TempFly tempfly;
	private MysqlDataSource dataSource;
	// MongoDB and SQLite handlers loaded via reflection to avoid class loading issues
	private Object mongoHandler;
	private Object sqliteHandler;
	private Connection migrationConnection = null;
	
	private File dataf;
	private FileConfiguration data;
	
	private ExecutorService executor;
	
	// Staged changes are held in local memory until either the autosave runs, or they are forcefully committed.
	// The databridge will act like these changes are part of the database even though they are local. 
	// It will look to see if there is data here first before it queries the database or YAML file.

	private Map<DataPointer, StagedChange> changes = new ConcurrentHashMap<>();
	// A list of pointers that tell the asynchronous batch manager to save the data they point to
	// if it exists in the list of changes.

	private List<DataPointer> manualCommit = new ArrayList<>();
	
	public MysqlDataSource getDataSource() {
		return dataSource;
	}
	
	public boolean hasDatabaseEnabled() {
		return dataSource != null || mongoHandler != null || sqliteHandler != null;
	}

	// Helpers for checking specific database types
	public boolean hasSqlEnabled() {
		return dataSource != null;
	}

	public boolean hasMongoEnabled() {
		return mongoHandler != null;
	}

	public boolean hasSqliteEnabled() {
		return sqliteHandler != null;
	}
	
	public boolean connectSql() throws SQLException {
		String
		host = Files.config.getString("system.mysql.host"),
		name = Files.config.getString("system.mysql.name"),
		user = Files.config.getString("system.mysql.user"),
		pass = Files.config.getString("system.mysql.pass");
		
		MysqlDataSource dataSource = new MysqlConnectionPoolDataSource();
		dataSource.setServerName(host);
		dataSource.setPortNumber(Files.config.getInt("system.mysql.port"));
		dataSource.setDatabaseName(name);
		dataSource.setUser(user);
		dataSource.setPassword(pass);
		
	    try (Connection conn = dataSource.getConnection()) {
	        if (!conn.isValid(1)) {
	        	Console.severe("Could not establish a connection to MySQL database!");
	            return false;
	        } else {
				Console.info("Successfully connected to MySQL database!");
			}
	    } 
	    
	    this.dataSource = dataSource;
	    return true;
	}

	public boolean connectMongo() {
		try {
			// Load MongoDBHandler via reflection to avoid class loading issues
			Class<?> handlerClass = Class.forName("com.moneybags.tempfly.util.data.MongoDBHandler");
			this.mongoHandler = handlerClass.getDeclaredConstructor().newInstance();
			
			// Get configuration section
			ConfigurationSection section = Files.config.getConfigurationSection("system.mongodb");
			
			// Call connect method via reflection
			java.lang.reflect.Method connectMethod = handlerClass.getMethod("connect", ConfigurationSection.class);
			boolean success = (boolean) connectMethod.invoke(mongoHandler, section);
			
			if (!success) {
				this.mongoHandler = null;
				return false;
			}
			
			return true;
			
		} catch (ClassNotFoundException e) {
			Console.severe("MongoDB driver not found! This feature requires the SHADED version of TempFly.");
			Console.severe("Download from: https://github.com/Etanarvazac/TempFly_XCraft/releases (use TempFly-X.X.X-shaded.jar)");
			return false;
		} catch (NoClassDefFoundError e) {
			Console.severe("MongoDB driver classes missing! This feature requires the SHADED version of TempFly.");
			Console.severe("Download from: https://github.com/Etanarvazac/TempFly_XCraft/releases (use TempFly-X.X.X-shaded.jar)");
			return false;
		} catch (Exception e) {
			Console.severe("Failed to load MongoDB handler!");
			e.printStackTrace();
			return false;
		}
	}

	public boolean connectSqlite() {
		try {
			// Load SQLiteHandler via reflection to avoid class loading issues
			Class<?> handlerClass = Class.forName("com.moneybags.tempfly.util.data.SQLiteHandler");
			this.sqliteHandler = handlerClass.getConstructor(TempFly.class).newInstance(tempfly);
			
			// Get configuration section
			ConfigurationSection section = Files.config.getConfigurationSection("system.sqlite");
			
			// Call connect method via reflection
			java.lang.reflect.Method connectMethod = handlerClass.getMethod("connect", ConfigurationSection.class);
			boolean success = (boolean) connectMethod.invoke(sqliteHandler, section);
			
			if (!success) {
				this.sqliteHandler = null;
				return false;
			}
			
			return true;
			
		} catch (ClassNotFoundException e) {
			Console.severe("SQLite driver not found! This feature requires the SHADED version of TempFly or Spigot, Paper, or Purpur server types, which includes the drivers..");
			Console.severe("Download from: https://github.com/Etanarvazac/TempFly_XCraft/releases (use TempFly-X.X.X-shaded.jar)");
			return false;
		} catch (NoClassDefFoundError e) {
			Console.severe("SQLite driver classes missing! This feature requires the SHADED version of TempFly or Spigot, Paper, or Purpur server types, which includes the drivers.");
			Console.severe("Download from: https://github.com/Etanarvazac/TempFly_XCraft/releases (use TempFly-X.X.X-shaded.jar)");
			return false;
		} catch (Exception e) {
			Console.severe("Failed to load SQLite handler!");
			e.printStackTrace();
			return false;
		}
	}
	
	private void initMySqlDb() throws SQLException {
	    String setup;
	    try (InputStream in = tempfly.getResource("dbsetup.sql")) {
	        setup = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
	    } catch (IOException e) {
			Console.severe("Failed to load database setup script!");
			e.printStackTrace();
			return;
		}
	    String[] queries = setup.split(";");
	    for (String query : queries) {
	        if (query.isBlank()) continue;
	        try (Connection conn = dataSource.getConnection();
	             PreparedStatement stmt = conn.prepareStatement(query)) {
	            stmt.execute();
	        } 
	    }
	    Console.info("MySQL setup complete.");
	}

	private void initMongoDB () {
		try {
			if (mongoHandler != null) {
				// Call initialize method via reflection
				java.lang.reflect.Method initMethod = mongoHandler.getClass().getMethod("initialize");
				initMethod.invoke(mongoHandler);
			}
		} catch (Exception e) {
			Console.severe("MongoDB setup failed.");
			e.printStackTrace();
		}
	}

	private void initSqlite() {
		try {
			if (sqliteHandler != null) {
				// Call initialize method via reflection
				java.lang.reflect.Method initMethod = sqliteHandler.getClass().getMethod("initialize");
				initMethod.invoke(sqliteHandler);
			}
		} catch (Exception e) {
			Console.severe("SQLite setup failed.");
			e.printStackTrace();
		}
	}
	
	public DataBridge(TempFly tempfly) throws SQLException {
		this.tempfly = tempfly;
		// Try each database based on priority order: MySQL > MongoDB > SQLite
		if (Files.config.getBoolean("system.mysql.enabled")) {
			connectSql();
			initMySqlDb();
		} else if (Files.config.getBoolean("system.mongodb.enabled")) {
			connectMongo();
			initMongoDB();
		} else if (Files.config.getBoolean("system.sqlite.enabled")) {
			connectSqlite();
			initSqlite();
		}
		
		// If database connection could not be established, fall back to YAML file.
		if (!hasSqlEnabled()) {
			dataf = new File(tempfly.getDataFolder(), "data.yml");
		    if (!dataf.exists()){
		    	dataf.getParentFile().mkdirs();
		    	tempfly.saveResource("data.yml", false);
		    }
		    data = new YamlConfiguration();  
		    try { data.load(dataf); } catch (Exception e1) {
		    	Console.severe("There is a problem inside data.yml!");
		        e1.printStackTrace();
		    }
		    formatYamlData(tempfly);
		}
		this.executor = Executors.newCachedThreadPool();
	}
	
	
	/**
	 * format the data file from legacy TempFly version.
	 * @param plugin
	 */
	private void formatYamlData(TempFly plugin) {
		double version = data.getDouble("version", 0.0);
		if (version < 2.0) {
			Console.warn("Updating data.yml to version 2.0...");
			if (!backupLegacyData("update_2_backup_")) {
				Bukkit.getPluginManager().disablePlugin(plugin);
				return;
			}
			
			data.set("version", 2.0);
			ConfigurationSection csPlayers = data.getConfigurationSection("players");
			if (csPlayers != null) {
				Map<String, Double> time = new HashMap<>();
				for (String key: csPlayers.getKeys(false)) {
					time.put(key, data.getDouble("players." + key));
				}
				for (Entry<String, Double> entry: time.entrySet()) {
					String uuid = entry.getKey();
					double value = entry.getValue();
					data.set("players." + uuid + ".time", value);
					data.set("players." + uuid + ".logged_in_flight", false);
					data.set("players." + uuid + ".trail", "");
				}	
			}
			List<String> disco = data.getStringList("flight_disconnect");
			if (disco != null) {
				for (String uuid: disco) {
					data.set("players." + uuid + ".logged_in_flight", true);
				}
			}
			data.set("flight_disconnect", null);
			saveData();
			
		} else if (version < 3.0) {
			if (!backupLegacyData("update_3_backup_")) {
				Bukkit.getPluginManager().disablePlugin(plugin);
				return;
			}
			data.set("version", 3.0);
			saveData();
		} else if (version < 4.0) {
			if (!backupLegacyData("update_4_backup_")) {
				Bukkit.getPluginManager().disablePlugin(plugin);
				return;
			}
			data.set("version", 4.0);
			saveData();
		}
	}
	
	/**
	 * Create a data backup from legacy TempFly version when updating.
	 * @return
	 */
	private boolean backupLegacyData(String file) {
		Console.info("Creating a backup of your data file...");
		File f = new File(tempfly.getDataFolder(), file + String.valueOf(new Random().nextInt(99999)) + ".yml");
		try {
			data.save(f);
		} catch (Exception e) {
			Console.severe("-----------------------------------", "There was an error while trying to backup the data file", "For your safety the plugin will disable. Please contact the tempfly developer.");
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	
	public void stageChange(DataPointer pointer, Object data) {
		stageChange(pointer, data, null);
	}
	/**
	 * Stage a change to be sent to the database later.
	 * @param pointer The type and path of the data
	 * @param data the data.
	 */
	public void stageChange(DataPointer pointer, Object data, DataFileHolder fileHolder) {
		DataValue value = pointer.getValue();
		String[] path = pointer.getPath();
		changes.put(pointer, new StagedChange(value, data, path, fileHolder));
	}
	
	public boolean isStaged(DataPointer pointer) {
		return changes.containsKey(pointer);
	}
	
	/**
	 * Commit all changes to the database or yaml if applicable.
	 * Adds all the staged changes to the manual batch and runs the async batch collector.
	 */
	public void commitAll() {
		manualCommit.clear();
		manualCommit.addAll(changes.keySet());
		if (manualCommit.size() == 0) {
			return;
		}
		executor.submit(() -> {
			executeCommit();
		});
	}
	
	/**
	 * Collects StagedChanges using the pointers collected in the manual batch and sends data to the database.
	 */
	private void executeCommit() {
		List<StagedChange> commit = new ArrayList<>();
		
		List<DataPointer> pl = new ArrayList<>();
		pl.addAll(manualCommit);
		manualCommit.clear();
		
		for (DataPointer pointer: pl) {
			StagedChange change = changes.get(pointer);
			if (change != null) {
				commit.add(change);
				changes.remove(pointer);
			}
		}	
		
		if (commit.size() == 0) {
			return;
		}
		List<DataFileHolder> altered = new ArrayList<>();
		for (StagedChange change: commit) {
			DataFileHolder holder = change.getValue().getTable().getDataFileHolder(tempfly);
			if (!altered.contains(holder)) {
				altered.add(holder);
			}
			try {
				setValue(change, holder.forceYaml());
			} catch (SQLException e) {
				// SQL-based exceptions, since these are more detailed.
				e.printStackTrace();
			} catch (Exception e) {
				// All other exceptions, for MongoDB or YAML.
				e.printStackTrace();
			}
		}
		for (DataFileHolder holder: altered) {
			if (!hasDatabaseEnabled() || holder.forceYaml()) {
				holder.saveData();
			}
		}
	}
	
	/**
	 * Manually add data pointers to the next manual commit and run the async batch collector.
	 * @param pointers
	 */
	public void manualCommit(DataPointer... pointers) {
		manualCommit.addAll(Arrays.asList(pointers));
		executor.submit(() -> {
			executeCommit();
		});
	}
	
	/**
	 * Drop ALL changes, resets data back to the original state unless it has been commited. 
	 */
	public void dropChanges() {
		manualCommit.clear();
		changes.clear();
	}
	
	/**
	 * Get a value from the table
	 * @param value
	 * @param row
	 * @return
	 * @throws SQLException 
	 * @throws DataFormatException
	 */
	public Object getValue(DataPointer pointer) throws SQLException {
		DataValue value = pointer.getValue();
		String[] path = pointer.getPath();
		
		// Let's first check for changes.
		StagedChange change = changes.get(pointer);
		if (change != null) {
			return change.getData();
		}
		
		// Since there was, let's get the data from the correct storage.
		if (hasSqlEnabled() || hasSqlEnabled()) {
			// MySQL or SQLite reading operation
			DataTable table = value.getTable();
			String statement = "SELECT " + value.getSqlColumn() + " FROM " + table.getSqlTable()
					+ " WHERE " + table.getPrimaryKey() + " = ?";
			try (PreparedStatement st = prepareStatement(statement)) {
				st.setString(1, path[0]);
				ResultSet sresult = st.executeQuery();
				if (sresult.next()) {
					return sresult.getObject(value.getSqlColumn());
				}
			}
		} else if (hasMongoEnabled()) {
			// MongoDB reading operation via handler
			try {
				String collectionName = value.getTable().getSqlTable();
				String primaryKey = value.getTable().getPrimaryKey();
				String column = value.getSqlColumn();
				
				// Call findDocument via reflection
				java.lang.reflect.Method findMethod = mongoHandler.getClass().getMethod("findDocument", 
					String.class, String.class, String.class);
				Object document = findMethod.invoke(mongoHandler, collectionName, primaryKey, path[0]);
				
				if (document != null) {
					// Call get method on Document via reflection
					java.lang.reflect.Method getMethod = document.getClass().getMethod("get", Object.class);
					return getMethod.invoke(document, column);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			// YAML reading operation
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < value.getYamlPath().length; i++) {
				String s = value.getYamlPath()[i];
				if (sb.length() > 0) {
					sb.append(".");
				}
				sb.append(s);
				// Insert path elements between yamlPath segments
				if (i < path.length) {
					sb.append(".").append(path[i]);
				}
			}
			return value.getTable().getDataFileHolder(tempfly).getDataConfiguration().get(sb.toString());
		}
		return null;
	}
	
	public PreparedStatement prepareStatement(String statement) {
		if (!hasSqlEnabled() && !hasSqliteEnabled()) {
			return null;
		}
		try {
			if (migrationConnection != null && !migrationConnection.isClosed())
				return migrationConnection.prepareStatement(statement);

			// Check which SQL method
			if (hasSqlEnabled()) {
				return dataSource.getConnection().prepareStatement(statement);
			} else if (hasSqliteEnabled()) {
				// Get connection from SQLite handler via reflection
				java.lang.reflect.Method getConnectionMethod = sqliteHandler.getClass().getMethod("getConnection");
				Connection conn = (Connection) getConnectionMethod.invoke(sqliteHandler);
				// Check if connection is valid before using it
				if (conn == null || conn.isClosed()) {
					return null;
				}
				return conn.prepareStatement(statement);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public Object getOrDefault(DataPointer pointer, Object def) {
		Object object;
		try {
			object = getValue(pointer);
		} catch (SQLException e) {
			e.printStackTrace();
			return def;
		}
		return object == null ? def : object;
	}
	
	public Map<String, Object> getValues(DataTable table, String yamlPathTo, String row, String... extra) {
		return getValues(table, null, yamlPathTo, row, extra);
	}
	
	/**
	 * Get all values from the table for the given row.
	 * Assumes the row is path to the ConfigurationSection in yaml
	 * @param value
	 * @param row
	 * @return
	 */
	
	public Map<String, Object> getValues(DataTable table, DataFileHolder fileHolder, String yamlPathTo, String row, String... extra) {
		Map<String, Object> values = new HashMap<>();

		// Let's first see which storage we are using.
		if ((hasSqlEnabled() || hasSqliteEnabled()) && (fileHolder == null || !fileHolder.forceYaml())) {
			// MySQL or SQLite reading operation
			// Will implement after remaining code is done.
			String statement = "SELECT * FROM " + table.getSqlTable()
					+ " WHERE " + table.getPrimaryKey() + " = ?";
			try (PreparedStatement st = prepareStatement(statement)) {
				st.setString(1, row);
				ResultSet result = st.executeQuery();
				if (result.next()) {
					// Get every column from results
					java.sql.ResultSetMetaData rsmd = result.getMetaData();
					int columnCount = rsmd.getColumnCount();

					for (int i = 1; i <= columnCount; i++) {
						String columnName = rsmd.getColumnName(i);
						// We'll skip the primary key, which is usually the UUID
						if (!columnName.equals(table.getPrimaryKey())) {
							values.put(columnName, result.getObject(columnName));
						}
					}
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		} else if (hasMongoEnabled() && (fileHolder == null || !fileHolder.forceYaml())) {
			// MongoDB reading operation via handler
			try {
				String collectionName = table.getSqlTable();
				String primaryKey = table.getPrimaryKey();
				
				// Call findDocument via reflection
				java.lang.reflect.Method findMethod = mongoHandler.getClass().getMethod("findDocument", 
					String.class, String.class, String.class);
				Object document = findMethod.invoke(mongoHandler, collectionName, primaryKey, row);
				
				if (document != null) {
					// Call keySet method on Document via reflection
					java.lang.reflect.Method keySetMethod = document.getClass().getMethod("keySet");
					@SuppressWarnings("unchecked")
					java.util.Set<String> keySet = (java.util.Set<String>) keySetMethod.invoke(document);
					
					// Call get method for each key
					java.lang.reflect.Method getMethod = document.getClass().getMethod("get", Object.class);
					for (String key : keySet) {
						if (!key.equals("_id") && !key.equals(primaryKey)) {
							values.put(key, getMethod.invoke(document, key));
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			// YAML reading operation
			FileConfiguration df = fileHolder == null ?
					table.getDataFileHolder(tempfly).getDataConfiguration()
					: fileHolder.getDataConfiguration();
			StringBuilder pathBuilder = new StringBuilder(yamlPathTo).append(".").append(row);
			for (String e : extra) {
				pathBuilder.append(".").append(e);
			}
			String path = pathBuilder.toString();
			ConfigurationSection csValues = df.getConfigurationSection(path);
			if (csValues != null) {
				for (String key: csValues.getKeys(false)) {
					values.put(key, csValues.get(key));
				}
			}
		}
		
		
		// Apply any changes that we've staged locally.
		for (StagedChange local: changes.values()) {
			if (local.comparePathPartial(row)) {
				values.put(local.getPath()[local.getPath().length-1], local.getData());
			}
		}	
		return values;
	}
	
	public void setValue(StagedChange change, boolean forceYaml) throws SQLException {
		DataValue value = change.getValue();
		String[] path = change.getPath();
		
		// First let's check which storage we are using.
		if (hasSqlEnabled() || hasSqliteEnabled() && !forceYaml) {
			// MySQL writing operation
			PreparedStatement st = prepareStatement(
			"UPDATE " + value.getTable().getSqlTable() + " SET " + value.getSqlColumn()
				+ " = ? WHERE " + value.getTable().getPrimaryKey() + " = ?");
			
			// Safety check: if connection is closed, try to reconnect and save
			if (st == null && hasSqliteEnabled()) {
				try {
					// Reconnect via handler
					java.lang.reflect.Method getConnectionMethod = sqliteHandler.getClass().getMethod("getConnection");
					Connection conn = (Connection) getConnectionMethod.invoke(sqliteHandler);
					if (conn != null && !conn.isClosed()) {
						st = conn.prepareStatement("UPDATE " + value.getTable().getSqlTable() + " SET " + value.getSqlColumn()
							+ " = ? WHERE " + value.getTable().getPrimaryKey() + " = ?");
					}
				} catch (Exception e) {
					Console.warn("Failed to reconnect for saving data: " + e.getMessage());
				}
			}
			
		Class<?> type = value.getType();
			if (type.equals(Boolean.TYPE)) {
				st.setBoolean(1, (boolean) change.getData());
			} else if (type.equals(Double.TYPE)) {
				st.setDouble(1, (double) change.getData());
			} else if (type.equals(String.class)) {
				st.setString(1, (String) change.getData());
			} else if (type.equals(Long.TYPE)) {
				st.setLong(1, (long) change.getData());
			}
			st.setString(2, path[0]);
			st.execute();
			st.close();

		} else if (hasMongoEnabled() && !forceYaml) {
			// MongoDB writing operation via handler
			try {
				String collectionName = value.getTable().getSqlTable();
				String primaryKey = value.getTable().getPrimaryKey();
				String field = value.getSqlColumn();
				
				// Call upsertField via reflection
				java.lang.reflect.Method upsertMethod = mongoHandler.getClass().getMethod("upsertField", 
					String.class, String.class, String.class, String.class, Object.class);
				upsertMethod.invoke(mongoHandler, collectionName, primaryKey, path[0], field, change.getData());
			} catch (Exception e) {
				e.printStackTrace();
			}

		} else  {
			// YAML writing operation
			int index = 0;
			StringBuilder sb = new StringBuilder();
			for (String s: value.getYamlPath()) {
				if (sb.length() > 0) {
					sb.append(".");
				}
				sb.append(s);
				if (path.length > index) {
					sb.append(".").append(path[index]);
				}
				index++;
			}
			FileConfiguration yaml = change.getFileHolder() == null ?
					value.getTable().getDataFileHolder(tempfly).getDataConfiguration()
					: change.getFileHolder().getDataConfiguration();
			if (!yaml.contains(sb.toString())) {
				yaml.createSection(sb.toString());
			}
			yaml.set(sb.toString(), change.getData());
		}
	}

	
	public static enum DataTable {
		TEMPFLY_DATA("uuid");
		
		private String primary;
		
		private DataTable(String primary) {
			this.primary = primary;
		}
		
		public String getPrimaryKey() {
			return primary;
		}
		
		public DataFileHolder getDataFileHolder(TempFly tempfly) {
			switch (this) {
			case TEMPFLY_DATA:
				return tempfly.getDataBridge();
			default:
				return null;
			}
		}
		
		public String getSqlTable() {
			switch (this) {
			case TEMPFLY_DATA:
				return "tempfly_data";
			default:
				return null;
			}
		}
	}
	
	public static enum DataValue {
		PLAYER_TIME(
				DataTable.TEMPFLY_DATA,
				Double.TYPE,
				"player_time",
				new String[] {"players", "time"},
				false),
		PLAYER_FLIGHT_LOG(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"logged_in_flight",
				new String[] {"players", "logged_in_flight"},
				false),
		PLAYER_COMPAT_FLIGHT_LOG(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"compat_logged_in_flight",
				new String[] {"players", "compat_logged_in_flight"},
				false),
		PLAYER_DAMAGE_PROTECTION(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"damage_protection",
				new String[] {"players", "damage_protection"},
				false),
		PLAYER_DAILY_BONUS(
				DataTable.TEMPFLY_DATA,
				Long.TYPE,
				"last_daily_bonus",
				new String[] {"players", "last_daily_bonus"},
				false),
		PLAYER_TRAIL(
				DataTable.TEMPFLY_DATA,
				String.class,
				"trail",
				new String[] {"players", "trail"},
				false),
		PLAYER_INFINITE(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"infinite",
				new String[] {"players", "infinite"},
				false),
		PLAYER_BYPASS(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"bypass",
				new String[] {"players", "bypass"},
				false),
		PLAYER_SPEED(
				DataTable.TEMPFLY_DATA,
				Double.TYPE,
				"speed",
				new String[] {"players", "speed"},
				false),
		PLAYER_DISPLAY_VISIBLE(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"display_visible",
				new String[] {"players", "display_visible"},
				false),
		PLAYER_INFINITE_FIRST_USE(
				DataTable.TEMPFLY_DATA,
				Boolean.TYPE,
				"infinite_first_use",
				new String[] {"players", "infinite_first_use"},
				false);
		
		private DataTable table;
		private Class<?> type;
		private String sqlColumn;
		
		private String[]
		yamlPath;
		private boolean dynamic;
		
		private DataValue(DataTable table, Class<?> type, String sqlColumn, String[] yamlPath, boolean dynamic) {
			this.table = table;
			this.type = type;
			this.sqlColumn = sqlColumn;
			this.yamlPath = yamlPath;
			this.dynamic = dynamic;
		}
		
		public DataTable getTable() {
			return table;
		}
		
		public Class<?> getType() {
			return type;
		}
		
		public String getSqlColumn() {
			return sqlColumn;
		}
		
		public String[] getYamlPath() {
			return yamlPath;
		}
		
		public boolean hasDynamicPath() {
			return dynamic;
		}
	}
	
	public static class StagedChange {
		DataValue value;
		String[] path;
		Object data;
		DataFileHolder fileHolder;
		
		public StagedChange(DataValue value, Object data, String[] path, DataFileHolder fileHolder) {
			this.value = value;
			this.path = path;
			this.data = data;
			this.fileHolder = fileHolder;
		}

		public DataPointer getPointer() {
			return DataPointer.of(value, path);
		}

		public DataFileHolder getFileHolder() {
			return fileHolder;
		}
		
		public DataValue getValue() {
			return value;
		}
		
		public String[] getPath() {
			return path;
		}
		
		public Object getData() {
			return data;
		}
		
		public boolean comparePathPartial(String... path) {
			for (int index = 0; path.length > index; index++) {
				if (this.path.length <= index || !path[index].equals(this.path[index])) {
					return false;
				}
			}
			return true;
		}
		
	}

	public void openMigrationConnection() throws SQLException {
		// Only open migration connection for SQL databases (MySQL/SQLite)
		// MongoDB doesn't need a separate connection
		if (hasSqlEnabled() || hasSqliteEnabled()) {
			if (migrationConnection == null || migrationConnection.isClosed()) {
				if (hasSqlEnabled()) {
					migrationConnection = dataSource.getConnection();
				} else if (hasSqliteEnabled()) {
					// Get connection from SQLite handler via reflection
					try {
						java.lang.reflect.Method getConnectionMethod = sqliteHandler.getClass().getMethod("getConnection");
						migrationConnection = (Connection) getConnectionMethod.invoke(sqliteHandler);
					} catch (Exception e) {
						throw new SQLException("Failed to get SQLite connection for migration", e);
					}
				}
			}
		}
	}

	public void closeMigrationConnection() {
		if (migrationConnection != null) {
			try { migrationConnection.close(); }
			catch (SQLException e) { e.printStackTrace(); }
		}
	}

	// We don't want to leave any data leaks on shutdown or disable, so let's ensure any database connections are closed.
	// Closure will check each storage in it's priority order and close whichever is active.
	public void shutdown() {
		// SYNCHRONOUSLY commit any remaining changes before cleanup - we can't use async during shutdown!
		// If we use the async commitAll(), the executor might try to execute after the MongoDB client closes.
		executeCommit();
		
		// Now shut down the executor and wait for any lingering tasks
		executor.shutdown(); // Stop accepting new tasks
		try {
			// Wait up to 10 seconds for existing tasks to complete
			if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
				Console.warn("Executor did not terminate in time, forcing shutdown...");
				executor.shutdownNow(); // Force shutdown
			}
		} catch (InterruptedException e) {
			Console.warn("Executor shutdown interrupted!");
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		// MySQL cleanup is handled by MySQL itself via connection pooling, which means we don't need to do anything for it.

		// MongoDB cleanup via handler
		if (mongoHandler != null) {
			try {
				java.lang.reflect.Method closeMethod = mongoHandler.getClass().getMethod("close");
				closeMethod.invoke(mongoHandler);
			} catch (Exception e) {
				Console.warn("Could not close MongoDB connection cleanly! Reason: " + e.getMessage());
			}
		}

		// SQLite cleanup via handler
		if (sqliteHandler != null) {
			try {
				java.lang.reflect.Method closeMethod = sqliteHandler.getClass().getMethod("close");
				closeMethod.invoke(sqliteHandler);
			} catch (Exception e) {
				Console.warn("Could not close SQLite connection cleanly! Reason: " + e.getMessage());
			}
		}

		// YAML cleanup isn't necessary as there's no persistent connection. Simply committing remaining changes is sufficient.

		// Migration cleanup
		closeMigrationConnection();
	}
	@Override
	public File getDataFile() {
		return dataf;
	}

	@Override
	public FileConfiguration getDataConfiguration() {
		return data;
	}

	@Override
	public void setDataFile(File file) {
		this.dataf = file;
	}

	@Override
	public void setDataConfiguration(FileConfiguration data) {
		this.data = data;
	}
	
	@Override
	public void saveData() {
		try { data.save(dataf); } catch (Exception e) {e.printStackTrace();}
	}

}



