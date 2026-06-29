package com.moneybags.tempfly.command.player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.moneybags.tempfly.TempFly;
import com.moneybags.tempfly.command.TempFlyCommand;
import com.moneybags.tempfly.util.Console;
import com.moneybags.tempfly.util.U;
import com.moneybags.tempfly.util.V;
import com.moneybags.tempfly.util.data.DataBridge.DataValue;
import com.moneybags.tempfly.util.data.DataPointer;

public class CmdDisplay extends TempFlyCommand {
  public CmdDisplay(TempFly tempfly, String[] args) {
    super(tempfly, args);
  }

  @Override
  public void executeAs(CommandSender s) {
    Console.debug("TF--| Executing CmdDisplay for sender: " + s.getName());
    if (!U.hasPermission(s, "tempfly.time.display")) {
      U.m(s, V.invalidPermission);
      Console.debug("TF--| CmdDisplay: Invalid permission for sender: " + s.getName());
      return;
    }

    boolean isPlr;
    Player player;
    Boolean newState;
    UUID targetId;
    UUID senderId;
    Boolean isSelf = true;

    if (!U.isPlayer(s)) {
      Console.debug("TF--| CmdDisplay: Sender is console.");
      senderId = null;
      isPlr = false;
    } else {
      Console.debug("TF--| CmdDisplay: Sender is player.");
      player = (Player) s;
      senderId = player.getUniqueId();
      isPlr = true;
    }

    // -- /tf display
    switch (args.length) {
      case 1:
        // Toggle self without args
        if (!isPlr) {
          U.m(s, V.invalidSenderConsole);
          Console.debug("TF--| CmdDisplay: Invalid sender console for toggle self without args.");
          return;
        }
        if (!U.hasPermission(s, "tempfly.time.display.self")) {
          Console.debug("TF--| CmdDisplay: Invalid permission for sender: " + s.getName());
          U.m(s, V.invalidPermission);
          return;
        }
        targetId = senderId;
        if (targetId == null) {
          U.m(s, V.invalidTarget);
          return;
        }
        DataPointer pointer = DataPointer.of(DataValue.PLAYER_DISPLAY_VISIBLE, targetId.toString());
        Boolean current;
        try {
          current = (Boolean) tempfly.getDataBridge().getValue(pointer);
        } catch (SQLException e) {
          U.m(s, V.sqlErrorPointer);
          return;
        }
        // Before running check, let's include a failsafe in the event this is null. This check will
        // use TempFly's default setting for Display State (true) as the fallback.ound
        if (current == null) {
          current = true;
        }
        newState = !current;
        Console.debug("TF--| CmdDisplay: Toggling self display state to " + newState);
        break;
      case 2:
        // Toggle self to a specific state (on/off)
        if (!isPlr) {
          Console.debug("TF--| CmdDisplay: Invalid sender console for toggle self to specific state.");
          U.m(s, V.invalidSenderConsole);
          return;
        }
        if (!U.hasPermission(s, "tempfly.time.display.self")) {
          Console.debug("TF--| CmdDisplay: Invalid permission for sender: " + s.getName());
          U.m(s, V.invalidPermission);
          return;
        }
        switch (args[1].toLowerCase()) {
          case "on": case "enable":
            newState = true;
            break;
          case "off": case "disable":
            newState = false;
            break;
          default:
            U.m(s, V.invalidDisplayState);
            return;
        }
        targetId = senderId;
        Console.debug("TF--| CmdDisplay: Setting self display state to " + newState);
        break;
      case 3:
        // Toggle display for another player
        if (!U.hasPermission(s, "tempfly.time.display.other")) {
          Console.debug("TF--| CmdDisplay: Invalid permission for sender: " + s.getName());
          U.m(s, V.invalidPermission);
          return;
        }
        switch (args[1].toLowerCase()) {
          case "on": case "enable":
            newState = true;
            break;
          case "off": case "disable":
            newState = false;
            break;
          default:
            U.m(s, V.invalidDisplayState);
            return;
        }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null || !target.isOnline()) {
          U.m(s, V.invalidPlayer);
          return;
        }
        targetId = target.getUniqueId();
        isSelf = false;
        Console.debug("TF--| CmdDisplay: Setting display state for another player " + target.getName() + " to " + newState);
        break;
      default:
        U.m(s, V.invalidCommand);
        Console.debug("TF--| CmdDisplay: Invalid argument length: " + args.length);
        return;
    }
    if (targetId == null || newState == null) {
      U.m(s, V.invalidTarget);
      return;
    }

    // -- Apply changes
    Console.debug("TF--| CmdDisplay: Applying display state change for player ID " + targetId + " to " + newState);
    DataPointer pointer = DataPointer.of(DataValue.PLAYER_DISPLAY_VISIBLE, targetId.toString());
    tempfly.getDataBridge().stageChange(pointer, newState);

    if (isSelf) {
      U.m(s, V.displaySelfSuccess);
    } else {
      U.m(s, V.displayOtherSuccess.replace("{PLAYER}", args[2]));
    }
  }

  @Override
  public List<String> getPotentialArguments(CommandSender s) {
    // args[0] = "display", args[1] = on/off, args[2] = player name
    
    if (args.length < 3 && U.hasPermission(s, "tempfly.time.display.self")) {
      // Completing first arg (on/off)
      return tempfly.getCommandManager().getToggleCompletions(true);
    } else if (args.length < 4 && U.hasPermission(s, "tempfly.time.display.other")) {
      // Completing second arg (player name)
      String partial = args.length >= 3 ? args[2] : "";
      return getPlayerArguments(partial);
    } else if (args.length >= 4) {
      // Too many args
      return Arrays.asList("[Error: Too many arguments]");
    }
    return new ArrayList<>();
  }
}









