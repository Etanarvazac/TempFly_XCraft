package com.moneybags.tempfly.util.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.util.Console;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mysql.cj.jdbc.MysqlConnectionPoolDataSource;
import com.mysql.cj.jdbc.MysqlDataSource;


public class DataBridge implements DataFileHolder {

	private TempFly tempfly;
	private MysqlDataSource dataSource;
	private MongoClient mongoClient;
	private MongoDatabase mongoDatabase;
	private Connection sqliteConnection;
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
		return dataSource != null || mongoClient != null || sqliteConnection != null;
	}

	// Helpers for checking specific database types
	public boolean hasSqlEnabled() {
		return dataSource != null;
	}

	public boolean hasMongoEnabled() {
		return mongoClient != null;
	}

	public boolean hasSqliteEnabled() {
		return sqliteConnection != null;
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
			// Grab config values...
			String host = Files.config.getString("system.mongodb.host");
			int port = Files.config.getInt("system.mongodb.port");
			String name = Files.config.getString("system.mongodb.name");
			String user = Files.config.getString("system.mongodb.user");
			String pass = Files.config.getString("system.mongodb.pass");
			String authDb = Files.config.getString("system.mongodb.auth_database", "admin");

			// ...and encode user and pass, with special character handling...
			String encodedUser = URLEncoder.encode(user, StandardCharsets.UTF_8.toString());
			String encodedPass = URLEncoder.encode(pass, StandardCharsets.UTF_8.toString());

			// ...and now build the connection string.
			String connectionString;
			if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
				// Authenticated connection string
				connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
					encodedUser, encodedPass, host, port, name, authDb);
			} else {
				// Non-authenticated connection string
				connectionString = String.format("mongodb://%s:%d/%s", host, port, name);
			}


			mongoDatabase.listCollectionNames().first();
			Console.info("Successfully connected to MongoDB database!");
			return true;
		} catch (UnsupportedEncodingException e) {
			Console.severe("Failed to encode MongoDB credentials! Reason: Unsupported encoding.");
			e.printStackTrace();
			return false;
		} catch (Exception e) {
			Console.severe("Could not establish a connection to MongoDB database!");
			e.printStackTrace();
			return false;
		}
	}

	public boolean connectSqlite() {
		try {
			// Get path from config, defaulting to 'data.db' if not set.
			String filePath = Files.config.getString("system.sqlite.file_path", "data.db");

			// Let's create the file in the chosen location...
			File dbFile = new File(tempfly.getDataFolder(), filePath);

			// ..and double check for the parent directory.
			dbFile.getParentFile().mkdirs();

			// Now let's build JDBC for SQlite...
			String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

			// ...create the connection...
			this.sqliteConnection = java.sql.DriverManager.getConnection(jdbcUrl);

			// ...and verify connection to the database.
			if (!sqliteConnection.isValid(1)) {
				Console.severe("Could not establish a connection to SQLite database!");
				return false;
			} else {
				Console.info("Successfully connected to SQLite database!");
			}
			return true;
		} catch (SQLException e) {
			Console.severe("Could not establish a connection to SQLite database!");
			e.printStackTrace();
			return false;
		}
	}
	
	private void initMySqlDb() throws IOException, SQLException {
	    String setup;
	    try (InputStream in = tempfly.getResource("dbsetup.sql")) {
	        setup = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
	    } 
	    String[] queries = setup.split(";");
	    for (String query : queries) {
	        if (query.isBlank()) continue;
	        try (Connection conn = dataSource.getConnection();
	             PreparedStatement stmt = conn.prepareStatement(query)) {
	            stmt.execute();
	        } 
	    }
	    Console.info("§2MySQL setup complete.");
	}

	private void initMongoDB () {
		try {
			// MongoDB automatically creates collections, but we want to ensure it exists and index it
			String collectionName = "tempfly_data";
			
			// Now let's verify it. IF it fails, then we'll create one
			boolean collectionExists = false;
			for (String name : mongoDatabase.listCollectionNames()) {
				if (name.equals(collectionName)) {
					collectionExists = true;
					break;
				}
			}

			if (!collectionExists) {
				mongoDatabase.createCollection(collectionName);
			}

			// Create index of UUID's field for faster lookups
			MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
			collection.createIndex(new Document("uuid", 1));

			Console.info("MongoDB setup complete.");
		} catch (Exception e) {
			Console.severe("MongoDB setup failed.");
			e.printStackTrace();
		}
	}

	private void initSqlite() throws IOException, SQLException {
		String setup;
		try (InputStream in = tempfly.getResource("dbsetup.sql")) {
			setup = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
		}
		String[] queries = setup.split(";");
		for (String query : queries) {
			if (query.isBlank()) continue;
			try (PreparedStatement stmt = sqliteConnection.prepareStatement(query)) {
				stmt.execute();
			}
		}
		Console.info("SQLite setup complete.");
	}
	
	public DataBridge(TempFly tempfly) throws IOException, SQLException {
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
			// MongoDB reading operation
			String collectionName = value.getTable().getSqlTable();
			MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

			Document filterDoc = new Document(value.getTable().getPrimaryKey(), path[0]);
			Document mresult = collection.find(filterDoc).first();

			if (mresult != null) {
				return mresult.get(value.getSqlColumn());
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
		if (!hasSqlEnabled()) {
			return null;
		}
		try {
			if (migrationConnection != null && !migrationConnection.isClosed())
				return migrationConnection.prepareStatement(statement);

			// Check which SQL method
			if (hasSqlEnabled()) {
				return dataSource.getConnection().prepareStatement(statement);
			} else if (hasSqliteEnabled()) {
				return sqliteConnection.prepareStatement(statement);
			}
		} catch (SQLException e) {
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
			// MongoDB reading operation
			String collectionName = table.getSqlTable();
			MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

			Document filterDoc = new Document(table.getPrimaryKey(), row);
			Document result = collection.find(filterDoc).first();

			if (result != null) {
				// Iterate through keys and exclude the "_id" field
				for (String key : result.keySet()) {
					if (!key.equals("_id")) {
						values.put(key, result.get(key));
					}
				}
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
			// MongoDB writing operation
			// Set up the collection
			String collectionName = value.getTable().getSqlTable();
			MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

			// Now build the update document
			Document updateDoc = new Document("$set", new Document(value.getSqlColumn(), change.getData()));
			Document filterDoc = new Document(value.getTable().getPrimaryKey(), path[0]);

			// Send the update if it exists, otherwise insert a new document
			collection.updateOne(filterDoc, updateDoc, new com.mongodb.client.model.UpdateOptions().upsert(true));

		} else  {
			// YAML writing operation
			int index = 0;
			StringBuilder sb = new StringBuilder();
			for (String s: value.getYamlPath()) {
				sb.append(sb.length() > 0 ? "." : "" + s);
				if (path.length > index) {
					sb.append("." + path[index]);
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
					migrationConnection = sqliteConnection;
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

		// MongoDB cleanup
		if (mongoClient != null) {
			try {
				mongoClient.close();
				Console.info("MongoDB connection closed.");
			} catch (Exception e) {
				Console.warn("Could not close MongoDB connection cleanly! Reason: " + e.getMessage());
			}
		}

		// SQLite cleanup
		if (sqliteConnection != null) {
			try {
				sqliteConnection.close();
				Console.info("SQLite connection closed.");
			} catch (SQLException e) {
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



