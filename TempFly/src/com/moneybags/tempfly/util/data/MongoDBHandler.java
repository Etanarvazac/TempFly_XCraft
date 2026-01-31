package com.moneybags.tempfly.util.data;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.bson.Document;
import org.bukkit.configuration.ConfigurationSection;

import com.moneybags.tempfly.util.Console;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

/**
 * Handler for MongoDB database operations.
 * This class is loaded dynamically via reflection to avoid class loading issues
 * when MongoDB drivers are not available (LITE version).
 */
public class MongoDBHandler {
	
	private MongoClient mongoClient;
	private MongoDatabase mongoDatabase;
	
	/**
	 * Connect to MongoDB database using the provided configuration.
	 * @param section Configuration section containing MongoDB settings
	 * @return true if connection successful, false otherwise
	 */
	public boolean connect(ConfigurationSection section) {
		try {
			String host = section.getString("host");
			int port = section.getInt("port");
			String name = section.getString("name");
			String user = section.getString("user");
			String pass = section.getString("pass");
			String authDb = section.getString("auth_database", "admin");

			// URL encode credentials if they exist
			String encodedUser = null;
			String encodedPass = null;
			if (user != null && !user.isEmpty()) {
				encodedUser = URLEncoder.encode(user, StandardCharsets.UTF_8.toString());
			}
			if (pass != null && !pass.isEmpty()) {
				encodedPass = URLEncoder.encode(pass, StandardCharsets.UTF_8.toString());
			}

			// Build the connection string
			String connectionString;
			if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
				// Authenticated connection string
				connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
						encodedUser, encodedPass, host, port, name, authDb);
			} else {
				// Non-authenticated connection string
				connectionString = String.format("mongodb://%s:%d/%s", host, port, name);
			}

			// Create the client and test connection
			this.mongoClient = MongoClients.create(connectionString);
			this.mongoDatabase = mongoClient.getDatabase(name);
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
	
	/**
	 * Initialize MongoDB collections and indexes.
	 */
	public void initialize() {
		try {
			String collectionName = "tempfly_data";
			
			// Check if collection exists
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

			// Create index on UUID field for faster lookups
			MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
			collection.createIndex(new Document("uuid", 1));

			Console.info("MongoDB setup complete.");
		} catch (Exception e) {
			Console.severe("MongoDB setup failed.");
			e.printStackTrace();
		}
	}
	
	/**
	 * Get a MongoDB collection by name.
	 * @param collectionName Name of the collection
	 * @return MongoCollection
	 */
	public MongoCollection<Document> getCollection(String collectionName) {
		return mongoDatabase.getCollection(collectionName);
	}
	
	/**
	 * Find a document in a collection.
	 * @param collectionName Collection name
	 * @param primaryKey Primary key field name
	 * @param keyValue Primary key value
	 * @return Document or null if not found
	 */
	public Document findDocument(String collectionName, String primaryKey, String keyValue) {
		MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
		Document filterDoc = new Document(primaryKey, keyValue);
		return collection.find(filterDoc).first();
	}
	
	/**
	 * Update or insert a document in a collection.
	 * @param collectionName Collection name
	 * @param primaryKey Primary key field name
	 * @param keyValue Primary key value
	 * @param field Field to update
	 * @param value Value to set
	 */
	public void upsertField(String collectionName, String primaryKey, String keyValue, String field, Object value) {
		MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
		Document updateDoc = new Document("$set", new Document(field, value));
		Document filterDoc = new Document(primaryKey, keyValue);
		collection.updateOne(filterDoc, updateDoc, new UpdateOptions().upsert(true));
	}
	
	/**
	 * Close the MongoDB connection.
	 */
	public void close() {
		if (mongoClient != null) {
			try {
				mongoClient.close();
				Console.info("MongoDB connection closed.");
			} catch (Exception e) {
				Console.warn("Could not close MongoDB connection cleanly! Reason: " + e.getMessage());
			}
		}
	}
	
	/**
	 * Check if the handler is connected.
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		return mongoClient != null;
	}
}
