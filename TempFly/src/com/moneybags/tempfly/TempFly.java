package com.moneybags.tempfly;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.moneybags.tempfly.aesthetic.ActionBarAPI;
import com.moneybags.tempfly.aesthetic.ClipAPI;
import com.moneybags.tempfly.aesthetic.TitleAPI;
import com.moneybags.tempfly.aesthetic.particle.Particles;
import com.moneybags.tempfly.command.CommandManager;
import com.moneybags.tempfly.fly.FlightManager;
import com.moneybags.tempfly.gui.GuiManager;
import com.moneybags.tempfly.gui.pages.PageShop;
import com.moneybags.tempfly.gui.pages.PageTrails;
import com.moneybags.tempfly.hook.HookManager;
import com.moneybags.tempfly.time.TimeManager;
import com.moneybags.tempfly.util.AutoSave;
import com.moneybags.tempfly.util.Console;
import com.moneybags.tempfly.util.ParticleTask;
import com.moneybags.tempfly.util.V;
import com.moneybags.tempfly.util.data.DataBridge;
import com.moneybags.tempfly.util.data.Files;

public class TempFly extends JavaPlugin {
	
	// static abusers unite
	private static TempFlyAPI tfApi;
	public static TempFlyAPI getAPI() {
		return tfApi;
	}

	
	private HookManager hooks;
	private DataBridge bridge;
	private FlightManager flight;
	private TimeManager time;
	private CommandManager commands;
	private GuiManager gui;
	private BukkitTask autosave;
	
	public HookManager getHookManager() {
		return hooks;
	}
	
	public DataBridge getDataBridge() {
		return bridge;
	}
	
	public FlightManager getFlightManager() {
		return flight;
	}
	
	public TimeManager getTimeManager() {
		return time;
	}
	
	public CommandManager getCommandManager() {
		return commands;
	}

	public GuiManager getGuiManager() {
		return gui;
	}
	
	@Override
	public void onEnable() {
		Console.setLogger(this.getLogger());
		
		Files.createFiles(this);
		V.loadValues();
		
		try {
			this.bridge   = new DataBridge(this);
		} catch (SQLException e1) {
			e1.printStackTrace();
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		
		tfApi = new TempFlyAPI(this);
		this.flight   = new FlightManager(this);
		this.time     = new TimeManager(this);
		this.hooks    = new HookManager(this);
		this.commands = new CommandManager(this);
		this.gui      = new GuiManager(this);
		
		hooks.loadInternalGenres();
		initializeGui();
		initializeAesthetics();

		// bStats Metrics
		try {
			int pluginId = 29194; // This TempFly branch's bstats ID
			Class<?> metricsClass = Class.forName("com.moneybags.tempfly.Metrics");
			Object metrics = metricsClass.getConstructor(org.bukkit.plugin.Plugin.class, int.class).newInstance(this, pluginId);
			
			// Create MultiLineChart via reflection
			Class<?> multiLineChartClass = Class.forName("com.moneybags.tempfly.Metrics$MultiLineChart");
			Class<?> callableClass = Class.forName("java.util.concurrent.Callable");
			
			// Create callable lambda that returns the map
			Object callable = java.lang.reflect.Proxy.newProxyInstance(
				getClassLoader(),
				new Class<?>[] { callableClass },
				(proxy, method, args) -> {
					if (method.getName().equals("call")) {
						Map<String, Integer> map = new HashMap<>();
						map.put("servers", 1);
						map.put("players", Bukkit.getOnlinePlayers().size());
						return map;
					}
					return null;
				}
			);
			
			// Create MultiLineChart instance
			Object chart = multiLineChartClass.getConstructor(String.class, callableClass).newInstance("players_and_servers", callable);
			
			// Call addCustomChart
			java.lang.reflect.Method addChartMethod = metricsClass.getMethod("addCustomChart", Class.forName("com.moneybags.tempfly.Metrics$CustomChart"));
			addChartMethod.invoke(metrics, chart);
		} catch (ClassNotFoundException e) {
			// bStats not available (LITE version), skip silently
		} catch (Exception e) {
			Console.warn("Could not initialize bStats metrics: " + e.getMessage());
		}
		
		autosave = new AutoSave(bridge).runTaskTimerAsynchronously(this, V.save * 20 * 60, V.save * 20 * 60);
		
		// Support "/reload"
		for (Player p: Bukkit.getOnlinePlayers()) {
			flight.addUser(p);
		}
	}
	
	private void initializeAesthetics() {
		Particles.initialize(this);
		
		if (V.particles) {
			new ParticleTask(this).runTaskTimer(this, 0, 5);
		}
		if (V.actionBar) {
			ActionBarAPI.initialize(this);
		}
		
		TitleAPI.initialize(this);
		
		if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			Console.info("Initializing ClipAPI");
			ClipAPI.initialize(this);
		}
	}
	
	private void initializeGui() {
		PageTrails.initialize(this);
		PageShop.initialize(this);
	}
	
	@Override
	public void onDisable() {
		if (flight != null) {
			flight.onDisable();
		}
		if (gui != null) {
			gui.endAllSessions();
		}
		if (bridge != null) {
			bridge.commitAll();
			bridge.shutdown();
		}
	}
	
	/*
	 * Reload the plugin, this is the method called upon command /tempfly reload
	 */
	//TODO reload hooks
	public void reloadTempfly() {
		gui.endAllSessions();
		
		bridge.commitAll();
		Files.createFiles(this);
		V.loadValues();
		initializeAesthetics();
		initializeGui();
		
		flight.onTempflyReload();
		hooks.onTempflyReload();
		
		if (autosave != null) {
			autosave.cancel();
			autosave = new AutoSave(bridge).runTaskTimerAsynchronously(this, 0, V.save * 20 * 60);
		}
	}
	
	@Override
	public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("fly")) {
			return commands.getTabCompleter().onTabComplete(s, cmd, label, args);
		} else {
			return Arrays.asList(args);
		}
	}
	
}
