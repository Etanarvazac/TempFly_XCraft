package com.moneybags.tempfly.util.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.util.Console;

/**
 * Handler for SQLite database operations.
 * This class is loaded dynamically via reflection to avoid class loading issues
 * when SQLite drivers are not available (LITE version).
 */
public class SQLiteHandler {
	
	private Connection sqliteConnection;
	private TempFly tempfly;
	private File dbFile; // Store for reconnection
	
	public SQLiteHandler(TempFly tempfly) {
		this.tempfly = tempfly;
	}
	
	/**
	 * Connect to SQLite database using the provided configuration.
	 * @param section Configuration section containing SQLite settings
	 * @return true if connection successful, false otherwise
	 */
	public boolean connect(ConfigurationSection section) {
		try {
			String fileName = section.getString("file", "tempfly.db");
			this.dbFile = new File(tempfly.getDataFolder(), fileName);
			
			// Ensure the parent directory exists
			if (!dbFile.getParentFile().exists()) {
				dbFile.getParentFile().mkdirs();
			}
			
			// Connect to SQLite database
			String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
			this.sqliteConnection = DriverManager.getConnection(url);
			
			Console.info("Successfully connected to SQLite database!");
			return true;
			
		} catch (SQLException e) {
			Console.severe("Could not establish a connection to SQLite database!");
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Check if a column exists in the SQLite table.
	 * @param tableName The table name
	 * @param columnName The column name to check
	 * @return true if column exists, false otherwise
	 * @throws SQLException If query fails
	 */
	private boolean columnExists(String tableName, String columnName) throws SQLException {
		try (PreparedStatement stmt = sqliteConnection.prepareStatement("PRAGMA table_info(" + tableName + ")")) {
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				if (rs.getString("name").equalsIgnoreCase(columnName)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Initialize SQLite tables using the dbsetup.sql resource file.
	 * Performs schema validation before executing ALTER TABLE statements.
	 * @throws IOException If resource file cannot be read
	 * @throws SQLException If SQL execution fails
	 */
	public void initialize() throws IOException, SQLException {
		String setup;
		try (InputStream in = tempfly.getResource("dbsetup.sql")) {
			setup = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
		}
		
		String[] queries = setup.split(";");
		for (String query : queries) {
			if (query.isBlank()) continue;
			
			// Handle ALTER TABLE statements with schema validation
			if (query.trim().toUpperCase().startsWith("ALTER TABLE")) {
				try {
					// Extract column name from ALTER TABLE ADD COLUMN statement
					// Format: ALTER TABLE table_name ADD COLUMN column_name ...
					String[] parts = query.trim().split("\\s+");
					if (parts.length >= 6 && "ADD".equalsIgnoreCase(parts[3]) && "COLUMN".equalsIgnoreCase(parts[4])) {
						String columnName = parts[5];
						if (columnExists("tempfly_data", columnName)) {
							continue;
						}
					}
				} catch (SQLException e) {
					Console.warn("Could not verify column existence: " + e.getMessage());
				}
			}
			
			try (PreparedStatement stmt = sqliteConnection.prepareStatement(query)) {
				stmt.execute();
			} catch (SQLException e) {
				// For ALTER TABLE, log as info since it might be expected to fail if column exists
				if (query.trim().toUpperCase().startsWith("ALTER TABLE")) {
					Console.info("SQLite migration skipped (column may already exist): " + e.getMessage());
				} else {
					Console.severe("Failed to execute SQLite setup query: " + query);
					e.printStackTrace();
					throw e; // Rethrow for non-ALTER statements
				}
			}
		}
		Console.info("SQLite setup complete.");
	}
	
	/**
	 * Get the SQLite connection for executing queries.
	 * Automatically reconnects if the connection is closed.
	 * @return Connection object
	 */
	public Connection getConnection() {
		try {
			// Check if connection is closed and reconnect if needed
			if (sqliteConnection == null || sqliteConnection.isClosed()) {
				Console.warn("SQLite connection was closed, reconnecting...");
				if (dbFile != null) {
					String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
					this.sqliteConnection = DriverManager.getConnection(url);
					Console.info("SQLite connection restored.");
				} else {
					Console.severe("SQLite connection lost and cannot auto-reconnect. Database file path unknown.");
					return null;
				}
			}
		} catch (SQLException e) {
			Console.severe("Failed to reconnect SQLite connection!");
			e.printStackTrace();
			return null;
		}
		return sqliteConnection;
	}
	
	/**
	 * Close the SQLite connection.
	 */
	public void close() {
		if (sqliteConnection != null) {
			try {
				sqliteConnection.close();
				Console.info("SQLite connection closed.");
			} catch (SQLException e) {
				Console.warn("Could not close SQLite connection cleanly! Reason: " + e.getMessage());
			}
		}
	}
	
	/**
	 * Check if the handler is connected.
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		try {
			return sqliteConnection != null && !sqliteConnection.isClosed();
		} catch (SQLException e) {
			return false;
		}
	}
}
