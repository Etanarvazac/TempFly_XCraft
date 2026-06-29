package com.moneybags.tempfly.hook.factions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.command.TempFlyCommand;
import com.moneybags.tempfly.fly.result.FlightResult;
import com.moneybags.tempfly.fly.result.ResultAllow;
import com.moneybags.tempfly.fly.result.ResultDeny;
import com.moneybags.tempfly.fly.result.FlightResult.DenyReason;
import com.moneybags.tempfly.hook.HookManager.Genre;
import com.moneybags.tempfly.user.FlightUser;
import com.moneybags.tempfly.util.Console;
import com.moneybags.tempfly.util.V;
import com.moneybags.tempfly.hook.TerritoryHook;
import com.moneybags.tempfly.hook.TerritoryWrapper;
import com.moneybags.tempfly.hook.factions.FactionRequirement.PowerContext;

public abstract class FactionsHook extends TerritoryHook implements Listener {

	private Set<FactionRelation> damagePower;
	private Set<FactionRelation> damageIllegal;
	private String
	requireIllegalTerritory,
	requirePowerSelf,
	requirePowerForeign,
	requirePowerAllied,
	requireFactionSelf;
	
	private Map<FactionRelation, FactionRequirement> baseRequirements;
	private Map<String, FactionRoleSettings> roleRequirements;
	
	public static enum FactionRelation {
		HOME(PowerContext.SELF),
		ENEMY(PowerContext.FOREIGN),
		NEUTRAL(PowerContext.FOREIGN),
		ALLIED(PowerContext.FOREIGN),
		WILDERNESS(PowerContext.FOREIGN);
		
		private PowerContext context;
		
		private FactionRelation(PowerContext context) {
			this.context = context;
		}
		
		public PowerContext getPowerContext() {
			return context;
		}
	}
	
	public FactionsHook(TempFly tempfly) {
		super(tempfly);
	}
	
	public void loadValues() {
		Console.debug("", "---- Loading Factions Settings ----");
		FileConfiguration config = getConfig();
		
		damagePower = new HashSet<>();
		damageIllegal = new HashSet<>();
		baseRequirements = new HashMap<>();
		roleRequirements = new HashMap<>();
		
		ConfigurationSection csFallPower = config.getConfigurationSection("fall_damage.power");
		if (csFallPower != null) {
			for (String key: csFallPower.getKeys(false)) {
				FactionRelation relation;
				try {
					relation = FactionRelation.valueOf(key.toUpperCase());
				} catch (IllegalArgumentException e) {
					Console.warn("A faction relationship defined in the config does not exist: (" + key + ")");
					continue;
				}
				if (csFallPower.getBoolean(key)) {
					damagePower.add(relation);
				}
			}
		}
		
		ConfigurationSection csFallDisabled = config.getConfigurationSection("fall_damage.disabled");
		if (csFallDisabled != null) {
			for (String key: csFallDisabled.getKeys(false)) {
				FactionRelation relation;
				try {
					relation = FactionRelation.valueOf(key.toUpperCase());
				} catch (IllegalArgumentException e) {
					Console.warn("A faction relationship defined in the config does not exist: (" + key + ")");
					continue;
				}
				if (csFallDisabled.getBoolean(key)) {
					damageIllegal.add(relation);
				}
			}
		}
		
		String name = getConfigName();
		requireIllegalTerritory 	= V.st(config, "language.requirements.illegal_territory", name);
		requirePowerSelf			= V.st(config, "language.requirements.power_self", name);
		requirePowerForeign			= V.st(config, "language.requirements.power_foreign", name);
		requirePowerAllied			= V.st(config, "language.requirements.power_allied", name);
		requireFactionSelf			= V.st(config, "language.requirements.faction_self", name);
		
		ConfigurationSection csBase = config.getConfigurationSection("base_requirements");
		if (csBase != null) {
			for (String key: csBase.getKeys(false)) {
				FactionRelation relation;
				try {
					relation = FactionRelation.valueOf(key.toUpperCase());
				} catch (IllegalArgumentException e) {
					Console.warn("A faction relationship defined in the config does not exist: (" + key + ")");
					continue;
				}
				baseRequirements.put(relation, new FactionRequirement(this, relation, csBase.getConfigurationSection(key), true));
			}
		}
		
		for (FactionRelation relation: FactionRelation.values()) {
			if (!baseRequirements.containsKey(relation)) {
				Console.debug("--! Base requirements does not contain settings for: " + relation);
				baseRequirements.put(relation, new FactionRequirement(this, relation));
			}
		}
		
		ConfigurationSection csRole = config.getConfigurationSection("role_requirements");
		if (csRole != null) {
			for (String key: csRole.getKeys(false)) {
				roleRequirements.put(key.toUpperCase(), new FactionRoleSettings(this, key.toUpperCase(), csRole.getConfigurationSection(key)));
			}
		}
		Console.debug("---- Done Factions Settings ----");
	}
	
	public FactionRequirement getBaseRequirement(FactionRelation relation) {
		return baseRequirements.get(relation);
	}
	
	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		FactionWrapper to = getFactionAt(e.getPlayer().getLocation());
		if (to == null) {
			return;
		}
		onFactionEnter(e.getPlayer(), to, null);
	}
	
	@EventHandler
	public void onTeleport(PlayerTeleportEvent e) {
		if (e.getFrom().getChunk().equals(e.getTo().getChunk())) {
			return;
		}
		FactionWrapper from = getFactionAt(e.getFrom());
		FactionWrapper to = getFactionAt(e.getTo());
		if (from.equals(to)) {
			return;
		}
		
		onFactionExit(e.getPlayer());
		onFactionEnter(e.getPlayer(), to, null);
	}
	
	@EventHandler
	public void onRespawn(PlayerRespawnEvent e) {
		if (e.getPlayer().getLocation().getChunk().equals(e.getRespawnLocation().getChunk())) {
			return;
		}
		FactionWrapper from = getFactionAt(e.getPlayer().getLocation());
		FactionWrapper to = getFactionAt(e.getRespawnLocation());
		if (from.equals(to)) {
			return;
		}
		
		onFactionExit(e.getPlayer());
		onFactionEnter(e.getPlayer(), to, null);
	}
	
	@EventHandler
	public void onMove(PlayerMoveEvent e) {
		if (e.getFrom().getChunk().equals(e.getTo().getChunk())) {
			return;
		}
		FactionWrapper from = getFactionAt(e.getFrom());
		FactionWrapper to = getFactionAt(e.getTo());
		if (from.equals(to)) {
			return;
		}
		
		onFactionExit(e.getPlayer());
		onFactionEnter(e.getPlayer(), to, null);
	}
	
	public void onLandClaimed(Chunk chunk, FactionWrapper faction) {
		Console.debug("--| Claimed land");
		for (Entity ent: chunk.getEntities()) {
			if (!(ent instanceof Player)) {
				continue;
			}
			onFactionEnter((Player) ent, faction.getRawTerritory(), ent.getLocation());
		}
	}
	
	public void onLandOverClaimed(Chunk chunk, FactionWrapper victim, FactionWrapper agressor) {
		Console.debug("--| Land overclaimed from (" + getFactionName(victim));
		for (Entity ent: chunk.getEntities()) {
			if (!(ent instanceof Player)) {
				continue;
			}
			onFactionExit((Player) ent);
			onFactionEnter((Player) ent, agressor.getRawTerritory(), ent.getLocation());
		}
	}
	
	public void onLandUnclaimed(Chunk chunk) {
		Console.debug("--| Unclaimed land");
		for (Entity ent: chunk.getEntities()) {
			if (!(ent instanceof Player)) {
				continue;
			}
			onFactionExit((Player) ent);
			onFactionEnter((Player) ent, getFactionAt(ent.getLocation()).getRawTerritory(), ent.getLocation());
		}
	}
	
	public void onPlayerJoinFaction(Player p, FactionWrapper faction) {
		for (Player player: getOnlineMembers(faction)) {
			if (p.equals(player)) {
				continue;
			}
			evaluate(player);
		}
		evaluate(p);
	}
	
	public List<Player> getOnlineMembers(FactionWrapper faction) {
		List<Player> players = new ArrayList<>();
		for (UUID playerId: getAllMembers(faction)) {
			Player p = Bukkit.getPlayer(playerId);
			if (p == null) {
				continue;
			}
			players.add(p);
		}
		return players;
	}
	
	public void onPlayerLeaveFaction(Player p, FactionWrapper faction) {
		for (Player player: getOnlineMembers(faction)) {
			if (p.equals(player)) {
				continue;
			}
			evaluate(player);
		}
		evaluate(p);
	}
	
	public void onFactionDisband(FactionWrapper faction) {
		List<UUID> players = Arrays.asList(getAllMembers(faction));
		
		Bukkit.getScheduler().runTask(tempfly, () -> {
			for (UUID playerId: players) {
				Player p = Bukkit.getPlayer(playerId);
				if (p == null) {
					continue;
				}
				evaluate(p);
			}
			for (Player p: getPlayersOn(faction)) {
				if (!p.isOnline() || players.contains(p.getUniqueId())) {
					continue;
				}
				evaluate(p);
				onTerritoryExit(p);
			}
		});
	}
	
	public void onPlayerPowerChange(Player p) {
		FactionWrapper faction = getFaction(p.getUniqueId());
		for (UUID id: getAllMembers(faction)) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				evaluate(player);
			}
		}
		for (Player player: getPlayersOn(faction)) {
			if (isMember(player.getUniqueId(), faction)) {
				continue;
			}
			evaluate(player);
		}
	}
	
	public void onFactionRelationshipChange(FactionWrapper faction, FactionWrapper target) {
		for (UUID id: getAllMembers(faction)) {
			Player p = Bukkit.getPlayer(id);
			if (p != null) {
				evaluate(p);
			}
		}
		for (UUID id: getAllMembers(target)) {
			Player p = Bukkit.getPlayer(id);
			if (p != null) {
				evaluate(p);
			}
		}
	}
	
	public void onFactionEnter(Player p, Object rawFaction, Location loc) {
		super.onTerritoryEnter(p, rawFaction, loc);
	}
	
	public void onFactionExit(Player p) {
		super.onTerritoryExit(p);
	}
	
	public FactionRelation getRelation(UUID playerId, FactionWrapper faction) {
		if (faction == null || isWilderness(faction)) {
			return FactionRelation.WILDERNESS;
		}
		if (isMember(playerId, faction)) {
			return FactionRelation.HOME;
		}
		if (isEnemy(playerId, faction)) {
			return FactionRelation.ENEMY;
		}
		if (isAllied(playerId, faction)) {
			return FactionRelation.ALLIED;
		}
		return FactionRelation.NEUTRAL;
	}
	
	@Override
	public FlightResult handleFlightInquiry(FlightUser user) {
		return checkFlightRequirements(user.getPlayer().getUniqueId(), user.getPlayer().getLocation());
	}
	
	@Override
	public FlightResult checkFlightRequirements(UUID playerId, Location loc) {
		return checkFlightRequirements(playerId, getFactionAt(loc));
	}
	
	@Override
	public FlightResult checkFlightRequirements(UUID playerId, TerritoryWrapper territory) {
		return checkFlightRequirements(playerId, (FactionWrapper) territory);
	}
	
	public FlightResult checkFlightRequirements(UUID playerId, FactionWrapper faction) {
		Console.debug("", "--- FactionsHook Check Flight Requirements A ---");
		if (!isEnabled()) {
			Console.debug("--|!!!> Hook is not enabled. Returning allowed flight!");
			return new ResultAllow(this, InquiryType.OUT_OF_SCOPE, V.requirePassDefault);
		}
		return checkRoleRequirements(playerId, faction);
	}
	
	public FlightResult checkRoleRequirements(UUID playerId, FactionWrapper faction) {
		String role = getRole(playerId, faction);
		if (V.debug) {
			Console.debug("", "--- FactionHook check role requirements ---", "--| Players Role: " + role);	
		}
		FactionWrapper home = getFaction(playerId);
		if (home == null || isWilderness(home)) {
			return new ResultDeny(DenyReason.REQUIREMENT, this, InquiryType.OUT_OF_SCOPE,
				requireFactionSelf.replaceAll("\\{FACTION_NAME}", getFactionName(faction)), false);
		}
		FactionRelation relation = getRelation(playerId, faction);
		Console.debug("--| Players relation to faction: " + relation);
		FactionRoleSettings roleSettings = roleRequirements.get(role.toUpperCase());
		Console.debug("--| role settings result: " + roleSettings);
		FactionRequirement requirement = roleSettings == null ? baseRequirements.get(relation) : roleSettings.getRequirement(relation);
		if (requirement == null) {
			Console.debug("requirement is null");
			return new ResultAllow(this, InquiryType.OUT_OF_SCOPE, V.requirePassDefault);
		}
		
		if (!requirement.isAllowed()) {
			return new ResultDeny(DenyReason.DISABLED_REGION, this, InquiryType.OUT_OF_SCOPE,
				requireIllegalTerritory.replaceAll("\\{FACTION_NAME}", getFactionName(faction))
					.replaceAll("\\{ROLE}", role),
				!damageIllegal.contains(relation));
		}
		Console.debug("--| flight is allowed in the land.");
	
		
		if (!requirement.isThresholdMet(PowerContext.SELF, getCurrentPower(home), getMaxPower(home))) {
			Console.debug("--| Power threshold for flight is not met A.");
			return new ResultDeny(DenyReason.REQUIREMENT, this, InquiryType.OUT_OF_SCOPE,
				requirePowerSelf.replaceAll("\\{POWER}",
					String.valueOf(requirement.getPowerFormatted(PowerContext.SELF, getCurrentPower(home), getMaxPower(home))))
					.replaceAll("\\{FACTION_NAME}", getFactionName(faction)),
				!damagePower.contains(relation));
		}
		
		boolean allied = isAllied(playerId, faction);
		
		if (!requirement.isThresholdMet(PowerContext.FOREIGN, getCurrentPower(faction), getMaxPower(faction))) {
			Console.debug("--| Power threshold for flight is not met B.");
			
			return new ResultDeny(DenyReason.REQUIREMENT, this, InquiryType.OUT_OF_SCOPE,
				(allied ? requirePowerAllied : requirePowerForeign)
					.replaceAll("\\{POWER}", String.valueOf(requirement.getPowerFormatted(PowerContext.FOREIGN,
						getCurrentPower(faction), getMaxPower(faction))))
					.replaceAll("\\{FACTION_NAME}", getFactionName(faction)),
				!damagePower.contains(relation));
		}
		
		Console.debug("--| Power threshold for flight is met.");
		return new ResultAllow(this, InquiryType.OUT_OF_SCOPE, V.requirePassDefault);
	}
	
	public abstract UUID[] getAllMembers(FactionWrapper faction);
	
	public abstract boolean isWilderness(FactionWrapper faction);
	
	public abstract double getCurrentPower(UUID playerId);
	
	public abstract double getMaxPower(UUID playerId);
	
	public abstract double getCurrentPower(FactionWrapper faction);
	
	public abstract double getMaxPower(FactionWrapper faction);
	
	public abstract FactionWrapper getFaction(UUID playerId);
	
	public abstract boolean isMember(UUID playerId, FactionWrapper faction);
	
	public abstract UUID getFactionOwner(FactionWrapper faction);
	
	public abstract String getRole(UUID playerId, FactionWrapper faction);
	
	public abstract boolean isEnemy(UUID playerId, FactionWrapper faction);
	
	public abstract boolean isAllied(UUID playerId, FactionWrapper faction);
	
	public abstract String getFactionIdentifier(Object rawFaction);
	
	public abstract String getFactionName(FactionWrapper faction);
	
	@Override
	public String getTerritoryIdentifier(Object rawTerritory) {
		return getFactionIdentifier(rawTerritory);
	}
	
	public abstract FactionWrapper getFactionAt(Location loc);
	
	@Override
	public TerritoryWrapper getTerritoryAt(Location loc) {
		return getFactionAt(loc);
	}
	
	public abstract boolean isInFactionLand(FactionWrapper faction, Location loc);
	
	@Override
	public boolean isInTerritory(TerritoryWrapper territory, Location loc) {
		return isInFactionLand((FactionWrapper) territory, loc);
	}
	
	@Override
	public void onTempflyReload() {
		super.onTempflyReload();
		
		baseRequirements.clear();
		roleRequirements.clear();
		loadValues();
		for (Player player: Bukkit.getOnlinePlayers()) {
			evaluate(player);
		}
	}
	
	@Override
	public boolean initializeHook() {
		loadValues();
		getTempFly().getServer().getPluginManager().registerEvents(this, getTempFly());
		
		return true;
	}
	
	@Override
	public boolean needsDataFile() {
		return false;
	}
	
	@Override
	public boolean forceYaml() {
		return true;
	}
	
	@Override
	public Genre getGenre() {
		return Genre.FACTIONS;
	}

	@Override
	public String getEmbeddedConfigName() {
		return "factions_preset_generic";
	}

	@SuppressWarnings("serial")
	@Override
	public Map<String, Class<? extends TempFlyCommand>> getCommands() {
		return new HashMap<>();
	}
	
	public FactionWrapper getFactionWrapper(Object rawFaction) {
		return (FactionWrapper) super.getTerritoryWrapper(rawFaction);
	}
	
	@Override
	public TerritoryWrapper createTerritoryWrapper(Object rawTerritory, TerritoryHook hook) {
		return new FactionWrapper(rawTerritory);
	}
}
