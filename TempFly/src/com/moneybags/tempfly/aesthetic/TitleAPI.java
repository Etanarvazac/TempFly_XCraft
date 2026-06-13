package com.moneybags.tempfly.aesthetic;

import org.bukkit.entity.Player;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.aesthetic.title.LegacyTitle;
import com.moneybags.tempfly.aesthetic.title.ModernTitle;
import com.moneybags.tempfly.aesthetic.title.Title;
import com.moneybags.tempfly.util.U;

public class TitleAPI {

	private static Title title;

    public static void initialize(TempFly tempfly) {
    	  if (U.isModernServer()) {
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
