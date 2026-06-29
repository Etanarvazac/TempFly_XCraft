package com.moneybags.tempfly.aesthetic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.aesthetic.title.LegacyTitle;
import com.moneybags.tempfly.aesthetic.title.ModernTitle;
import com.moneybags.tempfly.aesthetic.title.Title;

public class TitleAPI {

	private static Title title;
	
    public static void initialize(TempFly tempfly) {
        // First, let's grab the server's version
        String version = Bukkit.getServer().getVersion();

        // Now let's check if we should use the legacy or modern title
    	  if (version.matches(".*(?<!1\\.)[2-9][0-9]\\.[0-9].*") || version.matches(".*1\\.(?!10|11)\\d{2,}.*")) {
    	      title = new ModernTitle();
        } else {
    	      title = new LegacyTitle();
    	}
    }
    
    public static void sendTitle(Player player, Integer fadeIn, Integer stay, Integer fadeOut, String title, String subtitle) {
    	TitleAPI.title.sendTitle(player, fadeIn, stay, fadeOut, title, subtitle);
    }

    public static void clearTitle(Player player) {
    	TitleAPI.title.clearTitle(player);
    }
}
