package com.moneybags.tempfly.command.player;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.command.TempFlyCommand;
import com.moneybags.tempfly.util.data.DataPointer;
import com.moneybags.tempfly.util.data.DataValue;

import java.util.UUID;

public class CmdDisplay extends TempFlyCommand {
  public CmdDisplay(TempFly tempfly, String[] args) {
    super(tempfly, args);
  }

  @Override
  public void execute(Player sender) {
    if (!hasPermission(sender, "tempfly.time.display")) {
      sender.sendMessage(ChatColor.RED + "You do not have permission to use the display command.");
      return;
    }

    Boolean newState = null;
    UUID targetId;
    Boolean isSelf = true;

    // -- /tf display
    if (args.length == 0) {
      if (!hasPermission(sender, "tempfly.time.display.self")) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to toggle the visibility of your flight timer.");
        return;
      }

      targetId = sender.getUniqueId();
      DataPointer pointer = DataPointer.of(DataValue.PLAYER_DISPLAY_VISIBLE, targetId);
      Boolean current = (boolean) tempfly.getDataBridge().getValue(pointer);
      newState = !current;
    }

    // -- /tf display [on|off]
    else if (args.length == 1) {
      if (!hasPermission(sender, "tempfly.time.display.self")) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to toggle the visibility of your flight timer.");
        return;
      }

      newState = parseState(args[0]);
      if (newState == null) {
        sender.sendMessage(ChatColor.RED + "Invalid state. Use 'on' or 'off' to set display visibility.");
        return;
      }

      targetId = sender.getUniqueId();
    }

    // -- /tf display [on|off] [player]
    else if (args.length == 2) {
      if (!hasPermission(sender, "tempfly.time.display.other")) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to toggle the visibility of another player's flight timer.");
        return;
      }

      newState = parseState(args[0]);
      if (newState == null) {
        sender.sendMessage(ChatColor.RED + "State was not specified. Command: /tf display [on|off] [player]");
        return;
      }

      Player target = Bukkit.getPlayer(args[1]);
      if (target == null || !target.isOnline()) {
        sender.sendMessage(ChatColor.RED + "Player was not found or is not currently online.");
        return;
      }

      targetId = target.getUniqueId();
      isSelf = false;
    }

    // -- Apply changes
    DataPointer pointer = DataPointer.of(DataValue.PLAYER_DISPLAY_VISIBLE, targetId);
    tempfly.getDataBridge().stageChange(pointer, newState);

    if (isSelf) {
      sender.sendMessage(ChatColor.YELLOW + "Your flight timer is now " + (newState ? "visible." : "hidden."));
    } else {
      sender.sendMessage(ChatColor.YELLOW + "Flight timer for " + args[1] + " is now " + (newState ? "visible." : "hidden."));
    }
  }
  private Boolean parseState(String input) {
    input = input.toLowerCase();
    if (input.equals("on")) return true;
    if (input.equals("off")) return false;
    return null;
  }
}








  
