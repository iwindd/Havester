====================================================================
         Havester - Bamboo Cutter Mod (for CobblemonOrigins)
====================================================================

INSTALL

   1. Copy the bundled Havester-*.jar to any folder on your PC.
      Example: D:\Games\CobblemonOrigins\Havester\build\libs\

   2. Open PowerShell as Administrator in your game folder and run:

      powershell -ExecutionPolicy Bypass -File install-for-friend.ps1

      OR double-click install-for-friend.ps1 if you have file association.

   3. Restart the game.

====================================================================
HOW TO USE

   [K]  Start / Stop Bamboo Cutter
   [O]  Open Settings GUI

====================================================================
SETTINGS (press O)

    Min Bamboo Height      2 - 10    (only cut stacks this tall or more)
    Height Priority        0.0 - 5.0 (higher = prefer taller bamboo over closer)
    Sell Threshold Stacks  1 - 10    (auto /shop when bamboo reaches N x 64)
   Scan Corner 1                    (stand at first corner and press Record 1)
    Scan Corner 2                    (stand at opposite corner and press Record 2)
    Zone Overlay                     (green client-only box shows recorded scan area when both corners set)
   Unpause When Tabbed Out          (game keeps running when tabbed out)
   Auto Sell                        (toggle automatic /shop selling)
   Collect Drops                    (toggle dropped bamboo collection)
    Hold W On Walk                   (toggle auto-walk toward target)
    Hold Jump On Walk                (toggle auto-jump toward target)
    Hold Sprint On Walk              (toggle auto-sprint toward target)

====================================================================
WHAT IT DOES

  1. Cut every reachable bamboo stack first (min height configurable)
  2. If no bamboo is reachable, walk directly toward the nearest bamboo (no pathfinding)
  3. Scan only inside the recorded corner box when both corners are set
  4. Green client-only zone overlay shows the recorded scan area
  5. Cut block #2 (not the root)
  6. Collect dropped bamboo items when enabled (walk directly to drops)
  7. When bamboo in inventory reaches threshold, auto-run /shop when enabled
  8. Click the category slot, then shift-right-click bamboo to sell
  9. If stuck walking to bamboo for 10s, blacklist that bamboo for 30s
  10. If no bamboo, retry every 10 seconds (does not stop)
  11. Game stays unpaused when tabbing out when enabled

====================================================================
REQUIREMENTS

  - Minecraft 1.21.1 with Fabric Loader 0.18.1+
  - Fabric API 0.116.7+
  - CobblemonOrigins modpack

====================================================================
