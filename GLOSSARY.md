# LubeLogger connection

The app connects to a user's LubeLogger instance through configured server addresses.

## Language

**Server address**:
A configured URL through which the user accesses a LubeLogger instance.
_Avoid_: Server identity (different addresses may reach the same instance).

**Preferred address**:
The server address the user chooses to try first when establishing a new connection.
_Avoid_: Primary server (the preference applies to an address).

**Fallback address**:
An optional second server address used when the preferred address cannot be reached.
_Avoid_: Backup server (the address may reach the same instance).

**Active address**:
The configured server address currently selected for displaying LubeLogger.
_Avoid_: Preferred address (the active address may be the fallback).
